package com.navio.communityservice.dto;

import java.time.Instant;
import java.util.UUID;

/** Operator views of integration events the notification consumer could not handle. */
public final class NotificationEventDtos {

    private NotificationEventDtos() {
    }

    /** {@code payload} holds identifiers only (owner id, share token); producers never put content in events. */
    public record QuarantinedEventResponse(UUID id, String topic, int partition, long offset, UUID eventId,
                                           String eventType, String payload, String error, Instant quarantinedAt,
                                           Instant replayedAt, String replayOutcome) { }

    public record ReplayResponse(UUID id, String outcome) { }
}
