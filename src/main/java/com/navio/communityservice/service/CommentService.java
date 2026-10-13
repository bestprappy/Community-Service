package com.navio.communityservice.service;

import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.repository.CommentRepository;
import com.navio.communityservice.support.CommentReaction;
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
    public CommentResponse update(UUID postId, UUID id, UUID actor, UpdateCommentRequest r) {
        posts.writable(postId, actor);
        var comment = find(postId, id, actor);
        if (comment.deleted()) throw GroupException.invalid("Cannot edit a deleted comment");
        if (!comment.authorId().equals(actor)) throw GroupException.forbidden("Only the author can edit this comment");
        comments.update(id, r.body());
        return find(postId, id, actor);
    }

    /**
     * A comment with replies becomes an unavailable placeholder; one without replies disappears, along with any
     * deleted ancestors it was the last reply to. Writers are serialized by the group lock taken in writable().
     */
    @Transactional
    public void delete(UUID postId, UUID id, UUID actor) {
        var post = posts.writable(postId, actor);
        var comment = find(postId, id, actor);
        posts.requireAuthorOrModerator(post.groupId(), comment.authorId(), actor);
        if (comments.hasReplies(id)) {
            comments.softDelete(id);
            return;
        }
        comments.remove(id);
        UUID parentId = comment.parentCommentId();
        while (parentId != null) {
            var parent = comments.find(postId, parentId, actor).orElse(null);
            if (parent == null || !parent.deleted() || comments.hasReplies(parentId)) return;
            comments.remove(parentId);
            parentId = parent.parentCommentId();
        }
    }

    @Transactional
    public CommentResponse vote(UUID postId, UUID id, UUID actor, int value) {
        posts.writable(postId, actor);
        if (find(postId, id, actor).deleted()) throw GroupException.invalid("Cannot vote on a deleted comment");
        comments.vote(id, actor, value);
        return find(postId, id, actor);
    }

    /** Adding is idempotent, so a repeated click or retry never stacks the same reaction twice. */
    @Transactional
    public CommentResponse react(UUID postId, UUID id, UUID actor, String reaction) {
        var parsed = CommentReaction.parse(reaction);
        posts.writable(postId, actor);
        if (find(postId, id, actor).deleted()) throw GroupException.invalid("Cannot react to a deleted comment");
        comments.react(id, actor, parsed);
        return find(postId, id, actor);
    }

    @Transactional
    public void unreact(UUID postId, UUID id, UUID actor, String reaction) {
        var parsed = CommentReaction.parse(reaction);
        posts.writable(postId, actor);
        find(postId, id, actor);
        comments.unreact(id, actor, parsed);
    }

    private CommentResponse find(UUID postId, UUID id, UUID actor) {
        return comments.find(postId, id, actor).orElseThrow(GroupException::notFound);
    }
}
