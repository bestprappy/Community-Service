package com.navio.communityservice.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record CreatePostRequest(
        @NotBlank @Size(max = 140) String groupSlug,
        @NotBlank @Size(max = 300) String title,
        @NotNull @Size(max = 40000) String body,
        @Size(max = 2048) @Pattern(regexp = "https?://[^\\s]+", message = "Enter an HTTP or HTTPS URL") String linkUrl,
        UUID flairId,
        @Size(max = 160) String sharedTripId,
        UUID requestId) {
    public CreatePostRequest(String groupSlug, String title, String body, String linkUrl, UUID flairId, String sharedTripId) {
        this(groupSlug, title, body, linkUrl, flairId, sharedTripId, null);
    }
}
