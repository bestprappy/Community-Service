package com.navio.communityservice.service;
import com.navio.communityservice.dto.*;
import com.navio.communityservice.model.*;
import com.navio.communityservice.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class GroupViewService {
    private final GroupProfileRepository profiles;
    private final GroupMembershipRepository memberships;
    private final GroupRuleRepository rules;
    private final GroupFlairRepository flairs;
    private final GroupResourceRepository resources;
    public GroupDetailResponse detail(Group g, UUID userId) {
        GroupProfile p = profiles.findById(g.getId()).orElseThrow(() -> new IllegalStateException("Missing group profile"));
        GroupMembership m = userId == null ? null : memberships.findById(new MembershipId(g.getId(), userId)).orElse(null);
        var groupFlairs = flairs.findByGroupIdOrderByDisplayOrderAscCreatedAtAsc(g.getId());
        return new GroupDetailResponse(g.getId(), g.getName(), g.getSlug(), g.getDescription(), g.getCountry(),
                g.getPlaces(), g.getTags(), g.getCreatedByUserId(), g.getOwnerUserId(), g.isOfficial(), g.getStatus(), g.getMemberCount(),
                g.getPostCount(), p.getBannerUrl(), p.getBannerMediaId(), p.getSummary(), g.getWeeklyVisitorCount(),
                g.getWeeklyContributionCount(), p.getModeratorIds(),
                rules.findByGroupIdOrderByDisplayOrderAscCreatedAtAsc(g.getId()).stream()
                    .map(r -> new RuleResponse(r.getId(), r.getTitle(), r.getDescription(), r.getDisplayOrder())).toList(),
                flairViews(groupFlairs, "post"), flairViews(groupFlairs, "user"),
                resources.findByGroupIdOrderByDisplayOrderAscCreatedAtAsc(g.getId()).stream()
                    .map(r -> new ResourceResponse(r.getId(), r.getLabel(), r.getUrl(), r.getDisplayOrder())).toList(),
                m != null && m.isActive(), m != null && "muted".equals(m.getState()), m == null ? null : m.getRole(),
                g.getCreatedAt(), g.getUpdatedAt());
    }
    private List<FlairResponse> flairViews(List<GroupFlair> rows, String type) {
        return rows.stream().filter(f -> type.equals(f.getFlairType()))
            .map(f -> new FlairResponse(f.getId(), f.getLabel(), f.getTone(), f.getDisplayOrder())).toList();
    }
}
