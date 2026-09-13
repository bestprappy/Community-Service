package com.navio.communityservice.dto;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(UUID id, UUID postId, UUID parentCommentId, UUID authorId, String body,
        boolean deleted, boolean edited, Instant createdAt, int upvotes, int viewerVote) { }
