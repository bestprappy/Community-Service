package com.navio.communityservice.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record CreatePostRequest(
        @NotBlank @Size(max = 140) String groupSlug,
        @NotBlank @Size(max = 300) String title,
        @NotNull @Size(max = 40000) String body,
        @Size(max = 2048) @Pattern(regexp = "https?://[^\\s]+", message = "Enter an HTTP or HTTPS URL") String linkUrl,
        UUID flairId,
        // A shared-plan token: 32 random bytes, base64url without padding.
        @Pattern(regexp = "[A-Za-z0-9_-]{43}", message = "Choose a plan listed on Explore") String sharedTripId,
        UUID requestId) {
    public CreatePostRequest(String groupSlug, String title, String body, String linkUrl, UUID flairId, String sharedTripId) {
        this(groupSlug, title, body, linkUrl, flairId, sharedTripId, null);
    }
}
