package com.navio.communityservice.repository;

import com.navio.communityservice.dto.SocialDtos.BlockedUserResponse;
import com.navio.communityservice.dto.SocialDtos.ConnectionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Follows, blocks and connection-list settings. All ids are Keycloak subjects. */
@Repository
@RequiredArgsConstructor
public class SocialGraphRepository {

    private static final String BLOCKED_EITHER_WAY = """
            EXISTS (SELECT 1 FROM social.user_blocks b
                    WHERE (b.blocker_id = :a AND b.blocked_id = :b) OR (b.blocker_id = :b AND b.blocked_id = :a))""";

    private final NamedParameterJdbcTemplate jdbc;

    public void lockPair(UUID a, UUID b) {
        String key = a.compareTo(b) < 0 ? a + ":" + b : b + ":" + a;
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(:key,0))", new MapSqlParameterSource("key", key), row -> { });
    }

    public record Follow(boolean alerts) { }

    /** @return true when a new follow row was created */
    public boolean follow(UUID follower, UUID followee) {
        return jdbc.update("""
                INSERT INTO social.user_follows(follower_id, followee_id) VALUES (:follower, :followee)
                ON CONFLICT (follower_id, followee_id) DO NOTHING
                """, pair(follower, followee)) == 1;
    }

    public boolean unfollow(UUID follower, UUID followee) {
        return jdbc.update("DELETE FROM social.user_follows WHERE follower_id = :follower AND followee_id = :followee",
                pair(follower, followee)) == 1;
    }

    public boolean setNotify(UUID follower, UUID followee, boolean notify) {
        return jdbc.update("""
                UPDATE social.user_follows SET notify = :notify WHERE follower_id = :follower AND followee_id = :followee
                """, pair(follower, followee).addValue("notify", notify)) == 1;
    }

    public Optional<Follow> findFollow(UUID follower, UUID followee) {
        return jdbc.query("SELECT notify FROM social.user_follows WHERE follower_id = :follower AND followee_id = :followee",
                pair(follower, followee), (row, index) -> new Follow(row.getBoolean(1))).stream().findFirst();
    }

    public long followerCount(UUID user) {
        return count("SELECT COUNT(*) FROM social.user_follows WHERE followee_id = :user", user);
    }

    public long followingCount(UUID user) {
        return count("SELECT COUNT(*) FROM social.user_follows WHERE follower_id = :user", user);
    }

    /** People who follow {@code user}, newest first, optionally narrowed to {@code within}. */
    public Page<ConnectionResponse> followers(UUID user, UUID viewer, Collection<UUID> within, Pageable page) {
        return connections("follower_id", "followee_id", user, viewer, within, page);
    }

    /** People {@code user} follows, newest first, optionally narrowed to {@code within}. */
    public Page<ConnectionResponse> following(UUID user, UUID viewer, Collection<UUID> within, Pageable page) {
        return connections("followee_id", "follower_id", user, viewer, within, page);
    }

    /**
     * Both column names are fixed literals chosen above, never caller input.
     * Accounts blocked either way with the viewer are left out of the list.
     */
    private Page<ConnectionResponse> connections(String otherColumn, String userColumn, UUID user, UUID viewer,
                                                 Collection<UUID> within, Pageable page) {
        String where = " FROM social.user_follows f WHERE f." + userColumn + " = :user"
                + " AND NOT EXISTS (SELECT 1 FROM social.user_blocks b WHERE (b.blocker_id = :viewer AND b.blocked_id = f." + otherColumn + ")"
                + " OR (b.blocked_id = :viewer AND b.blocker_id = f." + otherColumn + "))"
                + (within == null ? "" : " AND f." + otherColumn + " IN (:within)");
        var parameters = new MapSqlParameterSource("user", user)
                .addValue("viewer", viewer, Types.OTHER)
                .addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        if (within != null) {
            if (within.isEmpty()) return Page.empty(page);
            parameters.addValue("within", within);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, parameters, Long.class);
        List<ConnectionResponse> rows = jdbc.query("SELECT f." + otherColumn + " AS account_id, f.created_at,"
                        + " EXISTS (SELECT 1 FROM social.user_follows v WHERE v.follower_id = :viewer AND v.followee_id = f." + otherColumn + ") AS viewer_follows,"
                        + " COALESCE((SELECT v.notify FROM social.user_follows v WHERE v.follower_id = :viewer AND v.followee_id = f." + otherColumn + "), FALSE) AS viewer_alerts"
                        + where + " ORDER BY f.created_at DESC, f." + otherColumn + " LIMIT :limit OFFSET :offset",
                parameters, (row, index) -> new ConnectionResponse(row.getObject("account_id", UUID.class),
                        row.getTimestamp("created_at").toInstant(), row.getBoolean("viewer_follows"),
                        row.getBoolean("viewer_alerts")));
        return new PageImpl<>(rows, page, total == null ? 0 : total);
    }

    /** @return true when a new block row was created */
    public boolean block(UUID blocker, UUID blocked) {
        return jdbc.update("""
                INSERT INTO social.user_blocks(blocker_id, blocked_id) VALUES (:a, :b)
                ON CONFLICT (blocker_id, blocked_id) DO NOTHING
                """, new MapSqlParameterSource("a", blocker).addValue("b", blocked)) == 1;
    }

    public boolean unblock(UUID blocker, UUID blocked) {
        return jdbc.update("DELETE FROM social.user_blocks WHERE blocker_id = :a AND blocked_id = :b",
                new MapSqlParameterSource("a", blocker).addValue("b", blocked)) == 1;
    }

    /** Removes follows in both directions, as a block requires. */
    public void removeFollowsBetween(UUID a, UUID b) {
        jdbc.update("""
                DELETE FROM social.user_follows
                WHERE (follower_id = :a AND followee_id = :b) OR (follower_id = :b AND followee_id = :a)
                """, new MapSqlParameterSource("a", a).addValue("b", b));
    }

    public boolean hasBlocked(UUID blocker, UUID blocked) {
        Boolean exists = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM social.user_blocks WHERE blocker_id = :a AND blocked_id = :b)",
                new MapSqlParameterSource("a", blocker).addValue("b", blocked), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public boolean blockedEitherWay(UUID a, UUID b) {
        if (a == null || b == null) return false;
        Boolean exists = jdbc.queryForObject("SELECT " + BLOCKED_EITHER_WAY,
                new MapSqlParameterSource("a", a).addValue("b", b), Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    public Page<BlockedUserResponse> blocked(UUID blocker, Pageable page) {
        var parameters = new MapSqlParameterSource("user", blocker)
                .addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM social.user_blocks WHERE blocker_id = :user", parameters, Long.class);
        var rows = jdbc.query("""
                SELECT blocked_id, created_at FROM social.user_blocks WHERE blocker_id = :user
                ORDER BY created_at DESC, blocked_id LIMIT :limit OFFSET :offset
                """, parameters, (row, index) -> new BlockedUserResponse(row.getObject(1, UUID.class),
                row.getTimestamp(2).toInstant()));
        return new PageImpl<>(rows, page, total == null ? 0 : total);
    }

    public String connectionsVisibility(UUID user) {
        return jdbc.query("SELECT connections_visibility FROM social.social_settings WHERE user_id = :user",
                        new MapSqlParameterSource("user", user), (row, index) -> row.getString(1))
                .stream().findFirst().orElse("everyone");
    }

    public void saveConnectionsVisibility(UUID user, String visibility) {
        jdbc.update("""
                INSERT INTO social.social_settings(user_id, connections_visibility) VALUES (:user, :visibility)
                ON CONFLICT (user_id) DO UPDATE SET connections_visibility = EXCLUDED.connections_visibility, updated_at = now()
                """, new MapSqlParameterSource("user", user).addValue("visibility", visibility));
    }

    private long count(String sql, UUID user) {
        Long value = jdbc.queryForObject(sql, new MapSqlParameterSource("user", user), Long.class);
        return value == null ? 0 : value;
    }

    private static MapSqlParameterSource pair(UUID follower, UUID followee) {
        return new MapSqlParameterSource("follower", follower).addValue("followee", followee);
    }
}
