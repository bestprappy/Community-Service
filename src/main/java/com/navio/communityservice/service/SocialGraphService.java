package com.navio.communityservice.service;

import com.navio.communityservice.dto.PostResponse;
import com.navio.communityservice.dto.SocialDtos.BlockedUserResponse;
import com.navio.communityservice.dto.SocialDtos.ConnectionResponse;
import com.navio.communityservice.dto.SocialDtos.SocialSettingsResponse;
import com.navio.communityservice.dto.SocialDtos.SocialSummaryResponse;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.repository.NotificationRepository;
import com.navio.communityservice.repository.PostRepository;
import com.navio.communityservice.repository.SocialGraphRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Following and blocking.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Nobody can follow themselves; following twice is a no-op.</li>
 *   <li>A block, in either direction, prevents following, removes existing follows
 *       both ways, erases notifications between the two accounts, hides each
 *       other's posts from feeds and profile tabs, and stops comments, replies and
 *       votes on each other's content (see {@link #requireCanInteract}).</li>
 *   <li>Follower/following lists honour the owner's visibility setting; counts are
 *       always visible.</li>
 * </ul>
 * Every method takes the acting account; nothing here acts on a different account's behalf.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SocialGraphService {

    /** Most accounts a follower-list search can be narrowed to in one request. */
    public static final int MAX_WITHIN = 100;

    private final SocialGraphRepository graph;
    private final NotificationRepository notifications;
    private final PostRepository posts;

    public SocialSummaryResponse summary(UUID target, UUID viewer) {
        boolean self = target.equals(viewer);
        var follow = viewer == null || self ? null : graph.findFollow(viewer, target).orElse(null);
        String visibility = graph.connectionsVisibility(target);
        return new SocialSummaryResponse(target, graph.followerCount(target), graph.followingCount(target), visibility,
                canViewConnections(target, viewer, visibility),
                follow != null, viewer != null && !self && graph.findFollow(target, viewer).isPresent(),
                follow != null && follow.alerts(),
                viewer != null && !self && graph.hasBlocked(viewer, target),
                viewer != null && !self && graph.hasBlocked(target, viewer), self);
    }

    @Transactional
    public SocialSummaryResponse follow(UUID actor, UUID target) {
        graph.lockPair(actor, target);
        if (actor.equals(target)) throw GroupException.invalid("You cannot follow yourself");
        if (graph.blockedEitherWay(actor, target)) throw GroupException.forbidden("You can't follow this account");
        if (graph.follow(actor, target)) notifications.notifyNewFollower(actor, target);
        return summary(target, actor);
    }

    @Transactional
    public SocialSummaryResponse unfollow(UUID actor, UUID target) {
        graph.lockPair(actor, target);
        graph.unfollow(actor, target);
        return summary(target, actor);
    }

    @Transactional
    public SocialSummaryResponse setAlerts(UUID actor, UUID target, boolean notify) {
        graph.lockPair(actor, target);
        if (!graph.setNotify(actor, target, notify)) {
            throw new GroupException(HttpStatus.NOT_FOUND, "Follow this account to change its alerts");
        }
        return summary(target, actor);
    }

    @Transactional
    public SocialSummaryResponse block(UUID actor, UUID target) {
        graph.lockPair(actor, target);
        if (actor.equals(target)) throw GroupException.invalid("You cannot block yourself");
        graph.block(actor, target);
        graph.removeFollowsBetween(actor, target);
        notifications.deleteBetween(actor, target);
        return summary(target, actor);
    }

    @Transactional
    public SocialSummaryResponse unblock(UUID actor, UUID target) {
        graph.lockPair(actor, target);
        graph.unblock(actor, target);
        return summary(target, actor);
    }

    public Page<BlockedUserResponse> blocked(UUID actor, int page, int size) {
        return graph.blocked(actor, PostService.pagination(page, size));
    }

    public Page<ConnectionResponse> followers(UUID target, UUID viewer, Collection<UUID> within, int page, int size) {
        requireConnectionsVisible(target, viewer, within);
        return graph.followers(target, viewer, within, PostService.pagination(page, size));
    }

    public Page<ConnectionResponse> following(UUID target, UUID viewer, Collection<UUID> within, int page, int size) {
        requireConnectionsVisible(target, viewer, within);
        return graph.following(target, viewer, within, PostService.pagination(page, size));
    }

    /** Public posts by one author, unless either side has blocked the other. */
    public Page<PostResponse> profilePosts(UUID author, UUID viewer, int page, int size) {
        if (graph.blockedEitherWay(author, viewer)) throw GroupException.forbidden("This profile's posts are not available");
        return posts.listByAuthor(author, viewer, PostService.pagination(page, size));
    }

    public SocialSettingsResponse settings(UUID actor) {
        return new SocialSettingsResponse(graph.connectionsVisibility(actor));
    }

    @Transactional
    public SocialSettingsResponse updateSettings(UUID actor, String visibility) {
        graph.saveConnectionsVisibility(actor, visibility);
        return settings(actor);
    }

    /**
     * Refuses an interaction (comment, reply, vote, reaction) between two accounts
     * when either has blocked the other. Acting on your own content is always allowed.
     */
    public void requireCanInteract(UUID actor, UUID owner) {
        if (actor == null || owner == null || Objects.equals(actor, owner)) return;
        if (graph.blockedEitherWay(actor, owner)) {
            throw GroupException.forbidden("You can't interact with this account");
        }
    }

    private void requireConnectionsVisible(UUID target, UUID viewer, Collection<UUID> within) {
        if (within != null && within.size() > MAX_WITHIN) throw GroupException.invalid("Search can narrow to at most 100 accounts");
        if (graph.blockedEitherWay(target, viewer)) throw GroupException.forbidden("This list is not available");
        if (!canViewConnections(target, viewer, graph.connectionsVisibility(target))) {
            throw GroupException.forbidden("This list is private");
        }
    }

    private boolean canViewConnections(UUID target, UUID viewer, String visibility) {
        if (target.equals(viewer)) return true;
        return switch (visibility) {
            case "everyone" -> true;
            case "followers" -> viewer != null && graph.findFollow(viewer, target).isPresent();
            default -> false;
        };
    }
}
