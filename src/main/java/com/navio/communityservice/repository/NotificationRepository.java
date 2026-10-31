package com.navio.communityservice.repository;

import com.navio.communityservice.dto.SocialDtos.NotificationPreferences;
import com.navio.communityservice.dto.SocialDtos.NotificationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * In-app notifications and per-type preferences.
 *
 * <p>Every fan-out is a single {@code INSERT ... SELECT} guarded by the
 * recipient's preferences, the follow's own alert switch, blocks in either
 * direction, a per-sender burst cap and the {@code (recipient, dedupe_key)}
 * unique key, so retries and repeated follow/unfollow clicks cannot multiply rows.
 */
@Repository
@RequiredArgsConstructor
public class NotificationRepository {

    /** At most this many post/plan notifications from one person to one recipient per hour. */
    public static final int CONTENT_BURST_PER_HOUR = 3;

    private static final String NOT_BLOCKED = """
            NOT EXISTS (SELECT 1 FROM social.user_blocks b
                        WHERE (b.blocker_id = %1$s AND b.blocked_id = %2$s) OR (b.blocker_id = %2$s AND b.blocked_id = %1$s))""";

    /**
     * Visible notifications for a recipient: never from a blocked account, and a
     * post notification only while its post exists (cascade) and its community is not hidden.
     */
    private static final String VISIBLE = """
             FROM notif.notifications n
             LEFT JOIN social.posts p ON p.id = n.post_id
             LEFT JOIN social.groups g ON g.id = p.group_id
             WHERE n.recipient_id = :recipient
               AND (n.type <> 'followed_post' OR (p.id IS NOT NULL AND g.status <> 'hidden'))
            """ + " AND " + NOT_BLOCKED.formatted("n.recipient_id", "n.actor_id") + " ";

    private static final RowMapper<NotificationResponse> MAPPER = (row, index) -> new NotificationResponse(
            row.getObject("id", UUID.class), row.getString("type"), row.getObject("actor_id", UUID.class),
            row.getTimestamp("created_at").toInstant(), row.getTimestamp("read_at") != null,
            row.getObject("post_id", UUID.class), row.getString("post_title"), row.getString("group_slug"),
            row.getString("plan_token"), row.getString("plan_title"), true);

    private final NamedParameterJdbcTemplate jdbc;

    /** @return true when the followee will see a new-follower notification */
    public boolean notifyNewFollower(UUID follower, UUID followee) {
        return jdbc.update("""
                INSERT INTO notif.notifications(id, recipient_id, type, actor_id, dedupe_key)
                SELECT gen_random_uuid(), :followee, 'new_follower', :follower, 'follow:' || CAST(:follower AS TEXT)
                WHERE COALESCE((SELECT p.new_follower FROM notif.preferences p WHERE p.user_id = :followee), TRUE)
                """ + " AND " + NOT_BLOCKED.formatted(":follower", ":followee")
                + " ON CONFLICT (recipient_id, dedupe_key) DO NOTHING", new MapSqlParameterSource("follower", follower).addValue("followee", followee)) == 1;
    }

    /** @return how many followers were notified */
    public int notifyFollowersOfPost(UUID author, UUID postId) {
        return jdbc.update("""
                INSERT INTO notif.notifications(id, recipient_id, type, actor_id, post_id, dedupe_key)
                SELECT gen_random_uuid(), f.follower_id, 'followed_post', :author, :post, 'post:' || CAST(:post AS TEXT)
                """ + contentAudience("followed_post"),
                new MapSqlParameterSource("author", author).addValue("post", postId)
                        .addValue("burst", CONTENT_BURST_PER_HOUR));
    }

    /** @return how many followers were notified */
    public int notifyFollowersOfPlan(UUID author, String token, String title) {
        return jdbc.update("""
                INSERT INTO notif.notifications(id, recipient_id, type, actor_id, plan_token, plan_title, dedupe_key)
                SELECT gen_random_uuid(), f.follower_id, 'followed_plan', :author, :token, :title, 'plan:' || :token
                """ + contentAudience("followed_plan"),
                new MapSqlParameterSource("author", author).addValue("token", token).addValue("title", title)
                        .addValue("burst", CONTENT_BURST_PER_HOUR));
    }

    /** {@code preference} is one of two fixed column names, never caller input. */
    private static String contentAudience(String preference) {
        return """
                FROM social.user_follows f
                WHERE f.followee_id = :author AND f.notify
                  AND COALESCE((SELECT p.%s FROM notif.preferences p WHERE p.user_id = f.follower_id), TRUE)
                  AND %s
                  AND (SELECT COUNT(*) FROM notif.notifications r
                       WHERE r.recipient_id = f.follower_id AND r.actor_id = :author
                         AND r.type IN ('followed_post', 'followed_plan')
                         AND r.created_at > now() - INTERVAL '1 hour') < :burst
                ON CONFLICT (recipient_id, dedupe_key) DO NOTHING
                """.formatted(preference, NOT_BLOCKED.formatted("f.follower_id", ":author"));
    }

    public Page<NotificationResponse> list(UUID recipient, boolean unreadOnly, Pageable page) {
        String where = VISIBLE + (unreadOnly ? " AND n.read_at IS NULL" : "");
        var parameters = new MapSqlParameterSource("recipient", recipient)
                .addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, parameters, Long.class);
        var rows = jdbc.query("SELECT n.*, p.title AS post_title, g.slug AS group_slug" + where
                + " ORDER BY n.created_at DESC, n.id LIMIT :limit OFFSET :offset", parameters, MAPPER);
        return new PageImpl<>(rows, page, total == null ? 0 : total);
    }

    public long unreadCount(UUID recipient) {
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + VISIBLE + " AND n.read_at IS NULL",
                new MapSqlParameterSource("recipient", recipient), Long.class);
        return total == null ? 0 : total;
    }

    /** @return false when the notification does not exist for this recipient */
    public boolean markRead(UUID recipient, UUID id) {
        return jdbc.update("""
                UPDATE notif.notifications SET read_at = COALESCE(read_at, now())
                WHERE id = :id AND recipient_id = :recipient
                """, new MapSqlParameterSource("recipient", recipient).addValue("id", id)) == 1;
    }

    public int markAllRead(UUID recipient) {
        return jdbc.update("UPDATE notif.notifications SET read_at = now() WHERE recipient_id = :recipient AND read_at IS NULL",
                new MapSqlParameterSource("recipient", recipient));
    }

    /** A block erases what the two accounts were notified about each other. */
    public void deleteBetween(UUID a, UUID b) {
        jdbc.update("""
                DELETE FROM notif.notifications
                WHERE (recipient_id = :a AND actor_id = :b) OR (recipient_id = :b AND actor_id = :a)
                """, new MapSqlParameterSource("a", a).addValue("b", b));
    }

    public NotificationPreferences preferences(UUID user) {
        return jdbc.query("SELECT new_follower, followed_post, followed_plan FROM notif.preferences WHERE user_id = :user",
                        new MapSqlParameterSource("user", user),
                        (row, index) -> new NotificationPreferences(row.getBoolean(1), row.getBoolean(2), row.getBoolean(3)))
                .stream().findFirst().orElse(NotificationPreferences.defaults());
    }

    public void savePreferences(UUID user, NotificationPreferences value) {
        jdbc.update("""
                INSERT INTO notif.preferences(user_id, new_follower, followed_post, followed_plan)
                VALUES (:user, :follower, :post, :plan)
                ON CONFLICT (user_id) DO UPDATE SET new_follower = EXCLUDED.new_follower,
                    followed_post = EXCLUDED.followed_post, followed_plan = EXCLUDED.followed_plan, updated_at = now()
                """, new MapSqlParameterSource("user", user).addValue("follower", value.newFollower())
                .addValue("post", value.followedPost()).addValue("plan", value.followedPlan()));
    }
}
