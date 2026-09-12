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
public class CommentRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String SELECT = """
            SELECT c.*,
              COALESCE((SELECT SUM(v.value) FROM social.comment_votes v WHERE v.comment_id=c.id),0) AS score,
              COALESCE((SELECT v.value FROM social.comment_votes v WHERE v.comment_id=c.id AND v.user_id=:actor),0) AS viewer_vote
            FROM social.comments c
            """;
    private static final RowMapper<CommentResponse> MAPPER = (r, n) -> new CommentResponse(
            r.getObject("id", UUID.class), r.getObject("post_id", UUID.class), r.getObject("parent_comment_id", UUID.class),
            r.getObject("author_id", UUID.class), r.getString("body"), r.getBoolean("deleted"),
            r.getTimestamp("created_at").toInstant(), r.getInt("score"), r.getInt("viewer_vote"));

    public Optional<CommentResponse> find(UUID postId, UUID id, UUID actor) {
        return jdbc.query(SELECT + " WHERE c.post_id=:post AND c.id=:id", params(postId, actor).addValue("id", id), MAPPER).stream().findFirst();
    }

    public Page<CommentResponse> list(UUID postId, UUID actor, Pageable page) {
        var parameters = params(postId, actor).addValue("limit", page.getPageSize()).addValue("offset", page.getOffset());
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM social.comments WHERE post_id=:post", parameters, Long.class);
        return new PageImpl<>(jdbc.query(SELECT + " WHERE c.post_id=:post ORDER BY c.created_at,c.id LIMIT :limit OFFSET :offset", parameters, MAPPER), page, total == null ? 0 : total);
    }

    public void create(UUID id, UUID postId, UUID actor, CreateCommentRequest r) {
        jdbc.update("INSERT INTO social.comments(id,post_id,author_id,parent_comment_id,body) VALUES(:id,:post,:actor,:parent,:body)",
                params(postId, actor).addValue("id", id).addValue("parent", r.parentCommentId()).addValue("body", r.body().strip()));
    }

    public void delete(UUID id) {
        jdbc.update("UPDATE social.comments SET body='',deleted=true,updated_at=now() WHERE id=:id", Map.of("id", id));
        jdbc.update("DELETE FROM social.comment_votes WHERE comment_id=:id", Map.of("id", id));
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
