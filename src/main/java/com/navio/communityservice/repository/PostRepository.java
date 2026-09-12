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
    private static final String FROM = " FROM social.posts p JOIN social.groups g ON g.id = p.group_id ";
    private static final String SELECT = """
            SELECT p.*, g.slug AS group_slug, g.name AS group_name,
              COALESCE((SELECT SUM(v.value) FROM social.post_votes v WHERE v.post_id=p.id),0) AS score,
              (SELECT COUNT(*) FROM social.comments c WHERE c.post_id=p.id AND NOT c.deleted) AS comment_count,
              COALESCE((SELECT v.value FROM social.post_votes v WHERE v.post_id=p.id AND v.user_id=:actor),0) AS viewer_vote
            """;
    private static final RowMapper<PostResponse> MAPPER = (r, n) -> new PostResponse(
            r.getObject("id", UUID.class), r.getObject("group_id", UUID.class), r.getString("group_slug"),
            r.getString("group_name"), r.getObject("author_id", UUID.class), r.getString("title"), r.getString("body"),
            r.getString("link_url"), r.getObject("flair_id", UUID.class), r.getString("shared_trip_id"),
            r.getString("image_key") == null ? null : "/v1/posts/" + r.getString("id") + "/image",
            r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant(),
            r.getInt("score"), r.getInt("comment_count"), r.getInt("viewer_vote"));

    public Optional<PostResponse> find(UUID id, UUID actor) {
        return jdbc.query(SELECT + FROM + " WHERE p.id=:id", params(actor).addValue("id", id), MAPPER).stream().findFirst();
    }

    public Page<PostResponse> list(UUID groupId, UUID actor, String query, String sort, Pageable page) {
        String where = groupId == null ? " WHERE g.status='active' AND NOT EXISTS (SELECT 1 FROM social.group_memberships m WHERE m.group_id=g.id AND m.user_id=:actor AND m.state='muted')"
                : " WHERE p.group_id=:groupId";
        // Literal, escaped substring search. User input never becomes SQL syntax or a LIKE wildcard.
        where += " AND LOWER(p.title || ' ' || p.body || ' ' || g.name) LIKE :query ESCAPE '!'";
        var parameters = params(actor).addValue("groupId", groupId)
                .addValue("query", "%" + query.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%")
                .addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        String order = "new".equals(sort) ? "p.created_at DESC, p.id" : "score DESC, p.created_at DESC, p.id";
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + FROM + where, parameters, Long.class);
        return new PageImpl<>(jdbc.query(SELECT + FROM + where + " ORDER BY " + order + " LIMIT :limit OFFSET :offset", parameters, MAPPER), page, total == null ? 0 : total);
    }

    public void create(UUID id, UUID groupId, UUID actor, CreatePostRequest r, String imageKey, String imageType) {
        jdbc.update("""
                INSERT INTO social.posts(id,group_id,author_id,title,body,link_url,flair_id,shared_trip_id,image_key,image_content_type)
                VALUES(:id,:groupId,:actor,:title,:body,:link,:flair,:trip,:image,:type)
                """, params(actor).addValue("id", id).addValue("groupId", groupId).addValue("title", r.title().strip())
                .addValue("body", r.body().strip()).addValue("link", r.linkUrl()).addValue("flair", r.flairId())
                .addValue("trip", r.sharedTripId()).addValue("image", imageKey).addValue("type", imageType));
    }

    public void update(UUID id, UpdatePostRequest r) {
        jdbc.update("UPDATE social.posts SET title=:title,body=:body,updated_at=now() WHERE id=:id",
                new MapSqlParameterSource("id", id).addValue("title", r.title().strip()).addValue("body", r.body().strip()));
    }

    public void delete(UUID id) { jdbc.update("DELETE FROM social.posts WHERE id=:id", Map.of("id", id)); }

    public record ImageAsset(String key, String contentType) { }
    public Optional<ImageAsset> image(UUID id) {
        return jdbc.query("SELECT image_key,image_content_type FROM social.posts WHERE id=:id AND image_key IS NOT NULL",
                Map.of("id", id), (r, n) -> new ImageAsset(r.getString(1), r.getString(2))).stream().findFirst();
    }

    public void vote(UUID id, UUID actor, int value) {
        var parameters = params(actor).addValue("id", id).addValue("value", value);
        if (value == 0) jdbc.update("DELETE FROM social.post_votes WHERE post_id=:id AND user_id=:actor", parameters);
        else jdbc.update("INSERT INTO social.post_votes(post_id,user_id,value) VALUES(:id,:actor,:value) ON CONFLICT(post_id,user_id) DO UPDATE SET value=EXCLUDED.value", parameters);
    }

    private MapSqlParameterSource params(UUID actor) { return new MapSqlParameterSource().addValue("actor", actor, java.sql.Types.OTHER); }
}
