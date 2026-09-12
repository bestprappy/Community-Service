package com.navio.communityservice.service;

import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.repository.CommentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentService {
    private final PostService posts;
    private final CommentRepository comments;

    public Page<CommentResponse> list(UUID postId, UUID actor, int page, int size) {
        posts.read(postId, actor);
        return comments.list(postId, actor, PostService.pagination(page, size));
    }

    @Transactional
    public CommentResponse create(UUID postId, UUID actor, CreateCommentRequest r) {
        posts.writable(postId, actor);
        if (r.parentCommentId() != null && find(postId, r.parentCommentId(), actor).deleted()) {
            throw GroupException.invalid("Cannot reply to a deleted comment");
        }
        UUID id = UUID.randomUUID();
        comments.create(id, postId, actor, r);
        return find(postId, id, actor);
    }

    @Transactional
    public void delete(UUID postId, UUID id, UUID actor) {
        var post = posts.writable(postId, actor);
        var comment = find(postId, id, actor);
        posts.requireAuthorOrModerator(post.groupId(), comment.authorId(), actor);
        comments.delete(id);
    }

    @Transactional
    public CommentResponse vote(UUID postId, UUID id, UUID actor, int value) {
        posts.writable(postId, actor);
        if (find(postId, id, actor).deleted()) throw GroupException.invalid("Cannot vote on a deleted comment");
        comments.vote(id, actor, value);
        return find(postId, id, actor);
    }

    private CommentResponse find(UUID postId, UUID id, UUID actor) {
        return comments.find(postId, id, actor).orElseThrow(GroupException::notFound);
    }
}
