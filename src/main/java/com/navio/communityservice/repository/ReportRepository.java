package com.navio.communityservice.repository;

import com.navio.communityservice.dto.SocialDtos.ReportResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.Optional;
import java.util.UUID;

/** The moderation queue for reported profiles, posts and comments. */
@Repository
@RequiredArgsConstructor
public class ReportRepository {

    /** A short, current preview of the reported content; null once it is gone. */
    private static final String SELECT = """
            SELECT r.*,
              CASE r.target_type
                WHEN 'post' THEN (SELECT p.title FROM social.posts p WHERE p.id = r.target_id)
                WHEN 'comment' THEN (SELECT LEFT(c.body, 280) FROM social.comments c WHERE c.id = r.target_id AND NOT c.deleted)
              END AS target_preview,
              CASE r.target_type
                WHEN 'post' THEN EXISTS (SELECT 1 FROM social.posts p WHERE p.id = r.target_id)
                WHEN 'comment' THEN EXISTS (SELECT 1 FROM social.comments c WHERE c.id = r.target_id AND NOT c.deleted)
                ELSE TRUE
              END AS target_available
            FROM social.reports r
            """;

    private static final RowMapper<ReportResponse> MAPPER = (row, index) -> new ReportResponse(
            row.getObject("id", UUID.class), row.getString("target_type"), row.getObject("target_id", UUID.class),
            row.getObject("target_user_id", UUID.class), row.getString("reason"), row.getString("details"),
            row.getString("status"), row.getObject("reporter_id", UUID.class), row.getString("resolution_note"),
            row.getObject("resolved_by", UUID.class),
            row.getTimestamp("resolved_at") == null ? null : row.getTimestamp("resolved_at").toInstant(),
            row.getTimestamp("created_at").toInstant(), row.getString("target_preview"), row.getBoolean("target_available"));

    private final NamedParameterJdbcTemplate jdbc;

    /** @return false when this reporter already has an open report on the target */
    public boolean create(UUID id, UUID reporter, String targetType, UUID targetId, UUID targetUser, String reason, String details) {
        return jdbc.update("""
                INSERT INTO social.reports(id, reporter_id, target_type, target_id, target_user_id, reason, details)
                VALUES (:id, :reporter, :type, :target, :targetUser, :reason, :details)
                ON CONFLICT (reporter_id, target_type, target_id) WHERE status = 'open' DO NOTHING
                """, new MapSqlParameterSource("id", id).addValue("reporter", reporter).addValue("type", targetType)
                .addValue("target", targetId).addValue("targetUser", targetUser, Types.OTHER)
                .addValue("reason", reason).addValue("details", details)) == 1;
    }

    public long countLastDay(UUID reporter) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM social.reports WHERE reporter_id = :reporter AND created_at > now() - INTERVAL '1 day'",
                new MapSqlParameterSource("reporter", reporter), Long.class);
        return total == null ? 0 : total;
    }

    public Optional<ReportResponse> find(UUID id) {
        return jdbc.query(SELECT + " WHERE r.id = :id", new MapSqlParameterSource("id", id), MAPPER).stream().findFirst();
    }

    public Page<ReportResponse> queue(String status, Pageable page) {
        var parameters = new MapSqlParameterSource("status", status)
                .addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM social.reports WHERE status = :status", parameters, Long.class);
        String order = "open".equals(status) ? "r.created_at ASC" : "r.resolved_at DESC";
        var rows = jdbc.query(SELECT + " WHERE r.status = :status ORDER BY " + order + ", r.id LIMIT :limit OFFSET :offset",
                parameters, MAPPER);
        return new PageImpl<>(rows, page, total == null ? 0 : total);
    }

    /** @return false when the report was already resolved by someone else */
    public boolean resolve(UUID id, String status, String note, UUID moderator) {
        return jdbc.update("""
                UPDATE social.reports SET status = :status, resolution_note = :note, resolved_by = :moderator, resolved_at = now()
                WHERE id = :id AND status = 'open'
                """, new MapSqlParameterSource("id", id).addValue("status", status).addValue("note", note)
                .addValue("moderator", moderator)) == 1;
    }

    /** Resolves every other open report on the same content once it has been removed. */
    public void resolveOthersForTarget(String targetType, UUID targetId, String note, UUID moderator) {
        jdbc.update("""
                UPDATE social.reports SET status = 'actioned', resolution_note = :note, resolved_by = :moderator, resolved_at = now()
                WHERE target_type = :type AND target_id = :target AND status = 'open'
                """, new MapSqlParameterSource("type", targetType).addValue("target", targetId)
                .addValue("note", note).addValue("moderator", moderator));
    }
}
