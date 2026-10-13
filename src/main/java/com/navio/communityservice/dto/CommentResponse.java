package com.navio.communityservice.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CommentResponse(UUID id, UUID postId, UUID parentCommentId, UUID authorId, String body,
        boolean deleted, boolean edited, Instant createdAt, int upvotes, int viewerVote, List<ReactionSummary> reactions) {
    public CommentResponse(UUID id, UUID postId, UUID parentCommentId, UUID authorId, String body,
            boolean deleted, boolean edited, Instant createdAt, int upvotes, int viewerVote) {
        this(id, postId, parentCommentId, authorId, body, deleted, edited, createdAt, upvotes, viewerVote, List.of());
    }

    public CommentResponse withReactions(List<ReactionSummary> value) {
        return new CommentResponse(id, postId, parentCommentId, authorId, body, deleted, edited, createdAt, upvotes, viewerVote, value);
    }
}
