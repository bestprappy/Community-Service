package com.navio.communityservice.service;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.model.*;
import com.navio.communityservice.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class GroupAccessService {
    public static final List<String> ACTIVE_STATES = List.of("joined", "muted");
    public static final List<String> MODERATOR_ROLES = List.of("moderator", "admin");
    private final GroupRepository groups;
    private final GroupMembershipRepository memberships;
    public boolean isOwner(Group group, UUID userId) {
        return userId != null && userId.equals(group.getOwnerUserId());
    }
    public void requireOwner(Group group, UUID userId) {
        if (!isOwner(group, userId)) throw GroupException.forbidden("Group ownership is required");
    }
    public boolean isModerator(UUID groupId, UUID userId) {
        return userId != null && memberships.existsByIdGroupIdAndIdUserIdAndRoleInAndStateIn(
                groupId, userId, MODERATOR_ROLES, ACTIVE_STATES);
    }
    public void requireModerator(UUID groupId, UUID userId) {
        if (!isModerator(groupId, userId)) throw GroupException.forbidden("Group moderator membership is required");
    }
    /** Call after taking the group row lock, within the write transaction. */
    public void requireActiveMember(Group group, UUID userId) {
        if (userId == null || memberships.findById(new MembershipId(group.getId(), userId))
                .filter(GroupMembership::isActive).isEmpty()) {
            throw GroupException.forbidden("Active group membership is required");
        }
        if ("archived".equals(group.getStatus())) throw GroupException.conflict("Archived groups are read-only");
        if ("hidden".equals(group.getStatus())) requireModerator(group.getId(), userId);
    }
    public void requireActive(Group group) {
        if (!"active".equals(group.getStatus())) throw GroupException.conflict("Group must be active");
    }
    public void requireContentEditor(Group group, UUID userId) {
        if ("archived".equals(group.getStatus())) requireOwner(group, userId);
        else if (!isOwner(group, userId)) requireModerator(group.getId(), userId);
    }
    public Group readable(String slug, UUID userId) {
        return visible(groups.findBySlug(slug).orElseThrow(GroupException::notFound), userId);
    }
    /** All writers share this row lock; each operation then applies its status policy. */
    @Transactional
    public Group lock(String slug, UUID userId) {
        return visible(groups.lockBySlug(slug).orElseThrow(GroupException::notFound), userId);
    }
    private Group visible(Group group, UUID userId) {
        if ("hidden".equals(group.getStatus()) && !isOwner(group, userId)
                && !isModerator(group.getId(), userId)) throw GroupException.notFound();
        return group;
    }
}
