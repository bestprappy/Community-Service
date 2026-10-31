package com.navio.communityservice.service;

import com.navio.communityservice.dto.SocialDtos.NotificationPreferences;
import com.navio.communityservice.dto.SocialDtos.NotificationResponse;
import com.navio.communityservice.dto.SocialDtos.UnreadCountResponse;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.integration.SharedPlanDirectory;
import com.navio.communityservice.integration.SharedPlanDirectory.ListedPlan;
import com.navio.communityservice.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The recipient's in-app notifications.
 *
 * <p>Reads are always scoped to the caller. Post notifications disappear with
 * their post (database cascade) or a hidden community (query filter). Plan
 * notifications are re-checked against trip planning on every read, so a plan
 * that was unlisted, revoked or re-published anonymously is shown without its
 * title or link.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class NotificationService {

    private final NotificationRepository notifications;
    private final SharedPlanDirectory sharedPlans;

    public Page<NotificationResponse> list(UUID recipient, boolean unreadOnly, int page, int size) {
        Page<NotificationResponse> result = notifications.list(recipient, unreadOnly, PostService.pagination(page, size));
        Map<UUID, Optional<Set<String>>> listedByActor = new HashMap<>();
        return result.map(notification -> {
            if (!"followed_plan".equals(notification.type())) return notification;
            var listed = listedByActor.computeIfAbsent(notification.actorId(), this::listedTokens);
            boolean stillShared = listed.map(tokens -> tokens.contains(notification.planToken())).orElse(false);
            if (stillShared) return notification;
            return new NotificationResponse(notification.id(), notification.type(), notification.actorId(),
                    notification.createdAt(), notification.read(), null, null, null, null, null, false);
        });
    }

    public UnreadCountResponse unreadCount(UUID recipient) {
        return new UnreadCountResponse(notifications.unreadCount(recipient));
    }

    @Transactional
    public UnreadCountResponse markRead(UUID recipient, UUID id) {
        if (!notifications.markRead(recipient, id)) {
            throw new GroupException(HttpStatus.NOT_FOUND, "Notification not found");
        }
        return unreadCount(recipient);
    }

    @Transactional
    public UnreadCountResponse markAllRead(UUID recipient) {
        notifications.markAllRead(recipient);
        return unreadCount(recipient);
    }

    public NotificationPreferences preferences(UUID user) {
        return notifications.preferences(user);
    }

    @Transactional
    public NotificationPreferences updatePreferences(UUID user, NotificationPreferences preferences) {
        notifications.savePreferences(user, preferences);
        return notifications.preferences(user);
    }

    /** Called inside the post's own transaction, so a rolled-back post notifies nobody. */
    @Transactional
    public void postPublished(UUID author, UUID postId) {
        notifications.notifyFollowersOfPost(author, postId);
    }

    /**
     * Tells followers that the caller listed a plan on Explore. The caller can only
     * announce a plan trip planning confirms they listed under their own name, and
     * the per-recipient dedupe key means repeating the call notifies nobody twice.
     *
     * @return how many followers were notified
     */
    @Transactional
    public int planShared(UUID author, String token) {
        List<ListedPlan> listed;
        try {
            listed = sharedPlans.listedByAuthor(author);
        } catch (SharedPlanDirectory.SharedPlanDirectoryUnavailableException exception) {
            throw new GroupException(HttpStatus.SERVICE_UNAVAILABLE, "Shared plans cannot be checked right now; try again shortly");
        }
        ListedPlan plan = listed.stream().filter(candidate -> token.equals(candidate.token())).findFirst()
                .orElseThrow(() -> new GroupException(HttpStatus.NOT_FOUND,
                        "Only a plan you listed on Explore under your name can be announced"));
        String title = plan.title() == null ? null : plan.title().strip();
        if (title != null && title.length() > 200) title = title.substring(0, 200);
        return notifications.notifyFollowersOfPlan(author, token, title);
    }

    /** Empty when trip planning cannot answer: the notification is then shown without its link. */
    private Optional<Set<String>> listedTokens(UUID actor) {
        try {
            return Optional.of(sharedPlans.listedByAuthor(actor).stream().map(ListedPlan::token).collect(Collectors.toSet()));
        } catch (SharedPlanDirectory.SharedPlanDirectoryUnavailableException exception) {
            log.warn("Plan notification links hidden: trip planning unavailable");
            return Optional.empty();
        }
    }
}
