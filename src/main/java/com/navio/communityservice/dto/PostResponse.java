package com.navio.communityservice.dto;

import java.time.Instant;
import java.util.UUID;

public record PostResponse(UUID id, UUID groupId, String groupSlug, String groupName, UUID authorId,
        String title, String body, String linkUrl, UUID flairId, String sharedTripId, String imageUrl,
        Instant createdAt, Instant updatedAt, int upvotes, int commentCount, int viewerVote) { }
