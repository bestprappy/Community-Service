package com.navio.communityservice.repository;

import com.navio.communityservice.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class PostRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String FROM = " FROM social.posts p JOIN social.groups g ON g.id = p.group_id"
            + " LEFT JOIN social.group_profiles gp ON gp.group_id = g.id ";
    private static final String SELECT = """
            SELECT p.*, g.slug AS group_slug, g.name AS group_name, gp.icon_media_id AS group_icon_media_id,
              COALESCE((SELECT SUM(v.value) FROM social.post_votes v WHERE v.post_id=p.id),0) AS score,
              (SELECT COUNT(*) FROM social.comments c WHERE c.post_id=p.id AND NOT c.deleted) AS comment_count,
              COALESCE((SELECT v.value FROM social.post_votes v WHERE v.post_id=p.id AND v.user_id=:actor),0) AS viewer_vote,
              EXISTS(SELECT 1 FROM social.saved_posts sp WHERE sp.post_id=p.id AND sp.user_id=:actor) AS saved,
              (SELECT COUNT(*) FROM social.post_images pi WHERE pi.post_id=p.id) AS image_count
            """;
    /** "Best": votes plus half-weighted discussion, decayed by age in hours so new activity can surface. */
    private static final String HOT = """
            ((COALESCE((SELECT SUM(v.value) FROM social.post_votes v WHERE v.post_id=p.id),0)
              + (SELECT COUNT(*) FROM social.comments c WHERE c.post_id=p.id AND NOT c.deleted) * 0.5 + 1)
             / POWER(EXTRACT(EPOCH FROM (now() - p.created_at)) / 3600.0 + 2, 1.5))""";
    private static final RowMapper<PostResponse> MAPPER = (r, n) -> new PostResponse(
            r.getObject("id", UUID.class), r.getObject("group_id", UUID.class), r.getString("group_slug"),
            r.getString("group_name"), r.getObject("group_icon_media_id", UUID.class), r.getObject("author_id", UUID.class), r.getString("title"), r.getString("body"),
            r.getString("link_url"), r.getObject("flair_id", UUID.class), r.getString("shared_trip_id"),
            r.getString("image_key") == null ? null : "/v1/posts/" + r.getString("id") + "/image",
            r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant(),
            r.getInt("score"), r.getInt("comment_count"), r.getInt("viewer_vote"), r.getTimestamp("profile_pinned_at") != null, r.getBoolean("saved"),
            imageUrls(r.getString("id"), r.getInt("image_count"), r.getString("image_key") != null));

    /** A post written by an image from before galleries has a cover but no gallery rows. */
    private static List<String> imageUrls(String id, int galleryCount, boolean hasCover) {
        int count = Math.max(galleryCount, hasCover ? 1 : 0);
        var urls = new ArrayList<String>(count);
        for (int position = 0; position < count; position++) urls.add("/v1/posts/" + id + "/images/" + position);
        return urls;
    }

    /** Hides posts from accounts blocked in either direction with the viewer. */
    private static final String NOT_BLOCKED_AUTHOR = " AND NOT EXISTS (SELECT 1 FROM social.user_blocks ub"
            + " WHERE (ub.blocker_id = :actor AND ub.blocked_id = p.author_id) OR (ub.blocker_id = p.author_id AND ub.blocked_id = :actor))";

    public Optional<PostResponse> find(UUID id, UUID actor) {
        return jdbc.query(SELECT + FROM + " WHERE p.id=:id", params(actor).addValue("id", id), MAPPER).stream().findFirst();
    }

    public Page<PostResponse> list(UUID groupId, UUID actor, boolean joinedOnly, String query, String sort, Pageable page) {
        String where = groupId == null ? " WHERE g.status='active' AND NOT EXISTS (SELECT 1 FROM social.group_memberships m WHERE m.group_id=g.id AND m.user_id=:actor AND m.state='muted')"
                : " WHERE p.group_id=:groupId";
        if (groupId == null && joinedOnly) {
            where += " AND EXISTS (SELECT 1 FROM social.group_memberships m WHERE m.group_id=g.id AND m.user_id=:actor AND m.state='joined')";
        }
        where += NOT_BLOCKED_AUTHOR;
        // Literal, escaped substring search. User input never becomes SQL syntax or a LIKE wildcard.
        where += " AND LOWER(p.title || ' ' || p.body || ' ' || g.name) LIKE :query ESCAPE '!'";
        var parameters = params(actor).addValue("groupId", groupId)
                .addValue("query", "%" + query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%")
                .addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        String order = switch (sort) {
            case "new" -> "p.created_at DESC, p.id";
            case "best" -> HOT + " DESC, p.created_at DESC, p.id";
            default -> "score DESC, p.created_at DESC, p.id";
        };
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + FROM + where, parameters, Long.class);
        return new PageImpl<>(jdbc.query(SELECT + FROM + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", parameters, MAPPER), page, total == null ? 0 : total);
    }

    /**
     * One author's posts for their public profile: pinned first, then newest.
     * Posts in hidden communities are never listed.
     */
    public Page<PostResponse> listByAuthor(UUID author, UUID actor, Pageable page) {
        String where = " WHERE p.author_id = :author AND g.status <> 'hidden'";
        var parameters = params(actor).addValue("author", author)
                .addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + FROM + where, parameters, Long.class);
        return new PageImpl<>(jdbc.query(SELECT + FROM + where
                + " ORDER BY p.profile_pinned_at DESC NULLS LAST, p.created_at DESC, p.id LIMIT :limit OFFSET :offset",
                parameters, MAPPER), page, total == null ? 0 : total);
    }

    public int countPinned(UUID author) {
        Integer total = jdbc.queryForObject("""
                SELECT COUNT(*) FROM social.posts p JOIN social.groups g ON g.id = p.group_id
                WHERE p.author_id = :author AND p.profile_pinned_at IS NOT NULL AND g.status <> 'hidden'
                """, new MapSqlParameterSource("author", author), Integer.class);
        return total == null ? 0 : total;
    }

    public void setPinned(UUID id, boolean pinned) {
        jdbc.update("UPDATE social.posts SET profile_pinned_at = " + (pinned ? "COALESCE(profile_pinned_at, now())" : "NULL")
                + " WHERE id = :id", Map.of("id", id));
    }

    /** Inserts the post and its pictures in order; the first picture is also the legacy cover column. */
    public void create(UUID id, UUID groupId, UUID actor, CreatePostRequest r, List<ImageAsset> images) {
        var cover = images.isEmpty() ? null : images.getFirst();
        jdbc.update("""
                INSERT INTO social.posts(id,group_id,author_id,title,body,link_url,flair_id,shared_trip_id,image_key,image_content_type)
                VALUES(:id,:groupId,:actor,:title,:body,:link,:flair,:trip,:image,:type)
                """, params(actor).addValue("id", id).addValue("groupId", groupId).addValue("title", r.title().strip())
                .addValue("body", r.body().strip()).addValue("link", r.linkUrl()).addValue("flair", r.flairId())
                .addValue("trip", r.sharedTripId()).addValue("image", cover == null ? null : cover.key())
                .addValue("type", cover == null ? null : cover.contentType()));
        if (images.isEmpty()) return;
        var rows = new MapSqlParameterSource[images.size()];
        for (int position = 0; position < images.size(); position++) {
            rows[position] = new MapSqlParameterSource("post", id).addValue("position", position)
                    .addValue("key", images.get(position).key()).addValue("type", images.get(position).contentType());
        }
        jdbc.batchUpdate("INSERT INTO social.post_images(post_id,position,image_key,image_content_type) VALUES(:post,:position,:key,:type)", rows);
    }

    public void update(UUID id, UpdatePostRequest r) {
        jdbc.update("UPDATE social.posts SET title=:title,body=:body,updated_at=now() WHERE id=:id",
                new MapSqlParameterSource("id", id).addValue("title", r.title().strip()).addValue("body", r.body().strip()));
    }

    public void delete(UUID id) { jdbc.update("DELETE FROM social.posts WHERE id=:id", Map.of("id", id)); }

    public record ImageAsset(String key, String contentType) { }
    /** Every picture in gallery order, falling back to the cover column for posts without gallery rows. */
    public List<ImageAsset> images(UUID id) {
        var gallery = jdbc.query("SELECT image_key,image_content_type FROM social.post_images WHERE post_id=:id ORDER BY position",
                Map.of("id", id), (r, n) -> new ImageAsset(r.getString(1), r.getString(2)));
        if (!gallery.isEmpty()) return gallery;
        return jdbc.query("SELECT image_key,image_content_type FROM social.posts WHERE id=:id AND image_key IS NOT NULL",
                Map.of("id", id), (r, n) -> new ImageAsset(r.getString(1), r.getString(2)));
    }

    public Optional<ImageAsset> image(UUID id, int position) {
        var images = images(id);
        return position >= 0 && position < images.size() ? Optional.of(images.get(position)) : Optional.empty();
    }

    /** Storage keys to delete with the post: the gallery plus a cover an older image may have replaced. */
    public Set<String> imageKeys(UUID id) {
        var keys = new LinkedHashSet<String>();
        images(id).forEach(image -> keys.add(image.key()));
        jdbc.query("SELECT image_key FROM social.posts WHERE id=:id AND image_key IS NOT NULL", Map.of("id", id),
                (r, n) -> keys.add(r.getString(1)));
        return keys;
    }

    public void vote(UUID id, UUID actor, int value) {
        var parameters = params(actor).addValue("id", id).addValue("value", value);
        if (value == 0) jdbc.update("DELETE FROM social.post_votes WHERE post_id=:id AND user_id=:actor", parameters);
        else jdbc.update("INSERT INTO social.post_votes(post_id,user_id,value) VALUES(:id,:actor,:value) ON CONFLICT(post_id,user_id) DO UPDATE SET value=EXCLUDED.value", parameters);
    }

    /** Private collections reuse the feed projection and the same visibility rules as direct reads. */
    public Page<PostLibraryEntry> library(UUID actor, String view, String vote, String query, String sort, Pageable page) {
        String join, activity, extra="", commentId="NULL::uuid", commentBody="NULL::text";
        switch(view) {
            case "saved" -> { join=" JOIN social.saved_posts l ON l.post_id=p.id AND l.user_id=:actor "; activity="l.saved_at"; }
            case "history" -> { join=" JOIN social.post_visits l ON l.post_id=p.id AND l.user_id=:actor "; activity="l.opened_at"; extra=" AND l.opened_at > now()-interval '30 days'"; }
            case "comments" -> { join=" JOIN social.comments l ON l.post_id=p.id AND l.author_id=:actor AND NOT l.deleted "; activity="l.created_at"; commentId="l.id"; commentBody="l.body"; }
            default -> { join=" JOIN social.post_votes l ON l.post_id=p.id AND l.user_id=:actor "; activity="p.created_at"; extra=vote.equals("all")?"":" AND l.value="+(vote.equals("up")?"1":"-1"); }
        }
        String where=" WHERE (g.status<>'hidden' OR g.owner_user_id=:actor OR EXISTS(SELECT 1 FROM social.group_memberships m WHERE m.group_id=g.id AND m.user_id=:actor AND m.role IN ('admin','moderator') AND m.state IN ('joined','muted')))"
                +NOT_BLOCKED_AUTHOR+extra+" AND LOWER(p.title || ' ' || p.body || ' ' || g.name || ' ' || COALESCE("+commentBody+",'')) LIKE :query ESCAPE '!'";
        var parameters=params(actor).addValue("query","%"+query.toLowerCase(Locale.ROOT).replace("!","!!").replace("%","!%").replace("_","!_")+"%")
                .addValue("limit",page.getPageSize()).addValue("offset",page.getOffset());
        String order=sort.equals("top")?"score DESC, ":"";
        order+=activity+(sort.equals("oldest")?" ASC":" DESC")+", p.id, "+commentId;
        Long total=jdbc.queryForObject("SELECT COUNT(*)"+FROM+join+where,parameters,Long.class);
        String projection=SELECT.stripTrailing()+", "+activity+" AS activity_at, "+commentId+" AS library_comment_id, "+commentBody+" AS library_comment_body ";
        var rows=jdbc.query(projection+FROM+join+where+" ORDER BY "+order+" LIMIT :limit OFFSET :offset",parameters,
            (r,n)->new PostLibraryEntry(MAPPER.mapRow(r,n),r.getTimestamp("activity_at").toInstant(),r.getObject("library_comment_id",UUID.class),r.getString("library_comment_body")));
        return new PageImpl<>(rows,page,total==null?0:total);
    }
    public void savePost(UUID actor, UUID post) {
        jdbc.update("INSERT INTO social.saved_posts(user_id,post_id) VALUES(:actor,:post) ON CONFLICT DO NOTHING",params(actor).addValue("post",post));
    }
    public void unsavePost(UUID actor, UUID post) {
        jdbc.update("DELETE FROM social.saved_posts WHERE user_id=:actor AND post_id=:post",params(actor).addValue("post",post));
    }
    public void recordVisit(UUID actor, UUID post) {
        jdbc.update("INSERT INTO social.post_visits(user_id,post_id) VALUES(:actor,:post) ON CONFLICT(user_id,post_id) DO UPDATE SET opened_at=now()",params(actor).addValue("post",post));
        jdbc.update("DELETE FROM social.post_visits WHERE user_id=:actor AND opened_at<=now()-interval '30 days'",params(actor));
    }
    public void expireVisits() { jdbc.getJdbcTemplate().update("DELETE FROM social.post_visits WHERE opened_at<=now()-interval '30 days'"); }
    public void clearVisits(UUID actor) { jdbc.update("DELETE FROM social.post_visits WHERE user_id=:actor",params(actor)); }

    private MapSqlParameterSource params(UUID actor) { return new MapSqlParameterSource().addValue("actor", actor, java.sql.Types.OTHER); }
}
