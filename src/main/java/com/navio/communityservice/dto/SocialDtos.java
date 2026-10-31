package com.navio.communityservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** Request and response shapes for following, blocking, reports and notifications. */
public final class SocialDtos {

    private SocialDtos() {
    }

    /**
     * One person's public social facts, as seen by the viewer.
     *
     * @param canViewConnections whether the viewer may open the follower/following lists
     * @param viewerFollows      the viewer follows this person
     * @param followsViewer      this person follows the viewer
     * @param viewerNotify       the viewer gets alerts for this person's new posts and plans
     * @param viewerBlocked      the viewer has blocked this person
     * @param blockedViewer      this person has blocked the viewer
     */
    public record SocialSummaryResponse(UUID accountId, long followerCount, long followingCount,
            String connectionsVisibility, boolean canViewConnections, boolean viewerFollows, boolean followsViewer,
            boolean viewerNotify, boolean viewerBlocked, boolean blockedViewer, boolean self) { }

    /**
     * A row in a follower or following list.
     *
     * @param viewerAlerts the viewer follows this account with alerts on
     */
    public record ConnectionResponse(UUID accountId, Instant since, boolean viewerFollows, boolean viewerAlerts) { }

    public record BlockedUserResponse(UUID accountId, Instant blockedAt) { }

    public record FollowAlertsRequest(@NotNull Boolean alerts) { }

    public record SocialSettingsResponse(String connectionsVisibility) { }

    public record SocialSettingsRequest(
            @NotBlank @Pattern(regexp = "^(everyone|followers|only_me)$",
                    message = "connectionsVisibility must be everyone, followers or only_me")
            String connectionsVisibility) { }

    public record CreateReportRequest(
            @NotBlank @Pattern(regexp = "^(user|post|comment)$", message = "targetType must be user, post or comment")
            String targetType,
            @NotNull UUID targetId,
            @NotBlank @Pattern(regexp = "^(spam|harassment|hate|inappropriate|impersonation|misinformation|other)$",
                    message = "Choose a report reason")
            String reason,
            @Size(max = 1000, message = "Details must be at most 1000 characters")
            String details) { }

    public record ReportResponse(UUID id, String targetType, UUID targetId, UUID targetUserId, String reason,
            String details, String status, UUID reporterId, String resolutionNote, UUID resolvedBy,
            Instant resolvedAt, Instant createdAt, String targetPreview, boolean targetAvailable) { }

    public record ResolveReportRequest(
            @NotBlank @Pattern(regexp = "^(actioned|dismissed)$", message = "status must be actioned or dismissed")
            String status,
            @Size(max = 1000, message = "Notes must be at most 1000 characters")
            String note,
            boolean removeContent) { }

    /**
     * One in-app notification. {@code available} is false when what it points at
     * has been deleted, hidden or unshared; the client then shows it without a link.
     */
    public record NotificationResponse(UUID id, String type, UUID actorId, Instant createdAt, boolean read,
            UUID postId, String postTitle, String groupSlug, String planToken, String planTitle,
            boolean available) { }

    public record UnreadCountResponse(long unreadCount) { }

    public record NotificationPreferences(@NotNull Boolean newFollower, @NotNull Boolean followedPost,
            @NotNull Boolean followedPlan) {
        public static NotificationPreferences defaults() {
            return new NotificationPreferences(true, true, true);
        }
    }

    public record PlanSharedRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_-]+$", message = "Invalid plan link")
            String token) { }
}
