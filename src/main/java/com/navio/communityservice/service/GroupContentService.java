package com.navio.communityservice.service;
import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.model.*;
import com.navio.communityservice.repository.*;
import com.navio.communityservice.support.GroupValues;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class GroupContentService {
    private final GroupAccessService access;
    private final GroupProfileRepository profiles;
    private final GroupRuleRepository rules;
    private final GroupFlairRepository flairs;
    private final GroupResourceRepository resources;
    private final GroupViewService views;
    @Transactional
    public GroupDetailResponse updateProfile(String slug, UUID userId, UpdateGroupProfileRequest r) {
        Group g = moderated(slug, userId);
        GroupProfile p = profiles.findById(g.getId()).orElseThrow();
        if (r.has("description")) g.setDescription(GroupValues.required(r.getDescription(), "description"));
        if (r.has("country")) g.setCountry(r.getCountry() == null ? null : r.getCountry().strip());
        if (r.has("places")) {
            if (r.getPlaces() == null) throw GroupException.invalid("places must be an array");
            g.setPlaces(GroupValues.labels(r.getPlaces()));
        }
        if (r.has("tags")) {
            if (r.getTags() == null) throw GroupException.invalid("tags must be an array");
            g.setTags(GroupValues.labels(r.getTags()));
        }
        if (r.has("summary")) {
            if (r.getSummary() == null) throw GroupException.invalid("summary cannot be null");
            p.setSummary(r.getSummary().strip());
        }
        if (r.has("bannerUrl")) p.setBannerUrl(r.getBannerUrl());
        if (r.has("bannerMediaId")) p.setBannerMediaId(r.getBannerMediaId());
        return views.detail(g, userId);
    }
    @Transactional
    public GroupDetailResponse replaceRules(String slug, UUID userId, ReplaceRulesRequest request) {
        Group g = moderated(slug, userId);
        var rows = IntStream.range(0, request.rules().size()).mapToObj(i -> {
            var r = request.rules().get(i);
            return GroupRule.builder().groupId(g.getId()).title(GroupValues.required(r.title(), "title"))
                    .description(GroupValues.required(r.description(), "description")).displayOrder(i).build();
        }).toList();
        rules.deleteAllByGroupId(g.getId());
        rules.saveAll(rows);
        return views.detail(g, userId);
    }
    @Transactional
    public GroupDetailResponse replaceFlairs(String slug, UUID userId, ReplaceFlairsRequest request) {
        Group g = moderated(slug, userId);
        var rows = IntStream.range(0, request.flairs().size()).mapToObj(i -> {
            var r = request.flairs().get(i);
            return GroupFlair.builder().groupId(g.getId()).label(GroupValues.required(r.label(), "label"))
                    .flairType(r.flairType()).tone(r.tone()).displayOrder(i).build();
        }).toList();
        flairs.deleteAllByGroupId(g.getId());
        flairs.saveAll(rows);
        return views.detail(g, userId);
    }
    @Transactional
    public GroupDetailResponse replaceResources(String slug, UUID userId, ReplaceResourcesRequest request) {
        Group g = moderated(slug, userId);
        var rows = IntStream.range(0, request.resources().size()).mapToObj(i -> {
            var r = request.resources().get(i);
            return GroupResource.builder().groupId(g.getId()).label(GroupValues.required(r.label(), "label"))
                    .url(r.url()).displayOrder(i).build();
        }).toList();
        resources.deleteAllByGroupId(g.getId());
        resources.saveAll(rows);
        return views.detail(g, userId);
    }
    private Group moderated(String slug, UUID userId) {
        Group g = access.lock(slug, userId);
        access.requireModerator(g.getId(), userId);
        g.setUpdatedAt(Instant.now());
        return g;
    }
}
