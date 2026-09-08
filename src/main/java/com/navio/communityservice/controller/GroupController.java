package com.navio.communityservice.controller;
import com.navio.communityservice.dto.*;
import com.navio.communityservice.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.UUID;

@RestController @RequestMapping("/v1/groups") @RequiredArgsConstructor
public class GroupController {
    private final GroupService groups;
    private final GroupMembershipService memberships;
    private final GroupModerationService moderation;
    private final GroupContentService content;
    @PostMapping
    public ResponseEntity<GroupDetailResponse> create(@RequestHeader(name = "X-User-Id") UUID userId,
            @Valid @RequestBody CreateGroupRequest request) {
        var result = groups.create(userId, request);
        return ResponseEntity.created(URI.create("/v1/groups/" + result.slug())).body(result);
    }
    @GetMapping
    public Page<GroupListItem> discover(@RequestHeader(name = "X-User-Id", required = false) UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return groups.discover(userId, PageRequest.of(page, size));
    }
    @GetMapping("/mine")
    public Page<GroupListItem> mine(@RequestHeader(name = "X-User-Id") UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return groups.mine(userId, PageRequest.of(page, size));
    }
    @GetMapping("/search")
    public Page<GroupListItem> search(@RequestHeader(name = "X-User-Id", required = false) UUID userId,
            @RequestParam(required = false) String q, @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return groups.search(userId, q, PageRequest.of(page, size));
    }
    @GetMapping("/{slug}")
    public GroupDetailResponse detail(@PathVariable String slug, @RequestHeader(name = "X-User-Id", required = false) UUID userId) {
        return groups.detail(slug, userId);
    }
    @PostMapping("/{slug}/members/me")
    public MembershipResponse join(@PathVariable String slug, @RequestHeader(name = "X-User-Id") UUID userId) {
        return memberships.join(slug, userId);
    }
    @DeleteMapping("/{slug}/members/me")
    public MembershipResponse leave(@PathVariable String slug, @RequestHeader(name = "X-User-Id") UUID userId) {
        return memberships.leave(slug, userId);
    }
    @PatchMapping("/{slug}/members/me")
    public MembershipResponse mute(@PathVariable String slug, @RequestHeader(name = "X-User-Id") UUID userId,
            @Valid @RequestBody MuteGroupRequest request) {
        return memberships.mute(slug, userId, request.muted());
    }
    @GetMapping("/{slug}/members")
    public Page<MemberResponse> members(@PathVariable String slug, @RequestHeader(name = "X-User-Id") UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return moderation.members(slug, userId, PageRequest.of(page, size));
    }
    @PutMapping("/{slug}/moderators")
    public GroupDetailResponse moderators(@PathVariable String slug, @RequestHeader(name = "X-User-Id") UUID userId,
            @Valid @RequestBody ReplaceModeratorsRequest request) {
        return moderation.replaceModerators(slug, userId, request);
    }
    @PatchMapping("/{slug}/profile")
    public GroupDetailResponse profile(@PathVariable String slug, @RequestHeader(name = "X-User-Id") UUID userId,
            @Valid @RequestBody UpdateGroupProfileRequest request) {
        return content.updateProfile(slug, userId, request);
    }
    @PutMapping("/{slug}/rules")
    public GroupDetailResponse rules(@PathVariable String slug, @RequestHeader(name = "X-User-Id") UUID userId,
            @Valid @RequestBody ReplaceRulesRequest request) {
        return content.replaceRules(slug, userId, request);
    }
    @PutMapping("/{slug}/flairs")
    public GroupDetailResponse flairs(@PathVariable String slug, @RequestHeader(name = "X-User-Id") UUID userId,
            @Valid @RequestBody ReplaceFlairsRequest request) {
        return content.replaceFlairs(slug, userId, request);
    }
    @PutMapping("/{slug}/resources")
    public GroupDetailResponse resources(@PathVariable String slug, @RequestHeader(name = "X-User-Id") UUID userId,
            @Valid @RequestBody ReplaceResourcesRequest request) {
        return content.replaceResources(slug, userId, request);
    }
}
