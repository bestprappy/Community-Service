package com.navio.communityservice.repository;

import com.navio.communityservice.dto.NotificationEventDtos.QuarantinedEventResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

/** Integration events that failed after retries, kept for an operator to inspect and replay. */
@Repository
@RequiredArgsConstructor
public class QuarantinedEventRepository {

    private static final RowMapper<QuarantinedEventResponse> MAPPER = (row, index) -> new QuarantinedEventResponse(
            row.getObject("id", UUID.class), row.getString("topic"), row.getInt("record_partition"),
            row.getLong("record_offset"), row.getObject("event_id", UUID.class), row.getString("event_type"),
            row.getString("payload"), row.getString("error"), row.getTimestamp("quarantined_at").toInstant(),
            toInstant(row.getTimestamp("replayed_at")), row.getString("replay_outcome"));

    private final NamedParameterJdbcTemplate jdbc;

    /** Idempotent per record position: re-quarantining the same record refreshes its diagnosis. */
    public void quarantine(String topic, int partition, long offset, String key, UUID eventId, String eventType,
                           String payload, String error) {
        jdbc.update("""
                INSERT INTO notif.quarantined_events(id, topic, record_partition, record_offset, record_key,
                                                     event_id, event_type, payload, error)
                VALUES (gen_random_uuid(), :topic, :partition, :offset, :key, :event, :type, :payload, :error)
                ON CONFLICT (topic, record_partition, record_offset)
                DO UPDATE SET error = EXCLUDED.error, quarantined_at = now(), replayed_at = NULL, replay_outcome = NULL
                """, new MapSqlParameterSource("topic", topic).addValue("partition", partition)
                .addValue("offset", offset).addValue("key", truncate(key, 200)).addValue("event", eventId)
                .addValue("type", truncate(eventType, 100)).addValue("payload", payload == null ? "" : payload)
                .addValue("error", truncate(error, 2000)));
    }

    public Page<QuarantinedEventResponse> list(boolean openOnly, Pageable page) {
        String where = openOnly ? " WHERE replayed_at IS NULL" : "";
        var parameters = new MapSqlParameterSource("limit", page.getPageSize()).addValue("offset", page.getOffset());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM notif.quarantined_events" + where, parameters, Long.class);
        var rows = jdbc.query("SELECT * FROM notif.quarantined_events" + where
                + " ORDER BY quarantined_at DESC, id LIMIT :limit OFFSET :offset", parameters, MAPPER);
        return new PageImpl<>(rows, page, total == null ? 0 : total);
    }

    public Optional<QuarantinedEventResponse> find(UUID id) {
        return jdbc.query("SELECT * FROM notif.quarantined_events WHERE id = :id",
                new MapSqlParameterSource("id", id), MAPPER).stream().findFirst();
    }

    public long openCount() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM notif.quarantined_events WHERE replayed_at IS NULL",
                new MapSqlParameterSource(), Long.class);
        return total == null ? 0 : total;
    }

    public void markReplayed(UUID id, String outcome) {
        jdbc.update("UPDATE notif.quarantined_events SET replayed_at = now(), replay_outcome = :outcome WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("outcome", outcome));
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
