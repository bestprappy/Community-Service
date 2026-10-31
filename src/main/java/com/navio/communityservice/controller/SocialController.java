package com.navio.communityservice.controller;

import com.navio.communityservice.dto.PostResponse;
import com.navio.communityservice.dto.SocialDtos.BlockedUserResponse;
import com.navio.communityservice.dto.SocialDtos.ConnectionResponse;
import com.navio.communityservice.dto.SocialDtos.FollowAlertsRequest;
import com.navio.communityservice.dto.SocialDtos.SocialSettingsRequest;
import com.navio.communityservice.dto.SocialDtos.SocialSettingsResponse;
import com.navio.communityservice.dto.SocialDtos.SocialSummaryResponse;
import com.navio.communityservice.security.CurrentUser;
import com.navio.communityservice.service.SocialGraphService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Following, blocking and public profile content. Every route requires a signed-in
 * caller (the security config's default), and every write acts as that caller.
 */
@RestController
@RequestMapping("/v1/social")
@RequiredArgsConstructor
public class SocialController {

    private final SocialGraphService social;

    @GetMapping("/users/{accountId}/summary")
    public ResponseEntity<SocialSummaryResponse> summary(@PathVariable UUID accountId, @CurrentUser UUID actor) {
        return ResponseEntity.ok(social.summary(accountId, actor));
    }

    @PutMapping("/users/{accountId}/follow")
    public ResponseEntity<SocialSummaryResponse> follow(@PathVariable UUID accountId, @CurrentUser UUID actor) {
        return ResponseEntity.ok(social.follow(actor, accountId));
    }

    @DeleteMapping("/users/{accountId}/follow")
    public ResponseEntity<Void> unfollow(@PathVariable UUID accountId, @CurrentUser UUID actor) {
        social.unfollow(actor, accountId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{accountId}/alerts")
    public ResponseEntity<SocialSummaryResponse> alerts(@PathVariable UUID accountId, @CurrentUser UUID actor,
                                                        @Valid @RequestBody FollowAlertsRequest request) {
        return ResponseEntity.ok(social.setAlerts(actor, accountId, request.alerts()));
    }

    @PutMapping("/users/{accountId}/block")
    public ResponseEntity<SocialSummaryResponse> block(@PathVariable UUID accountId, @CurrentUser UUID actor) {
        return ResponseEntity.ok(social.block(actor, accountId));
    }

    @DeleteMapping("/users/{accountId}/block")
    public ResponseEntity<Void> unblock(@PathVariable UUID accountId, @CurrentUser UUID actor) {
        social.unblock(actor, accountId);
        return ResponseEntity.noContent().build();
    }

    /** {@code within} narrows the list to accounts a people search returned (at most 100). */
    @GetMapping("/users/{accountId}/followers")
    public ResponseEntity<Page<ConnectionResponse>> followers(@PathVariable UUID accountId, @CurrentUser UUID actor,
            @RequestParam(required = false) List<UUID> within,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(social.followers(accountId, actor, within, page, size));
    }

    @GetMapping("/users/{accountId}/following")
    public ResponseEntity<Page<ConnectionResponse>> following(@PathVariable UUID accountId, @CurrentUser UUID actor,
            @RequestParam(required = false) List<UUID> within,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(social.following(accountId, actor, within, page, size));
    }

    @GetMapping("/users/{accountId}/posts")
    public ResponseEntity<Page<PostResponse>> posts(@PathVariable UUID accountId, @CurrentUser UUID actor,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(social.profilePosts(accountId, actor, page, size));
    }

    @GetMapping("/blocks")
    public ResponseEntity<Page<BlockedUserResponse>> blocked(@CurrentUser UUID actor,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(social.blocked(actor, page, size));
    }

    @GetMapping("/settings")
    public ResponseEntity<SocialSettingsResponse> settings(@CurrentUser UUID actor) {
        return ResponseEntity.ok(social.settings(actor));
    }

    @PutMapping("/settings")
    public ResponseEntity<SocialSettingsResponse> updateSettings(@CurrentUser UUID actor,
                                                                 @Valid @RequestBody SocialSettingsRequest request) {
        return ResponseEntity.ok(social.updateSettings(actor, request.connectionsVisibility()));
    }
}
