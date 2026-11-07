package com.navio.communityservice.dto;
import java.time.Instant;
import java.util.UUID;
public record PostLibraryEntry(PostResponse post, Instant activityAt, UUID commentId, String commentBody) {}
