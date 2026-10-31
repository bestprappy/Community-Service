package com.navio.communityservice.integration.tripevents;

import tools.jackson.databind.ObjectMapper;
import com.navio.communityservice.repository.QuarantinedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.util.backoff.ExponentialBackOff;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Retry and quarantine policy for the notification consumer.
 *
 * <p>A transient failure (trip planning or the database briefly unavailable) is
 * retried in place with exponential backoff, 1 s doubling to 30 s, for about
 * three minutes; the partition waits, preserving per-plan order. A malformed
 * record is not retried. Either way the record is then written to
 * {@code notif.quarantined_events} and its offset committed. If the quarantine
 * write itself fails, the error handler re-seeks and the record is tried again,
 * so it is never lost silently.
 *
 * <p>The consumer has its own factory with String deserializers set on it
 * directly: the Spring Cloud Stream Kafka binder (pulled in by Cloud Bus)
 * defaults the shared {@code spring.kafka.consumer.*} deserializers to byte
 * arrays, and changing the shared properties could break the bus. Connection
 * settings still come from {@code spring.kafka.*}; the group id comes from the
 * listener. This policy applies to this factory only, not to other listeners.
 */
@Configuration
@Slf4j
@ConditionalOnProperty(name = "navio.notifications.trip-events.enabled", havingValue = "true", matchIfMissing = true)
public class TripEventsKafkaConfiguration {

    static final int MAX_RETRIES = 9;
    public static final String CONTAINER_FACTORY = "tripEventsContainerFactory";

    @Bean(CONTAINER_FACTORY)
    ConcurrentKafkaListenerContainerFactory<String, String> tripEventsContainerFactory(
            KafkaProperties kafka, QuarantinedEventRepository quarantine, ObjectMapper json) {
        Map<String, Object> consumer = new HashMap<>(kafka.buildConsumerProperties());
        consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumer.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumer, new StringDeserializer(), new StringDeserializer()));
        factory.setCommonErrorHandler(errorHandler(quarantine, json));
        return factory;
    }

    static CommonErrorHandler errorHandler(QuarantinedEventRepository quarantine, ObjectMapper json) {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000, 2.0);
        backOff.setMaxInterval(30_000);
        backOff.setMaxAttempts(MAX_RETRIES);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, failure) -> quarantine(quarantine, json, record, failure), backOff);
        handler.addNotRetryableExceptions(TripEvent.MalformedTripEventException.class);
        handler.setCommitRecovered(true);
        return handler;
    }

    private static void quarantine(QuarantinedEventRepository quarantine, ObjectMapper json,
                                   ConsumerRecord<?, ?> record, Exception failure) {
        Throwable cause = failure instanceof ListenerExecutionFailedException && failure.getCause() != null
                ? failure.getCause() : failure;
        String value = record.value() == null ? null : record.value().toString();
        UUID eventId = null;
        String eventType = null;
        // Diagnostics are best effort and read independently, so a bad id still
        // leaves the type; the raw record is stored regardless.
        try {
            var root = json.readTree(value == null ? "" : value);
            if (root != null && root.hasNonNull("eventType")) eventType = root.get("eventType").asText();
            if (root != null && root.hasNonNull("eventId")) eventId = UUID.fromString(root.get("eventId").asText());
        } catch (Exception ignored) {
            // Unparseable JSON or a non-UUID id: keep whatever was read.
        }
        String reason = cause.getClass().getSimpleName() + ": " + cause.getMessage();
        quarantine.quarantine(record.topic(), record.partition(), record.offset(),
                record.key() == null ? null : record.key().toString(), eventId, eventType, value, reason);
        log.error("Quarantined {} event {} at {}-{}@{}: {}", eventType, eventId, record.topic(), record.partition(),
                record.offset(), reason);
    }
}
