package com.navio.communityservice.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/** Which integration events a consumer has finished, keyed by consumer and event id. */
@Repository
@RequiredArgsConstructor
public class ProcessedEventRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public boolean exists(String consumer, UUID eventId) {
        Boolean found = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM notif.processed_events WHERE consumer = :consumer AND event_id = :event)
                """, new MapSqlParameterSource("consumer", consumer).addValue("event", eventId), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    /**
     * Claims the event for this consumer inside the caller's transaction.
     *
     * @return false when another delivery already recorded it; the caller must then do nothing
     */
    public boolean record(String consumer, UUID eventId, String eventType, String outcome) {
        return jdbc.update("""
                INSERT INTO notif.processed_events(consumer, event_id, event_type, outcome)
                VALUES (:consumer, :event, :type, :outcome)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """, new MapSqlParameterSource("consumer", consumer).addValue("event", eventId)
                .addValue("type", eventType).addValue("outcome", outcome)) == 1;
    }
}
