package com.navio.communityservice.integration.tripevents;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TripEventTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String EVENT = "00000000-0000-4000-8000-0000000000e1";
    private static final String OWNER = "00000000-0000-4000-8000-00000000000b";

    @Test
    void readsTheDocumentedEnvelopeAndItsListingFacts() {
        TripEvent event = TripEvent.parse(envelope("""
                {"version":1,"ownerId":"%s","token":"AbC_12-x","revision":3,"listedAt":"2026-10-31T08:00:00Z"}
                """.formatted(OWNER)), JSON);

        assertThat(event.eventId()).isEqualTo(UUID.fromString(EVENT));
        assertThat(event.eventType()).isEqualTo("SharedPlanListed.v1");
        assertThat(event.traceId()).isEqualTo("trace-1");
        TripEvent.SharedPlanListed listed = event.sharedPlanListed();
        assertThat(listed.ownerId()).isEqualTo(UUID.fromString(OWNER));
        assertThat(listed.token()).isEqualTo("AbC_12-x");
        assertThat(listed.listedAt()).isEqualTo(Instant.parse("2026-10-31T08:00:00Z"));
    }

    @Test
    void aMissingListingTimeFallsBackToWhenTheEventOccurred() {
        TripEvent event = TripEvent.parse(envelope("""
                {"version":1,"ownerId":"%s","token":"tok"}""".formatted(OWNER)), JSON);

        assertThat(event.sharedPlanListed().listedAt()).isEqualTo(Instant.parse("2026-10-31T08:00:05Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "not json", "[]", "{}",
            "{\"eventId\":\"nope\",\"eventType\":\"SharedPlanListed.v1\",\"occurredAt\":\"2026-10-31T08:00:05Z\",\"payload\":{}}",
            "{\"eventId\":\"" + EVENT + "\",\"eventType\":\"SharedPlanListed.v1\",\"occurredAt\":\"yesterday\",\"payload\":{}}",
            "{\"eventId\":\"" + EVENT + "\",\"eventType\":\"SharedPlanListed.v1\",\"occurredAt\":\"2026-10-31T08:00:05Z\"}",
            "{\"eventId\":\"" + EVENT + "\",\"eventType\":\"SharedPlanListed.v1\",\"occurredAt\":\"2026-10-31T08:00:05Z\",\"payload\":\"x\"}"
    })
    void anEnvelopeThatDoesNotFitTheContractIsMalformed(String body) {
        assertThatThrownBy(() -> TripEvent.parse(body, JSON)).isInstanceOf(TripEvent.MalformedTripEventException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"version\":1,\"token\":\"tok\"}",
            "{\"version\":1,\"ownerId\":\"" + OWNER + "\"}",
            "{\"version\":1,\"ownerId\":\"" + OWNER + "\",\"token\":\"../admin\"}",
            "{\"version\":1,\"ownerId\":\"" + OWNER + "\",\"token\":\"tok\",\"listedAt\":\"soon\"}",
            "{\"ownerId\":\"" + OWNER + "\",\"token\":\"tok\"}",
            "{\"version\":2,\"ownerId\":\"" + OWNER + "\",\"token\":\"tok\"}"
    })
    void aListingPayloadThatDoesNotFitVersionOneIsMalformed(String payload) {
        TripEvent event = TripEvent.parse(envelope(payload), JSON);

        assertThatThrownBy(event::sharedPlanListed).isInstanceOf(TripEvent.MalformedTripEventException.class);
    }

    @Test
    void anOverlongTokenIsRejected() {
        TripEvent event = TripEvent.parse(envelope("""
                {"version":1,"ownerId":"%s","token":"%s"}""".formatted(OWNER, "a".repeat(65))), JSON);

        assertThatThrownBy(event::sharedPlanListed).isInstanceOf(TripEvent.MalformedTripEventException.class);
    }

    private static String envelope(String payload) {
        return """
                {"eventId":"%s","eventType":"SharedPlanListed.v1","occurredAt":"2026-10-31T08:00:05Z",
                 "producer":"trip-planning-service","partitionKey":"p","traceId":"trace-1","payload":%s}
                """.formatted(EVENT, payload);
    }
}
