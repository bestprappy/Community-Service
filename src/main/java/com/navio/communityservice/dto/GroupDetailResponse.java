package com.navio.communityservice.dto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
public record GroupDetailResponse(UUID id, String name, String slug, String description, String country, String[] places, String[] tags,
        UUID createdById, UUID ownerId, boolean isOfficial, String status, int memberCount, int postCount,
        String bannerUrl, UUID bannerMediaId, String summary, int weeklyVisitorCount, int weeklyContributionCount,
        UUID[] moderatorIds, List<RuleResponse> rules, List<FlairResponse> postFlairs, List<FlairResponse> userFlairs,
        List<ResourceResponse> resources, boolean joined, boolean muted, String role,
        java.time.Instant createdAt, java.time.Instant updatedAt) { }
