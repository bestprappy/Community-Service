package com.navio.communityservice.service;

import com.navio.communityservice.integration.SharedPlanDirectory;
import com.navio.communityservice.integration.SharedPlanDirectory.ListedPlan;
import com.navio.communityservice.integration.tripevents.TripEvent;
import com.navio.communityservice.repository.NotificationRepository;
import com.navio.communityservice.repository.ProcessedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Turns {@code trip.events.v1} events into follower notifications, exactly once
 * per event id and at most once per recipient and plan.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Eligibility is decided when the event is handled, not when it was sent:
 *       trip planning is asked whether the plan is still listed under the owner's
 *       name. A delayed event for a plan that was since unlisted, revoked or made
 *       anonymous notifies nobody.</li>
 *   <li>Only accounts that already followed the owner when the plan was listed are
 *       notified, so a late event never reaches someone who followed afterwards.
 *       Blocks, per-person alerts, type preferences and the burst cap apply as for
 *       every other notification.</li>
 *   <li>An event older than {@code max-age} is recorded but not delivered: a stale
 *       backlog after an outage must not flood inboxes with old news.</li>
 *   <li>The trip planning lookup happens outside the database transaction; the
 *       processed-event row and the notifications commit together, so a crash
 *       either leaves both or neither and redelivery finishes the job.</li>
 * </ul>
 */
@Service
@Slf4j
public class TripEventProcessor {

    public static final String CONSUMER = "community-notifications";

    public enum Outcome { NOTIFIED, NOT_LISTED, STALE, DUPLICATE, IGNORED }

    private final ProcessedEventRepository processed;
    private final NotificationRepository notifications;
    private final SharedPlanDirectory sharedPlans;
    private final InboxSignals signals;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Duration maxAge;

    public TripEventProcessor(ProcessedEventRepository processed, NotificationRepository notifications,
                              SharedPlanDirectory sharedPlans, InboxSignals signals, TransactionTemplate transactions,
                              Clock clock,
                              @Value("${navio.notifications.trip-events.max-age:P3D}") Duration maxAge) {
        this.processed = processed;
        this.notifications = notifications;
        this.sharedPlans = sharedPlans;
        this.signals = signals;
        this.transactions = transactions;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    /**
     * @throws TripEvent.MalformedTripEventException when the event cannot ever be handled
     * @throws SharedPlanDirectory.SharedPlanDirectoryUnavailableException when trip planning
     *         cannot answer right now; the caller retries the same event later
     */
    public Outcome process(TripEvent event) {
        if (!TripEvent.SHARED_PLAN_LISTED.equals(event.eventType())) return Outcome.IGNORED;
        TripEvent.SharedPlanListed listed = event.sharedPlanListed();
        if (processed.exists(CONSUMER, event.eventId())) return Outcome.DUPLICATE;

        if (event.occurredAt().isBefore(clock.instant().minus(maxAge))) {
            return finish(event, Outcome.STALE, () -> List.of());
        }

        ListedPlan plan = sharedPlans.listedByAuthor(listed.ownerId()).stream()
                .filter(candidate -> listed.token().equals(candidate.token()))
                .findFirst().orElse(null);
        if (plan == null) return finish(event, Outcome.NOT_LISTED, () -> List.of());

        String title = plan.title() == null ? null : plan.title().strip();
        if (title != null && title.length() > 200) title = title.substring(0, 200);
        String finalTitle = title;
        return finish(event, Outcome.NOTIFIED, () -> notifications.notifyFollowersOfPlan(
                listed.ownerId(), listed.token(), finalTitle, listed.listedAt()));
    }

    private Outcome finish(TripEvent event, Outcome outcome, java.util.function.Supplier<List<UUID>> deliver) {
        Outcome result = transactions.execute(status -> {
            if (!processed.record(CONSUMER, event.eventId(), event.eventType(), outcome.name())) {
                return Outcome.DUPLICATE;
            }
            List<UUID> notified = deliver.get();
            signals.created(notified);
            log.info("Trip event {} ({}) handled: {} recipient(s), {}", event.eventId(), event.eventType(),
                    notified.size(), outcome);
            return outcome;
        });
        return result == null ? Outcome.DUPLICATE : result;
    }
}
