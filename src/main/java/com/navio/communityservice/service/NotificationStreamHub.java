package com.navio.communityservice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Live inbox hints over Server-Sent Events.
 *
 * <h2>Protocol</h2>
 * <ul>
 *   <li>{@code ready}: sent on connect. The browser reconciles its inbox from the
 *       REST API, because anything committed while it was disconnected is not
 *       replayed here.</li>
 *   <li>{@code inbox.changed}: new notifications arrived or some were removed.</li>
 *   <li>{@code read-state.changed}: the same account marked something read elsewhere.</li>
 *   <li>A comment line every {@code heartbeat} keeps proxies from timing the stream out.</li>
 * </ul>
 * Frames carry no notification content, only a kind, so a queued frame cannot
 * leak a title the recipient has since lost access to. Counts always come from
 * the authorised API.
 *
 * <h2>Lifecycle and limits</h2>
 * A stream ends no later than its access token expires; the browser reconnects
 * with a fresh token. Each account keeps at most {@code per-user} streams (the
 * oldest is closed) and the instance at most {@code max-connections} (new ones
 * are refused). Dispatch runs after commit on a separate executor so a slow
 * socket never holds a database transaction.
 *
 * <p>Delivery reaches streams held by <em>this</em> instance only. Community runs
 * as one instance; running several needs a broadcast channel between them before
 * live delivery can be relied on. The durable inbox is correct either way.
 */
@Component
@Slf4j
public class NotificationStreamHub {

    static final Duration MIN_LIFETIME = Duration.ofSeconds(15);

    private final Map<UUID, Set<Connection>> byUser = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();
    private final AtomicLong opened = new AtomicLong();
    private final TaskExecutor dispatcher;
    private final Clock clock;
    private final int perUser;
    private final int maxConnections;
    private final Duration maxLifetime;
    private final Function<Long, SseEmitter> emitters;

    @org.springframework.beans.factory.annotation.Autowired
    public NotificationStreamHub(Clock clock,
                                 @Value("${navio.notifications.stream.per-user:5}") int perUser,
                                 @Value("${navio.notifications.stream.max-connections:2000}") int maxConnections,
                                 @Value("${navio.notifications.stream.max-lifetime:PT30M}") Duration maxLifetime) {
        this(boundedDispatcher(), clock, perUser, maxConnections, maxLifetime, SseEmitter::new);
    }

    /**
     * A small private pool with a bounded queue: a burst of hints can never grow
     * memory without limit, and a full queue drops hints (browsers reconcile)
     * rather than blocking the committing thread.
     */
    private static TaskExecutor boundedDispatcher() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(2_000);
        executor.setThreadNamePrefix("notif-stream-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    NotificationStreamHub(TaskExecutor dispatcher, Clock clock, int perUser, int maxConnections,
                          Duration maxLifetime, Function<Long, SseEmitter> emitters) {
        this.dispatcher = dispatcher;
        this.clock = clock;
        this.perUser = perUser;
        this.maxConnections = maxConnections;
        this.maxLifetime = maxLifetime;
        this.emitters = emitters;
    }

    /** Raised when this instance already holds as many streams as it allows. */
    public static class StreamCapacityException extends RuntimeException {
        public StreamCapacityException() {
            super("Live updates are busy; the inbox still refreshes on its own");
        }
    }

    /**
     * Opens a stream for an authenticated account.
     *
     * @param tokenExpiresAt when the caller's access token expires; the stream ends then
     */
    public SseEmitter connect(UUID user, Instant tokenExpiresAt) {
        if (total.incrementAndGet() > maxConnections) {
            total.decrementAndGet();
            throw new StreamCapacityException();
        }
        Duration lifetime = lifetime(tokenExpiresAt);
        Connection connection = new Connection(user, emitters.apply(lifetime.toMillis()), opened.incrementAndGet());
        Set<Connection> mine = byUser.computeIfAbsent(user, ignored -> new CopyOnWriteArraySet<>());
        mine.add(connection);
        connection.emitter.onCompletion(() -> remove(connection));
        connection.emitter.onTimeout(() -> close(connection));
        connection.emitter.onError(error -> remove(connection));
        evictOldest(mine);
        connection.send(SseEmitter.event().name("ready").reconnectTime(5_000)
                .data("{\"lifetimeSeconds\":" + lifetime.toSeconds() + "}"));
        return connection.emitter;
    }

    Duration lifetime(Instant tokenExpiresAt) {
        Duration untilExpiry = tokenExpiresAt == null ? maxLifetime : Duration.between(clock.instant(), tokenExpiresAt);
        if (untilExpiry.compareTo(MIN_LIFETIME) < 0) return MIN_LIFETIME;
        return untilExpiry.compareTo(maxLifetime) > 0 ? maxLifetime : untilExpiry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInboxChanged(InboxSignals.InboxChanged change) {
        List<Connection> targets = new ArrayList<>();
        for (UUID recipient : change.recipients()) {
            Set<Connection> streams = byUser.get(recipient);
            if (streams != null) targets.addAll(streams);
        }
        if (targets.isEmpty()) return;
        String name = change.kind() == InboxSignals.Kind.READ_STATE ? "read-state.changed" : "inbox.changed";
        String data = "{\"kind\":\"" + change.kind().name().toLowerCase(Locale.ROOT) + "\"}";
        try {
            dispatcher.execute(() -> targets.forEach(target -> target.send(SseEmitter.event().name(name).data(data))));
        } catch (RuntimeException rejected) {
            // A saturated executor drops the hint, not the notification: browsers
            // reconcile on reconnect, focus and their fallback poll.
            log.warn("Live inbox hint dropped for {} stream(s): dispatcher rejected the task", targets.size());
        }
    }

    @Scheduled(fixedRateString = "${navio.notifications.stream.heartbeat-ms:25000}",
            initialDelayString = "${navio.notifications.stream.heartbeat-ms:25000}")
    public void heartbeat() {
        for (Set<Connection> streams : byUser.values()) {
            for (Connection connection : streams) connection.send(SseEmitter.event().comment("hb"));
        }
    }

    public int activeStreams() {
        return total.get();
    }

    int streamsFor(UUID user) {
        Set<Connection> streams = byUser.get(user);
        return streams == null ? 0 : streams.size();
    }

    /** Ends every stream so browsers reconnect to the next instance during a deploy. */
    @PreDestroy
    public void shutdown() {
        for (Set<Connection> streams : byUser.values()) {
            for (Connection connection : streams) close(connection);
        }
        if (dispatcher instanceof ThreadPoolTaskExecutor pool) pool.shutdown();
    }

    private void evictOldest(Set<Connection> mine) {
        while (mine.size() > perUser) {
            mine.stream().min(Comparator.comparingLong(Connection::sequence)).ifPresent(this::close);
        }
    }

    private void close(Connection connection) {
        remove(connection);
        try {
            connection.emitter.complete();
        } catch (RuntimeException ignored) {
            // Already completed or the response is gone.
        }
    }

    private void remove(Connection connection) {
        Set<Connection> streams = byUser.get(connection.user);
        if (streams != null && streams.remove(connection)) {
            total.decrementAndGet();
            if (streams.isEmpty()) byUser.remove(connection.user, streams);
        }
    }

    private final class Connection {
        private final UUID user;
        private final SseEmitter emitter;
        /** Opening order; the lowest is the oldest stream. */
        private final long sequence;

        private Connection(UUID user, SseEmitter emitter, long sequence) {
            this.user = user;
            this.emitter = emitter;
            this.sequence = sequence;
        }

        long sequence() {
            return sequence;
        }

        /** Serialised per stream; a failed write means the browser is gone. */
        synchronized void send(SseEmitter.SseEventBuilder event) {
            try {
                emitter.send(event);
            } catch (IOException | IllegalStateException gone) {
                remove(this);
                try {
                    emitter.completeWithError(gone);
                } catch (RuntimeException ignored) {
                    // Response already closed.
                }
            }
        }
    }
}
