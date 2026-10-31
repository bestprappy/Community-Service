package com.navio.communityservice;

import com.navio.communityservice.integration.SharedPlanDirectory;
import com.navio.communityservice.integration.SharedPlanDirectory.ListedPlan;
import com.navio.communityservice.integration.tripevents.TripEvent;
import com.navio.communityservice.media.ObjectStorage;
import com.navio.communityservice.repository.QuarantinedEventRepository;
import com.navio.communityservice.service.InboxSignals;
import com.navio.communityservice.service.NotificationService;
import com.navio.communityservice.service.QuarantinedEventService;
import com.navio.communityservice.service.TripEventProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Follower notifications from trip.events.v1 against real PostgreSQL, because
 * exactly-once delivery, the event-time audience and dedupe with the legacy
 * announcement path are enforced in SQL.
 */
@SpringBootTest
@ActiveProfiles("postgres")
@RecordApplicationEvents
@EnabledIfEnvironmentVariable(named = "COMMUNITY_TEST_DB_URL", matches = ".+")
class NotificationDeliveryPostgresTests {

    @Autowired TripEventProcessor processor;
    @Autowired NotificationService notificationService;
    @Autowired QuarantinedEventRepository quarantine;
    @Autowired QuarantinedEventService quarantinedEvents;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired ApplicationEvents events;
    @MockitoBean ObjectStorage storage;
    @MockitoBean SharedPlanDirectory sharedPlans;

    final UUID author = UUID.randomUUID();
    final UUID early = UUID.randomUUID();
    final UUID late = UUID.randomUUID();
    final UUID muted = UUID.randomUUID();
    final String token = "tok" + UUID.randomUUID().toString().replace("-", "");

    @AfterEach
    void cleanup() {
        for (UUID user : List.of(author, early, late, muted)) {
            jdbc.update("DELETE FROM social.user_follows WHERE follower_id=? OR followee_id=?", user, user);
            jdbc.update("DELETE FROM notif.notifications WHERE recipient_id=? OR actor_id=?", user, user);
        }
        jdbc.update("DELETE FROM notif.quarantined_events WHERE payload LIKE ?", "%" + token + "%");
    }

    @Test
    void aListingReachesOnlyThoseWhoFollowedBeforeItOnceAndSharesDedupeWithTheLegacyPath() {
        Instant listedAt = Instant.now().minusSeconds(60);
        follow(early, listedAt.minusSeconds(600), true);
        follow(muted, listedAt.minusSeconds(600), false);
        follow(late, listedAt.plusSeconds(30), true);
        when(sharedPlans.listedByAuthor(author)).thenReturn(List.of(new ListedPlan(token, "Kyoto in autumn")));
        TripEvent event = event(UUID.randomUUID(), listedAt);

        assertThat(processor.process(event)).isEqualTo(TripEventProcessor.Outcome.NOTIFIED);
        assertThat(recipientsOfPlan()).containsExactly(early);
        assertThat(events.stream(InboxSignals.InboxChanged.class))
                .anySatisfy(change -> assertThat(change.recipients()).isEqualTo(Set.of(early)));

        // Redelivery of the same event id, and a different event for the same plan, create nothing.
        assertThat(processor.process(event)).isEqualTo(TripEventProcessor.Outcome.DUPLICATE);
        assertThat(processor.process(event(UUID.randomUUID(), listedAt))).isEqualTo(TripEventProcessor.Outcome.NOTIFIED);
        // An old client still calling the browser announcement is answered but writes
        // nothing: the event is the single writer, so the late follower stays excluded.
        assertThat(notificationService.planShared(author, token)).isZero();
        assertThat(recipientsOfPlan()).containsExactly(early);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notif.processed_events WHERE event_id=?", Long.class,
                event.eventId())).isEqualTo(1);
    }

    @Test
    void aPlanUnlistedBeforeDeliveryIsRecordedButNotifiesNobody() {
        follow(early, Instant.now().minus(Duration.ofDays(1)), true);
        when(sharedPlans.listedByAuthor(author)).thenReturn(List.of());
        TripEvent event = event(UUID.randomUUID(), Instant.now());

        assertThat(processor.process(event)).isEqualTo(TripEventProcessor.Outcome.NOT_LISTED);
        assertThat(recipientsOfPlan()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT outcome FROM notif.processed_events WHERE event_id=?", String.class,
                event.eventId())).isEqualTo("NOT_LISTED");
    }

    @Test
    void aQuarantinedEventReplaysOnceAndReQuarantiningRefreshesTheSameRow() throws Exception {
        follow(early, Instant.now().minus(Duration.ofDays(1)), true);
        when(sharedPlans.listedByAuthor(author)).thenReturn(List.of(new ListedPlan(token, "Kyoto")));
        UUID eventId = UUID.randomUUID();
        String body = body(eventId, Instant.now());
        quarantine.quarantine("trip.events.v1", 0, 4242L, "k", eventId, TripEvent.SHARED_PLAN_LISTED, body, "first failure");
        quarantine.quarantine("trip.events.v1", 0, 4242L, "k", eventId, TripEvent.SHARED_PLAN_LISTED, body, "second failure");
        UUID id = jdbc.queryForObject("SELECT id FROM notif.quarantined_events WHERE event_id=?", UUID.class, eventId);
        assertThat(quarantine.find(id).orElseThrow().error()).isEqualTo("second failure");

        assertThat(quarantinedEvents.replay(id, UUID.randomUUID()).outcome()).isEqualTo("NOTIFIED");
        assertThat(quarantinedEvents.replay(id, UUID.randomUUID()).outcome()).isEqualTo("DUPLICATE");
        assertThat(recipientsOfPlan()).containsExactly(early);
        assertThat(quarantine.find(id).orElseThrow().replayedAt()).isNotNull();
        assertThat(quarantine.list(true, org.springframework.data.domain.PageRequest.of(0, 50)).getContent())
                .noneMatch(row -> row.id().equals(id));
    }

    private void follow(UUID follower, Instant at, boolean alerts) {
        jdbc.update("INSERT INTO social.user_follows(follower_id, followee_id, notify, created_at) VALUES (?,?,?,?)",
                follower, author, alerts, Timestamp.from(at));
    }

    private List<UUID> recipientsOfPlan() {
        return jdbc.queryForList("SELECT recipient_id FROM notif.notifications WHERE plan_token=? ORDER BY recipient_id",
                UUID.class, token);
    }

    private TripEvent event(UUID id, Instant listedAt) {
        return TripEvent.parse(body(id, listedAt), json);
    }

    private String body(UUID id, Instant listedAt) {
        return """
                {"eventId":"%s","eventType":"SharedPlanListed.v1","occurredAt":"%s","producer":"trip-planning-service",
                 "partitionKey":"p","traceId":"t",
                 "payload":{"version":1,"publicationId":"%s","ownerId":"%s","token":"%s","revision":1,"listedAt":"%s"}}
                """.formatted(id, listedAt, UUID.randomUUID(), author, token, listedAt);
    }
}
