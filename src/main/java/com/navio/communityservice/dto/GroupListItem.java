package com.navio.communityservice.dto;
import com.navio.communityservice.model.Group;
import java.time.Instant;
import java.util.UUID;
public record GroupListItem(UUID id, String name, String slug, String description, String country,
        String[] places, String[] tags, boolean isOfficial, String status, int memberCount, int postCount,
        boolean joined, boolean muted, String role, UUID iconMediaId, UUID bannerMediaId,
        Instant createdAt, Instant updatedAt) {
    public GroupListItem(Group g, String state, String role, UUID iconMediaId, UUID bannerMediaId) {
        this(g.getId(), g.getName(), g.getSlug(), g.getDescription(), g.getCountry(), g.getPlaces(), g.getTags(),
             g.isOfficial(), g.getStatus(), g.getMemberCount(), g.getPostCount(),
             "joined".equals(state) || "muted".equals(state), "muted".equals(state), role,
             iconMediaId, bannerMediaId, g.getCreatedAt(), g.getUpdatedAt());
    }
}
