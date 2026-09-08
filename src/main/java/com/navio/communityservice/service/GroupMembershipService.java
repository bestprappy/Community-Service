package com.navio.communityservice.service;
import com.navio.communityservice.dto.MembershipResponse;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.model.*;
import com.navio.communityservice.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class GroupMembershipService {
    private final GroupAccessService access;
    private final GroupMembershipRepository memberships;
    private final GroupProfileRepository profiles;
    @Transactional
    public MembershipResponse join(String slug, UUID userId) {
        Group g = access.lock(slug, userId);
        GroupMembership m = memberships.findById(new MembershipId(g.getId(), userId)).orElse(null);
        if (m != null && "banned".equals(m.getState())) throw GroupException.forbidden("Banned members cannot join this group");
        if (m == null || "left".equals(m.getState())) {
            if (m == null) m = GroupMembership.builder().id(new MembershipId(g.getId(), userId)).build();
            m.setState("joined");
            memberships.save(m);
            g.setMemberCount(g.getMemberCount() + 1);
            g.setUpdatedAt(Instant.now());
            if (m.isModerator()) mirrorModerators(g.getId());
        }
        return MembershipResponse.from(g.getId(), m, g.getMemberCount());
    }
    @Transactional
    public MembershipResponse leave(String slug, UUID userId) {
        Group g = access.lock(slug, userId);
        GroupMembership m = memberships.findById(new MembershipId(g.getId(), userId)).orElse(null);
        if (m != null && "banned".equals(m.getState())) throw GroupException.forbidden("Banned membership cannot be changed");
        if (m != null && m.isActive()) {
            if (m.isModerator() && memberships.findByIdGroupIdAndRoleIn(g.getId(), GroupAccessService.MODERATOR_ROLES)
                    .stream().filter(GroupMembership::isActive).count() == 1) {
                throw GroupException.conflict("Hand over moderation before leaving: you are the last moderator");
            }
            m.setState("left");
            memberships.save(m);
            g.setMemberCount(g.getMemberCount() - 1);
            g.setUpdatedAt(Instant.now());
            if (m.isModerator()) mirrorModerators(g.getId());
        }
        return MembershipResponse.from(g.getId(), m, g.getMemberCount());
    }
    @Transactional
    public MembershipResponse mute(String slug, UUID userId, boolean muted) {
        Group g = access.lock(slug, userId);
        GroupMembership m = memberships.findById(new MembershipId(g.getId(), userId))
                .filter(GroupMembership::isActive).orElseThrow(() -> GroupException.forbidden("Join the group before changing mute state"));
        String desired = muted ? "muted" : "joined";
        if (!desired.equals(m.getState())) {
            m.setState(desired);
            memberships.save(m);
        }
        return MembershipResponse.from(g.getId(), m, g.getMemberCount());
    }
    private void mirrorModerators(UUID groupId) {
        GroupProfile profile = profiles.findById(groupId).orElseThrow();
        profile.setModeratorIds(memberships.findByIdGroupIdAndRoleIn(groupId, GroupAccessService.MODERATOR_ROLES)
                .stream().filter(GroupMembership::isActive).map(m -> m.getId().getUserId()).sorted().toArray(UUID[]::new));
    }
}
