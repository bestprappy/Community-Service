package com.navio.communityservice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * A post as shown in feeds, threads and profiles.
 *
 * @param pinnedOnProfile the author featured this post on their public profile
 */
public record PostResponse(UUID id, UUID groupId, String groupSlug, String groupName, UUID groupIconMediaId, UUID authorId,
        String title, String body, String linkUrl, UUID flairId, String sharedTripId, String imageUrl,
        Instant createdAt, Instant updatedAt, int upvotes, int commentCount, int viewerVote, boolean pinnedOnProfile) {

    public PostResponse(UUID id, UUID groupId, String groupSlug, String groupName, UUID groupIconMediaId, UUID authorId,
            String title, String body, String linkUrl, UUID flairId, String sharedTripId, String imageUrl,
            Instant createdAt, Instant updatedAt, int upvotes, int commentCount, int viewerVote) {
        this(id, groupId, groupSlug, groupName, groupIconMediaId, authorId, title, body, linkUrl, flairId, sharedTripId,
                imageUrl, createdAt, updatedAt, upvotes, commentCount, viewerVote, false);
    }
}
