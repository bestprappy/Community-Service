package com.navio.communityservice.service;

import tools.jackson.databind.ObjectMapper;
import com.navio.communityservice.integration.SharedPlanDirectory;
import com.navio.communityservice.integration.SharedPlanDirectory.ListedPlan;
import com.navio.communityservice.integration.tripevents.TripEvent;
import com.navio.communityservice.repository.NotificationRepository;
import com.navio.communityservice.repository.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TripEventProcessorTests {

    private static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");
    private static final Instant LISTED_AT = NOW.minusSeconds(30);
    private static final UUID EVENT = UUID.fromString("00000000-0000-4000-8000-0000000000e1");
    private static final UUID OWNER = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final String TOKEN = "tok_Abc-123";
    private static final String CONSUMER = TripEventProcessor.CONSUMER;

    @Mock ProcessedEventRepository processed;
    @Mock NotificationRepository notifications;
    @Mock SharedPlanDirectory sharedPlans;
    @Mock InboxSignals signals;
    @Mock TransactionTemplate transactions;

    private final ObjectMapper json = new ObjectMapper();
    private TripEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new TripEventProcessor(processed, notifications, sharedPlans, signals, transactions,
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofDays(3));
        lenient().when(transactions.execute(any())).thenAnswer(call ->
                call.<TransactionCallback<?>>getArgument(0).doInTransaction(mock(TransactionStatus.class)));
    }

    @Test
    void notifiesFollowersAsOfTheListingTimeAndSignalsExactlyThem() {
        when(sharedPlans.listedByAuthor(OWNER)).thenReturn(List.of(new ListedPlan(TOKEN, "  Kyoto in autumn ")));
        when(processed.record(CONSUMER, EVENT, TripEvent.SHARED_PLAN_LISTED, "NOTIFIED")).thenReturn(true);
        when(notifications.notifyFollowersOfPlan(OWNER, TOKEN, "Kyoto in autumn", LISTED_AT)).thenReturn(List.of(ALICE));

        TripEventProcessor.Outcome outcome = processor.process(event(LISTED_AT));

        assertThat(outcome).isEqualTo(TripEventProcessor.Outcome.NOTIFIED);
        verify(signals).created(List.of(ALICE));
    }

    @Test
    void aRedeliveredEventCreatesNothingAndDoesNotAskTripPlanning() {
        when(processed.exists(CONSUMER, EVENT)).thenReturn(true);

        assertThat(processor.process(event(LISTED_AT))).isEqualTo(TripEventProcessor.Outcome.DUPLICATE);

        verifyNoInteractions(sharedPlans, notifications, signals);
    }

    @Test
    void aConcurrentDeliveryThatLosesTheClaimCreatesNothing() {
        when(sharedPlans.listedByAuthor(OWNER)).thenReturn(List.of(new ListedPlan(TOKEN, "Kyoto")));
        when(processed.record(CONSUMER, EVENT, TripEvent.SHARED_PLAN_LISTED, "NOTIFIED")).thenReturn(false);

        assertThat(processor.process(event(LISTED_AT))).isEqualTo(TripEventProcessor.Outcome.DUPLICATE);

        verify(notifications, never()).notifyFollowersOfPlan(any(), anyString(), any(), any());
        verifyNoInteractions(signals);
    }

    @Test
    void aPlanUnlistedOrMadeAnonymousBeforeDeliveryNotifiesNobody() {
        when(sharedPlans.listedByAuthor(OWNER)).thenReturn(List.of(new ListedPlan("another-token", "Other")));
        when(processed.record(CONSUMER, EVENT, TripEvent.SHARED_PLAN_LISTED, "NOT_LISTED")).thenReturn(true);

        assertThat(processor.process(event(LISTED_AT))).isEqualTo(TripEventProcessor.Outcome.NOT_LISTED);

        verify(notifications, never()).notifyFollowersOfPlan(any(), anyString(), any(), any());
        verify(signals).created(List.of());
    }

    @Test
    void anEventOlderThanTheDeliveryWindowIsRecordedWithoutNotifyingAnyone() {
        Instant old = NOW.minus(Duration.ofDays(4));
        when(processed.record(CONSUMER, EVENT, TripEvent.SHARED_PLAN_LISTED, "STALE")).thenReturn(true);

        assertThat(processor.process(event(old))).isEqualTo(TripEventProcessor.Outcome.STALE);

        verifyNoInteractions(sharedPlans, notifications);
    }

    @Test
    void eventTypesThisConsumerDoesNotHandleAreIgnoredWithoutARecord() throws Exception {
        TripEvent other = TripEvent.parse("""
                {"eventId":"%s","eventType":"TripCopied.v1","occurredAt":"%s","payload":{}}
                """.formatted(EVENT, NOW), json);

        assertThat(processor.process(other)).isEqualTo(TripEventProcessor.Outcome.IGNORED);

        verifyNoInteractions(processed, sharedPlans, notifications, signals);
    }

    @Test
    void tripPlanningBeingDownIsRaisedForRetryAndRecordsNothing() {
        when(sharedPlans.listedByAuthor(OWNER)).thenThrow(
                new SharedPlanDirectory.SharedPlanDirectoryUnavailableException("down", null));

        assertThatThrownBy(() -> processor.process(event(LISTED_AT)))
                .isInstanceOf(SharedPlanDirectory.SharedPlanDirectoryUnavailableException.class);

        verify(processed, never()).record(any(), any(), any(), any());
        verifyNoInteractions(notifications, signals);
    }

    @Test
    void anUnsupportedPayloadVersionIsMalformedBeforeAnythingIsRead() {
        TripEvent future = TripEvent.parse("""
                {"eventId":"%s","eventType":"SharedPlanListed.v1","occurredAt":"%s",
                 "payload":{"version":2,"ownerId":"%s","token":"%s"}}
                """.formatted(EVENT, NOW, OWNER, TOKEN), json);

        assertThatThrownBy(() -> processor.process(future)).isInstanceOf(TripEvent.MalformedTripEventException.class);

        verifyNoInteractions(processed, sharedPlans, notifications);
    }

    @Test
    void aVeryLongTitleIsCutToTheStoredLength() {
        String title = "x".repeat(260);
        when(sharedPlans.listedByAuthor(OWNER)).thenReturn(List.of(new ListedPlan(TOKEN, title)));
        when(processed.record(CONSUMER, EVENT, TripEvent.SHARED_PLAN_LISTED, "NOTIFIED")).thenReturn(true);
        when(notifications.notifyFollowersOfPlan(eq(OWNER), eq(TOKEN), anyString(), eq(LISTED_AT))).thenReturn(List.of());

        processor.process(event(LISTED_AT));

        verify(notifications).notifyFollowersOfPlan(OWNER, TOKEN, "x".repeat(200), LISTED_AT);
    }

    private TripEvent event(Instant listedAt) {
        return TripEvent.parse("""
                {"eventId":"%s","eventType":"SharedPlanListed.v1","occurredAt":"%s","producer":"trip-planning-service",
                 "partitionKey":"p","traceId":"t",
                 "payload":{"version":1,"publicationId":"%s","ownerId":"%s","token":"%s","revision":1,"listedAt":"%s"}}
                """.formatted(EVENT, listedAt, UUID.randomUUID(), OWNER, TOKEN, listedAt), json);
    }
}
