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
    public boolean isModerator(UUID groupId, UUID userId) {
        return userId != null && memberships.existsByIdGroupIdAndIdUserIdAndRoleInAndStateIn(
                groupId, userId, MODERATOR_ROLES, ACTIVE_STATES);
    }
    public void requireModerator(UUID groupId, UUID userId) {
        if (!isModerator(groupId, userId)) throw GroupException.forbidden("Group moderator membership is required");
    }
    public Group readable(String slug, UUID userId) {
        return visible(groups.findBySlug(slug).orElseThrow(GroupException::notFound), userId);
    }
    /** All writers take this same row lock before reading memberships or counters. */
    @Transactional
    public Group lock(String slug, UUID userId) {
        return visible(groups.lockBySlug(slug).orElseThrow(GroupException::notFound), userId);
    }
    private Group visible(Group group, UUID userId) {
        if ("hidden".equals(group.getStatus()) && !isModerator(group.getId(), userId)) throw GroupException.notFound();
        return group;
    }
}
