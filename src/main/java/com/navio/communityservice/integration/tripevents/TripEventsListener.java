package com.navio.communityservice.integration.tripevents;

import tools.jackson.databind.ObjectMapper;
import com.navio.communityservice.service.TripEventProcessor;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code trip.events.v1} for follower notifications.
 *
 * <p>The offset is committed only after {@link TripEventProcessor} returns, which
 * is after its database transaction committed. A failure is retried with backoff
 * by {@link TripEventsKafkaConfiguration}; a malformed record, or one that keeps
 * failing, is quarantined for an operator rather than dropped.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "navio.notifications.trip-events.enabled", havingValue = "true", matchIfMissing = true)
public class TripEventsListener {

    private final TripEventProcessor processor;
    private final ObjectMapper json;

    @KafkaListener(id = "community-trip-events",
            topics = "${navio.notifications.trip-events.topic:trip.events.v1}",
            groupId = TripEventProcessor.CONSUMER,
            containerFactory = TripEventsKafkaConfiguration.CONTAINER_FACTORY)
    public void onRecord(ConsumerRecord<String, String> record) {
        processor.process(TripEvent.parse(record.value(), json));
    }
}
