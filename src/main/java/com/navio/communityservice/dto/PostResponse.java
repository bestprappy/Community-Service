package com.navio.communityservice.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A post as shown in feeds, threads and profiles.
 *
 * @param imageUrl the first picture, kept for clients that show only one
 * @param pinnedOnProfile the author featured this post on their public profile
 * @param imageUrls every picture in gallery order; empty when the post has none
 */
public record PostResponse(UUID id, UUID groupId, String groupSlug, String groupName, UUID groupIconMediaId, UUID authorId,
        String title, String body, String linkUrl, UUID flairId, String sharedTripId, String imageUrl,
        Instant createdAt, Instant updatedAt, int upvotes, int commentCount, int viewerVote, boolean pinnedOnProfile, boolean saved,
        List<String> imageUrls) {

    public PostResponse {
        imageUrls = imageUrls == null ? (imageUrl == null ? List.of() : List.of(imageUrl)) : List.copyOf(imageUrls);
    }

    public PostResponse(UUID id, UUID groupId, String groupSlug, String groupName, UUID groupIconMediaId, UUID authorId,
            String title, String body, String linkUrl, UUID flairId, String sharedTripId, String imageUrl,
            Instant createdAt, Instant updatedAt, int upvotes, int commentCount, int viewerVote, boolean pinnedOnProfile, boolean saved) {
        this(id,groupId,groupSlug,groupName,groupIconMediaId,authorId,title,body,linkUrl,flairId,sharedTripId,imageUrl,
                createdAt,updatedAt,upvotes,commentCount,viewerVote,pinnedOnProfile,saved,null);
    }

    public PostResponse(UUID id, UUID groupId, String groupSlug, String groupName, UUID groupIconMediaId, UUID authorId,
            String title, String body, String linkUrl, UUID flairId, String sharedTripId, String imageUrl,
            Instant createdAt, Instant updatedAt, int upvotes, int commentCount, int viewerVote, boolean pinnedOnProfile) {
        this(id,groupId,groupSlug,groupName,groupIconMediaId,authorId,title,body,linkUrl,flairId,sharedTripId,imageUrl,
                createdAt,updatedAt,upvotes,commentCount,viewerVote,pinnedOnProfile,false);
    }

    public PostResponse(UUID id, UUID groupId, String groupSlug, String groupName, UUID groupIconMediaId, UUID authorId,
            String title, String body, String linkUrl, UUID flairId, String sharedTripId, String imageUrl,
            Instant createdAt, Instant updatedAt, int upvotes, int commentCount, int viewerVote) {
        this(id, groupId, groupSlug, groupName, groupIconMediaId, authorId, title, body, linkUrl, flairId, sharedTripId,
                imageUrl, createdAt, updatedAt, upvotes, commentCount, viewerVote, false);
    }
}
