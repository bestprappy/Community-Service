package com.navio.communityservice.repository;

import com.navio.communityservice.dto.*;
import com.navio.communityservice.support.CommentReaction;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import java.util.*;

@Repository
@RequiredArgsConstructor
public class CommentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String SELECT = """
            SELECT c.*,
              (NOT c.deleted AND c.updated_at > c.created_at) AS edited,
              COALESCE((SELECT SUM(v.value) FROM social.comment_votes v WHERE v.comment_id=c.id),0) AS score,
              COALESCE((SELECT v.value FROM social.comment_votes v WHERE v.comment_id=c.id AND v.user_id=:actor),0) AS viewer_vote
            FROM social.comments c
            """;
    private static final RowMapper<CommentResponse> MAPPER = (r, n) -> new CommentResponse(
            r.getObject("id", UUID.class), r.getObject("post_id", UUID.class), r.getObject("parent_comment_id", UUID.class),
            r.getObject("author_id", UUID.class), r.getString("body"), r.getBoolean("deleted"), r.getBoolean("edited"),
            r.getTimestamp("created_at").toInstant(), r.getInt("score"), r.getInt("viewer_vote"));

    public Optional<CommentResponse> find(UUID postId, UUID id, UUID actor) {
        return withReactions(jdbc.query(SELECT + " WHERE c.post_id=:post AND c.id=:id", params(postId, actor).addValue("id", id), MAPPER), actor)
                .stream().findFirst();
    }

    public Page<CommentResponse> list(UUID postId, UUID actor, Pageable page) {
        var parameters = params(postId, actor).addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM social.comments WHERE post_id=:post", parameters, Long.class);
        var rows = jdbc.query(SELECT + " WHERE c.post_id=:post ORDER BY c.created_at,c.id LIMIT :limit OFFSET :offset", parameters, MAPPER);
        return new PageImpl<>(withReactions(rows, actor), page, total == null ? 0 : total);
    }

    /** One grouped query per page; reactions come back in the fixed display order of {@link CommentReaction}. */
    private List<CommentResponse> withReactions(List<CommentResponse> rows, UUID actor) {
        if (rows.isEmpty()) return rows;
        Map<UUID, Map<String, ReactionSummary>> byComment = new HashMap<>();
        jdbc.query("""
                SELECT comment_id, reaction, COUNT(*) AS total, COALESCE(BOOL_OR(user_id=:actor), false) AS reacted
                FROM social.comment_reactions WHERE comment_id IN (:ids) GROUP BY comment_id, reaction
                """, new MapSqlParameterSource("ids", rows.stream().map(CommentResponse::id).toList())
                        .addValue("actor", actor, java.sql.Types.OTHER),
                (RowCallbackHandler) r -> byComment.computeIfAbsent(r.getObject("comment_id", UUID.class), id -> new HashMap<>())
                        .put(r.getString("reaction"), new ReactionSummary(r.getString("reaction"), r.getInt("total"), r.getBoolean("reacted"))));
        return rows.stream().map(row -> {
            var found = byComment.getOrDefault(row.id(), Map.of());
            return row.withReactions(Arrays.stream(CommentReaction.values()).map(reaction -> found.get(reaction.value()))
                    .filter(Objects::nonNull).toList());
        }).toList();
    }

    public void react(UUID id, UUID actor, CommentReaction reaction) {
        jdbc.update("INSERT INTO social.comment_reactions(comment_id,user_id,reaction) VALUES(:id,:actor,:reaction) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource("id", id).addValue("actor", actor).addValue("reaction", reaction.value()));
    }

    public void unreact(UUID id, UUID actor, CommentReaction reaction) {
        jdbc.update("DELETE FROM social.comment_reactions WHERE comment_id=:id AND user_id=:actor AND reaction=:reaction",
                new MapSqlParameterSource("id", id).addValue("actor", actor).addValue("reaction", reaction.value()));
    }

    public void create(UUID id, UUID postId, UUID actor, CreateCommentRequest r) {
        jdbc.update("INSERT INTO social.comments(id,post_id,author_id,parent_comment_id,body) VALUES(:id,:post,:actor,:parent,:body)",
                params(postId, actor).addValue("id", id).addValue("parent", r.parentCommentId()).addValue("body", r.body().strip()));
    }

    public void update(UUID id, String body) {
        jdbc.update("UPDATE social.comments SET body=:body,updated_at=now() WHERE id=:id AND NOT deleted",
                new MapSqlParameterSource("id", id).addValue("body", body.strip()));
    }

    public boolean hasReplies(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM social.comments WHERE parent_comment_id=:id)", Map.of("id", id), Boolean.class));
    }

    /** Keeps the row as a placeholder so its replies stay attached to the thread. */
    public void softDelete(UUID id) {
        jdbc.update("UPDATE social.comments SET body='',deleted=true,updated_at=now() WHERE id=:id", Map.of("id", id));
        jdbc.update("DELETE FROM social.comment_votes WHERE comment_id=:id", Map.of("id", id));
        jdbc.update("DELETE FROM social.comment_reactions WHERE comment_id=:id", Map.of("id", id));
    }

    /** Removes a comment without replies; its votes and reactions cascade. */
    public void remove(UUID id) {
        jdbc.update("DELETE FROM social.comments WHERE id=:id", Map.of("id", id));
    }

    public void vote(UUID id, UUID actor, int value) {
        var parameters = new MapSqlParameterSource("id", id).addValue("actor", actor).addValue("value", value);
        if (value == 0) jdbc.update("DELETE FROM social.comment_votes WHERE comment_id=:id AND user_id=:actor", parameters);
        else jdbc.update("INSERT INTO social.comment_votes(comment_id,user_id,value) VALUES(:id,:actor,:value) ON CONFLICT(comment_id,user_id) DO UPDATE SET value=EXCLUDED.value", parameters);
    }

    private MapSqlParameterSource params(UUID postId, UUID actor) {
        return new MapSqlParameterSource("post", postId).addValue("actor", actor, java.sql.Types.OTHER);
    }
}
