package com.navio.communityservice.service;
import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.model.*;
import com.navio.communityservice.repository.*;
import com.navio.communityservice.support.GroupValues;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class GroupService {
    private final GroupRepository groups;
    private final GroupProfileRepository profiles;
    private final GroupMembershipRepository memberships;
    private final GroupAccessService access;
    private final GroupViewService views;
    @Transactional
    public GroupDetailResponse create(UUID userId, CreateGroupRequest request) {
        String name = GroupValues.required(request.name(), "name");
        if (name.length() > 120) throw GroupException.invalid("name must be at most 120 characters");
        Group group = Group.builder().name(name).slug(GroupValues.slug(name))
                .description(GroupValues.required(request.description(), "description"))
                .country(request.country() == null ? null : request.country().strip())
                .places(GroupValues.labels(request.places())).tags(GroupValues.labels(request.tags()))
                .createdByUserId(userId).memberCount(1).build();
        // Flush the group first: the database UNIQUE constraint resolves concurrent slug collisions.
        group = groups.saveAndFlush(group);
        profiles.save(GroupProfile.builder().groupId(group.getId()).summary(group.getDescription())
                .moderatorIds(new UUID[]{userId}).build());
        memberships.save(GroupMembership.builder().id(new MembershipId(group.getId(), userId)).role("admin").build());
        return views.detail(group, userId);
    }
    public GroupDetailResponse detail(String slug, UUID userId) { return views.detail(access.readable(slug, userId), userId); }
    public Page<GroupListItem> discover(UUID userId, Pageable page) { return groups.discover(userId, page); }
    public Page<GroupListItem> mine(UUID userId, Pageable page) { return groups.mine(userId, page); }
    public Page<GroupListItem> search(UUID userId, String q, Pageable page) {
        if (q == null || q.isBlank()) return Page.empty(page);
        return groups.search(userId, q.strip(), page).map(r -> new GroupListItem(r.getId(), r.getName(), r.getSlug(),
                r.getDescription(), r.getCountry(), r.getPlaces(), r.getTags(), r.getOfficial(), r.getStatus(),
                r.getMemberCount(), r.getPostCount(), GroupAccessService.ACTIVE_STATES.contains(
                        r.getMembershipState() == null ? "" : r.getMembershipState()),
                "muted".equals(r.getMembershipState()), r.getRole(), r.getCreatedAt(), r.getUpdatedAt()));
    }
}
