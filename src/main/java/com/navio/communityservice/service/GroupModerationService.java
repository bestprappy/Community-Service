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
        access.requireModerator(g.getId(), userId);
        Set<UUID> desired = new LinkedHashSet<>(request.userIds());
        if (desired.isEmpty()) throw GroupException.conflict("A group must keep at least one moderator");
        List<GroupMembership> candidates = memberships.findByIdGroupIdAndIdUserIdIn(g.getId(), desired);
        if (candidates.size() != desired.size() || candidates.stream().anyMatch(m -> !m.isActive())) {
            throw GroupException.invalid("Every moderator must already be a joined or muted group member");
        }
        // Validate the entire replacement before changing any managed entity.
        List<GroupMembership> previous = memberships.findByIdGroupIdAndRoleIn(g.getId(), GroupAccessService.MODERATOR_ROLES);
        previous.stream().filter(m -> !desired.contains(m.getId().getUserId())).forEach(m -> m.setRole("member"));
        candidates.stream().filter(m -> !"admin".equals(m.getRole())).forEach(m -> m.setRole("moderator"));
        memberships.saveAll(previous);
        memberships.saveAll(candidates);
        profiles.findById(g.getId()).orElseThrow().setModeratorIds(desired.toArray(UUID[]::new));
        g.setUpdatedAt(Instant.now());
        return views.detail(g, userId);
    }
}
