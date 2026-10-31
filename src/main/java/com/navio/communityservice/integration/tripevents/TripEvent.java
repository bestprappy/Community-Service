package com.navio.communityservice.integration.tripevents;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * One {@code trip.events.v1} envelope, validated.
 *
 * <p>Wire format (docs/summary/Navio Architecture.md section 8.3):
 * {@code {eventId, eventType, occurredAt, producer, partitionKey, traceId, payload}}.
 * Anything that does not fit is rejected as malformed and quarantined, never guessed at.
 */
public record TripEvent(UUID eventId, String eventType, Instant occurredAt, String traceId, JsonNode payload) {

    public static final String SHARED_PLAN_LISTED = "SharedPlanListed.v1";

    /** The record can never be processed as sent; retrying will not help. */
    public static class MalformedTripEventException extends RuntimeException {
        public MalformedTripEventException(String message) {
            super(message);
        }
    }

    /** The facts a SharedPlanListed.v1 event carries. Identifiers only. */
    public record SharedPlanListed(UUID ownerId, String token, Instant listedAt) { }

    public static TripEvent parse(String body, ObjectMapper json) {
        if (body == null || body.isBlank()) throw new MalformedTripEventException("Empty record");
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (JacksonException exception) {
            throw new MalformedTripEventException("Record is not JSON");
        }
        if (root == null || !root.isObject()) throw new MalformedTripEventException("Record is not a JSON object");
        UUID eventId = uuid(root, "eventId");
        String eventType = text(root, "eventType", 100);
        Instant occurredAt = instant(root, "occurredAt");
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) throw new MalformedTripEventException("payload must be an object");
        JsonNode trace = root.get("traceId");
        return new TripEvent(eventId, eventType, occurredAt, trace != null && trace.isTextual() ? trace.asText() : null, payload);
    }

    public SharedPlanListed sharedPlanListed() {
        if (!SHARED_PLAN_LISTED.equals(eventType)) throw new MalformedTripEventException("Not a " + SHARED_PLAN_LISTED);
        JsonNode version = payload.get("version");
        if (version == null || !version.canConvertToInt() || version.asInt() != 1) {
            throw new MalformedTripEventException("Unsupported SharedPlanListed payload version");
        }
        String token = text(payload, "token", 64);
        if (!token.matches("[A-Za-z0-9_-]+")) throw new MalformedTripEventException("token has unexpected characters");
        JsonNode listedAt = payload.get("listedAt");
        return new SharedPlanListed(uuid(payload, "ownerId"), token,
                listedAt == null || listedAt.isNull() ? occurredAt : instant(payload, "listedAt"));
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new MalformedTripEventException(field + " is required");
        }
        if (value.asText().length() > max) throw new MalformedTripEventException(field + " is too long");
        return value.asText();
    }

    private static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field, 36));
        } catch (IllegalArgumentException exception) {
            throw new MalformedTripEventException(field + " is not a UUID");
        }
    }

    private static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field, 40));
        } catch (DateTimeParseException exception) {
            throw new MalformedTripEventException(field + " is not an ISO-8601 instant");
        }
    }
}
