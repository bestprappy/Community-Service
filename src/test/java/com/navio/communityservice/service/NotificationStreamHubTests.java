package com.navio.communityservice.service;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationStreamHubTests {

    private static final Instant NOW = Instant.parse("2026-10-31T09:00:00Z");
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    private final List<RecordingEmitter> created = new ArrayList<>();

    private NotificationStreamHub hub(int perUser, int max, TaskExecutor executor) {
        return new NotificationStreamHub(executor, Clock.fixed(NOW, ZoneOffset.UTC), perUser, max, Duration.ofMinutes(30),
                timeout -> {
                    RecordingEmitter emitter = new RecordingEmitter(timeout);
                    created.add(emitter);
                    return emitter;
                });
    }

    private NotificationStreamHub hub() {
        return hub(5, 100, Runnable::run);
    }

    @Test
    void aNewStreamIsToldToReconcileAndEndsWhenItsTokenExpires() {
        NotificationStreamHub hub = hub();

        hub.connect(ALICE, NOW.plus(Duration.ofMinutes(4)));

        RecordingEmitter stream = created.getFirst();
        assertThat(stream.getTimeout()).isEqualTo(Duration.ofMinutes(4).toMillis());
        assertThat(stream.frames).hasSize(1);
        assertThat(stream.frames.getFirst()).contains("event:ready").contains("\"lifetimeSeconds\":240");
    }

    @Test
    void streamLifetimeStaysBetweenTheFloorAndTheCeiling() {
        NotificationStreamHub hub = hub();

        assertThat(hub.lifetime(NOW.plusSeconds(3))).isEqualTo(NotificationStreamHub.MIN_LIFETIME);
        assertThat(hub.lifetime(NOW.minusSeconds(30))).isEqualTo(NotificationStreamHub.MIN_LIFETIME);
        assertThat(hub.lifetime(NOW.plus(Duration.ofHours(8)))).isEqualTo(Duration.ofMinutes(30));
        assertThat(hub.lifetime(null)).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void aHintReachesOnlyTheRecipientsStreams() {
        NotificationStreamHub hub = hub();
        hub.connect(ALICE, NOW.plusSeconds(600));
        hub.connect(BOB, NOW.plusSeconds(600));

        hub.onInboxChanged(new InboxSignals.InboxChanged(Set.of(ALICE), InboxSignals.Kind.CREATED));

        assertThat(created.get(0).frames).hasSize(2);
        assertThat(created.get(0).frames.get(1)).contains("event:inbox.changed").contains("{\"kind\":\"created\"}");
        assertThat(created.get(1).frames).hasSize(1);
    }

    @Test
    void hintsCarryAKindButNeverNotificationContent() {
        NotificationStreamHub hub = hub();
        hub.connect(ALICE, NOW.plusSeconds(600));

        hub.onInboxChanged(new InboxSignals.InboxChanged(Set.of(ALICE), InboxSignals.Kind.REMOVED));
        hub.onInboxChanged(new InboxSignals.InboxChanged(Set.of(ALICE), InboxSignals.Kind.READ_STATE));

        List<String> frames = created.getFirst().frames;
        assertThat(frames.get(1)).contains("event:inbox.changed").endsWith("data:{\"kind\":\"removed\"}\n\n");
        assertThat(frames.get(2)).contains("event:read-state.changed").endsWith("data:{\"kind\":\"read_state\"}\n\n");
    }

    @Test
    void everyTabOfTheSameAccountReceivesTheHint() {
        NotificationStreamHub hub = hub();
        hub.connect(ALICE, NOW.plusSeconds(600));
        hub.connect(ALICE, NOW.plusSeconds(600));

        hub.onInboxChanged(new InboxSignals.InboxChanged(Set.of(ALICE), InboxSignals.Kind.CREATED));

        assertThat(created).allSatisfy(stream -> assertThat(stream.frames).hasSize(2));
    }

    @Test
    void oneAccountOpeningTooManyStreamsClosesItsOldest() {
        NotificationStreamHub hub = hub(2, 100, Runnable::run);

        hub.connect(ALICE, NOW.plusSeconds(600));
        hub.connect(ALICE, NOW.plusSeconds(600));
        hub.connect(ALICE, NOW.plusSeconds(600));

        assertThat(created.get(0).completed).isTrue();
        assertThat(created.get(1).completed).isFalse();
        assertThat(created.get(2).completed).isFalse();
        assertThat(hub.streamsFor(ALICE)).isEqualTo(2);
        assertThat(hub.activeStreams()).isEqualTo(2);
    }

    @Test
    void aFullInstanceRefusesNewStreamsWithoutLeakingACount() {
        NotificationStreamHub hub = hub(5, 1, Runnable::run);
        hub.connect(ALICE, NOW.plusSeconds(600));

        assertThatThrownBy(() -> hub.connect(BOB, NOW.plusSeconds(600)))
                .isInstanceOf(NotificationStreamHub.StreamCapacityException.class);
        assertThat(hub.activeStreams()).isEqualTo(1);
        assertThat(hub.streamsFor(BOB)).isZero();
    }

    @Test
    void aStreamWhoseBrowserLeftIsDroppedOnTheNextWrite() {
        NotificationStreamHub hub = hub();
        hub.connect(ALICE, NOW.plusSeconds(600));
        created.getFirst().failWrites = true;

        hub.onInboxChanged(new InboxSignals.InboxChanged(Set.of(ALICE), InboxSignals.Kind.CREATED));

        assertThat(hub.streamsFor(ALICE)).isZero();
        assertThat(hub.activeStreams()).isZero();
    }

    @Test
    void theHeartbeatReachesEveryOpenStreamAsAComment() {
        NotificationStreamHub hub = hub();
        hub.connect(ALICE, NOW.plusSeconds(600));
        hub.connect(BOB, NOW.plusSeconds(600));

        hub.heartbeat();

        assertThat(created).allSatisfy(stream -> assertThat(stream.frames.getLast()).isEqualTo(":hb\n\n"));
    }

    @Test
    void aSaturatedDispatcherDropsTheHintInsteadOfFailingTheCommit() {
        NotificationStreamHub hub = hub(5, 100, task -> {
            throw new RejectedExecutionException("full");
        });
        hub.connect(ALICE, NOW.plusSeconds(600));

        assertThatCode(() -> hub.onInboxChanged(new InboxSignals.InboxChanged(Set.of(ALICE), InboxSignals.Kind.CREATED)))
                .doesNotThrowAnyException();
        assertThat(created.getFirst().frames).hasSize(1);
    }

    @Test
    void shuttingDownEndsEveryStreamSoBrowsersReconnectElsewhere() {
        NotificationStreamHub hub = hub();
        hub.connect(ALICE, NOW.plusSeconds(600));
        hub.connect(BOB, NOW.plusSeconds(600));

        hub.shutdown();

        assertThat(created).allSatisfy(stream -> assertThat(stream.completed).isTrue());
        assertThat(hub.activeStreams()).isZero();
    }

    /** Captures the exact SSE text each frame would put on the wire. */
    static final class RecordingEmitter extends SseEmitter {
        final List<String> frames = new ArrayList<>();
        boolean failWrites;
        boolean completed;

        RecordingEmitter(Long timeout) {
            super(timeout);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failWrites) throw new IOException("Broken pipe");
            StringBuilder text = new StringBuilder();
            for (var part : builder.build()) text.append(part.getData());
            frames.add(text.toString());
        }

        @Override
        public synchronized void complete() {
            completed = true;
        }

        @Override
        public synchronized void completeWithError(Throwable failure) {
            completed = true;
        }
    }
}
