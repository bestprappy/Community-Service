package com.navio.communityservice.service;

import com.navio.communityservice.dto.SocialDtos.NotificationPreferences;
import com.navio.communityservice.dto.SocialDtos.NotificationResponse;
import com.navio.communityservice.dto.SocialDtos.UnreadCountResponse;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.integration.SharedPlanDirectory;
import com.navio.communityservice.integration.SharedPlanDirectory.ListedPlan;
import com.navio.communityservice.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The recipient's in-app notifications.
 *
 * <p>Reads are always scoped to the caller. Post notifications disappear with
 * their post (database cascade) or a hidden community (query filter). Plan
 * notifications are re-checked against trip planning on every read, so a plan
 * that was unlisted, revoked or re-published anonymously is shown without its
 * title or link.
 */
@Service
@Slf4j
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notifications;
    private final SharedPlanDirectory sharedPlans;
    private final InboxSignals signals;
    private final boolean legacyPlanAnnouncements;

    public NotificationService(NotificationRepository notifications, SharedPlanDirectory sharedPlans,
                               InboxSignals signals,
                               @Value("${navio.notifications.legacy-plan-announcements.enabled:false}")
                               boolean legacyPlanAnnouncements) {
        this.notifications = notifications;
        this.sharedPlans = sharedPlans;
        this.signals = signals;
        this.legacyPlanAnnouncements = legacyPlanAnnouncements;
    }

    public Page<NotificationResponse> list(UUID recipient, boolean unreadOnly, int page, int size) {
        Page<NotificationResponse> result = notifications.list(recipient, unreadOnly, PostService.pagination(page, size));
        Map<UUID, Optional<Set<String>>> listedByActor = new HashMap<>();
        return result.map(notification -> {
            if (!"followed_plan".equals(notification.type())) return notification;
            var listed = listedByActor.computeIfAbsent(notification.actorId(), this::listedTokens);
            boolean stillShared = listed.map(tokens -> tokens.contains(notification.planToken())).orElse(false);
            if (stillShared) return notification;
            return new NotificationResponse(notification.id(), notification.type(), notification.actorId(),
                    notification.createdAt(), notification.read(), null, null, null, null, null, false);
        });
    }

    public UnreadCountResponse unreadCount(UUID recipient) {
        return new UnreadCountResponse(notifications.unreadCount(recipient));
    }

    @Transactional
    public UnreadCountResponse markRead(UUID recipient, UUID id) {
        if (!notifications.markRead(recipient, id)) {
            throw new GroupException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        signals.readStateChanged(recipient);
        return unreadCount(recipient);
    }

    @Transactional
    public UnreadCountResponse markAllRead(UUID recipient) {
        if (notifications.markAllRead(recipient) > 0) signals.readStateChanged(recipient);
        return unreadCount(recipient);
    }

    public NotificationPreferences preferences(UUID user) {
        return notifications.preferences(user);
    }

    @Transactional
    public NotificationPreferences updatePreferences(UUID user, NotificationPreferences preferences) {
        notifications.savePreferences(user, preferences);
        return notifications.preferences(user);
    }

    /** Called inside the post's own transaction, so a rolled-back post notifies nobody. */
    @Transactional
    public void postPublished(UUID author, UUID postId) {
        signals.created(notifications.notifyFollowersOfPost(author, postId));
    }

    /**
     * Tells followers that the caller listed a plan on Explore. The caller can only
     * announce a plan trip planning confirms they listed under their own name, and
     * the per-recipient dedupe key means repeating the call notifies nobody twice.
     *
     * <p>Legacy path for clients released before trip planning announced listings
     * itself through {@code trip.events.v1}. It still verifies ownership and answers
     * as before, but only fans out when
     * {@code navio.notifications.legacy-plan-announcements.enabled} is set (the
     * fallback if the trip outbox relay is ever switched off). Otherwise the event
     * is the single writer: it applies the event-time audience this path cannot,
     * because a browser call cannot say when the plan was listed. Both share the
     * {@code plan:<token>} dedupe key, so enabling both never notifies anyone twice.
     * Remove once no released client calls it.
     *
     * @return how many followers this call notified
     */
    @Transactional
    public int planShared(UUID author, String token) {
        List<ListedPlan> listed;
        try {
            listed = sharedPlans.listedByAuthor(author);
        } catch (SharedPlanDirectory.SharedPlanDirectoryUnavailableException exception) {
            throw new GroupException(HttpStatus.SERVICE_UNAVAILABLE, "Shared plans cannot be checked right now; try again shortly");
        }
        ListedPlan plan = listed.stream().filter(candidate -> token.equals(candidate.token())).findFirst()
                .orElseThrow(() -> new GroupException(HttpStatus.NOT_FOUND,
                        "Only a plan you listed on Explore under your name can be announced"));
        if (!legacyPlanAnnouncements) return 0;
        String title = plan.title() == null ? null : plan.title().strip();
        if (title != null && title.length() > 200) title = title.substring(0, 200);
        List<UUID> notified = notifications.notifyFollowersOfPlan(author, token, title, null);
        signals.created(notified);
        return notified.size();
    }

    /** Empty when trip planning cannot answer: the notification is then shown without its link. */
    private Optional<Set<String>> listedTokens(UUID actor) {
        try {
            return Optional.of(sharedPlans.listedByAuthor(actor).stream().map(ListedPlan::token).collect(Collectors.toSet()));
        } catch (SharedPlanDirectory.SharedPlanDirectoryUnavailableException exception) {
            log.warn("Plan notification links hidden: trip planning unavailable");
            return Optional.empty();
        }
    }
}
