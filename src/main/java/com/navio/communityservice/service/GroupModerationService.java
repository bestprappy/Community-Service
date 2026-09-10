package com.navio.communityservice.service;
import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.model.*;
import com.navio.communityservice.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class GroupModerationService {
    private final GroupAccessService access;
    private final GroupMembershipRepository memberships;
    private final GroupProfileRepository profiles;
    private final GroupViewService views;
    public Page<MemberResponse> members(String slug, UUID userId, Pageable page) {
        Group g = access.readable(slug, userId);
        access.requireModerator(g.getId(), userId);
        return memberships.findByIdGroupIdAndStateInOrderByUpdatedAtDescIdUserIdAsc(g.getId(), GroupAccessService.ACTIVE_STATES, page)
            .map(m -> new MemberResponse(m.getId().getUserId(), m.getRole(), m.getState(), m.getJoinedAt(), m.getUpdatedAt()));
    }
    @Transactional
    public GroupDetailResponse replaceModerators(String slug, UUID userId, ReplaceModeratorsRequest request) {
        Group g = access.lock(slug, userId);
        access.requireOwner(g, userId);
        access.requireActive(g);
        Set<UUID> desired = new LinkedHashSet<>(request.userIds());
        desired.add(g.getOwnerUserId()); // The owner is always retained, even for an empty roster.
        List<GroupMembership> candidates = memberships.findByIdGroupIdAndIdUserIdIn(g.getId(), desired);
        if (candidates.size() != desired.size() || candidates.stream().anyMatch(m -> !m.isActive())) {
            throw GroupException.invalid("Every moderator must already be a joined or muted group member");
        }
        // Validate the entire replacement before changing any managed entity.
        List<GroupMembership> previous = memberships.findByIdGroupIdAndRoleIn(g.getId(), GroupAccessService.MODERATOR_ROLES);
        previous.stream().filter(m -> !desired.contains(m.getId().getUserId())).forEach(m -> m.setRole("member"));
        candidates.forEach(m -> m.setRole(m.getId().getUserId().equals(g.getOwnerUserId()) ? "admin" : "moderator"));
        memberships.saveAll(previous);
        memberships.saveAll(candidates);
        profiles.findById(g.getId()).orElseThrow().setModeratorIds(desired.toArray(UUID[]::new));
        g.setUpdatedAt(Instant.now());
        return views.detail(g, userId);
    }
    @Transactional
    public MemberResponse setBanned(String slug, UUID actor, UUID target, boolean banned) {
        Group g = access.lock(slug, actor);
        access.requireModerator(g.getId(), actor);
        if ("archived".equals(g.getStatus())) throw GroupException.conflict("Archived groups are read-only");
        if (target.equals(g.getOwnerUserId())) throw GroupException.forbidden("The owner cannot be banned");
        GroupMembership m = memberships.findById(new MembershipId(g.getId(), target))
                .orElseThrow(() -> GroupException.invalid("User has no membership in this group"));
        if (m.isModerator() && !access.isOwner(g, actor)) {
            throw GroupException.forbidden("Only the owner can ban a moderator");
        }
        if (banned && !"banned".equals(m.getState())) {
            if (m.isActive()) g.setMemberCount(g.getMemberCount() - 1);
            m.setState("banned");
            m.setRole("member"); // Unbanning never restores staff privileges.
        } else if (!banned && "banned".equals(m.getState())) {
            m.setState("left"); // The user chooses whether to rejoin.
        }
        memberships.save(m);
        mirrorModerators(g.getId());
        g.setUpdatedAt(Instant.now());
        return new MemberResponse(target, m.getRole(), m.getState(), m.getJoinedAt(), m.getUpdatedAt());
    }
    @Transactional
    public GroupDetailResponse transferOwnership(String slug, UUID actor, UUID target) {
        Group g = access.lock(slug, actor);
        access.requireOwner(g, actor);
        access.requireActive(g);
        GroupMembership next = memberships.findById(new MembershipId(g.getId(), target))
                .filter(GroupMembership::isActive)
                .orElseThrow(() -> GroupException.invalid("New owner must be a joined or muted member"));
        if (!target.equals(g.getOwnerUserId())) {
            GroupMembership previous = memberships.findById(new MembershipId(g.getId(), g.getOwnerUserId())).orElseThrow();
            previous.setRole("member");
            next.setRole("admin");
            g.setOwnerUserId(target);
            memberships.saveAll(List.of(previous, next));
            mirrorModerators(g.getId());
            g.setUpdatedAt(Instant.now());
        }
        return views.detail(g, actor);
    }
    @Transactional
    public GroupDetailResponse updateStatus(String slug, UUID actor, String status) {
        Group g = access.lock(slug, actor);
        access.requireOwner(g, actor);
        if ("hidden".equals(g.getStatus())) throw GroupException.conflict("Hidden groups cannot be republished by their owner");
        if (!List.of("active", "archived").contains(status)) throw GroupException.invalid("Invalid group status");
        g.setStatus(status);
        g.setUpdatedAt(Instant.now());
        return views.detail(g, actor);
    }
    private void mirrorModerators(UUID groupId) {
        profiles.findById(groupId).orElseThrow().setModeratorIds(
                memberships.findByIdGroupIdAndRoleIn(groupId, GroupAccessService.MODERATOR_ROLES).stream()
                        .filter(GroupMembership::isActive).map(m -> m.getId().getUserId()).sorted().toArray(UUID[]::new));
    }

}
