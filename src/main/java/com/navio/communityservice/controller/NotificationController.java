package com.navio.communityservice.controller;

import com.navio.communityservice.dto.SocialDtos.NotificationPreferences;
import com.navio.communityservice.dto.SocialDtos.NotificationResponse;
import com.navio.communityservice.dto.SocialDtos.PlanSharedRequest;
import com.navio.communityservice.dto.SocialDtos.UnreadCountResponse;
import com.navio.communityservice.security.CurrentUser;
import com.navio.communityservice.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** The caller's own in-app notifications. No route can read or change anyone else's. */
@RestController
@RequestMapping("/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notifications;

    @GetMapping
    public ResponseEntity<Page<NotificationResponse>> list(@CurrentUser UUID actor,
            @RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.list(actor, unread, page, size));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount(@CurrentUser UUID actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(notifications.unreadCount(actor));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<UnreadCountResponse> markRead(@CurrentUser UUID actor, @PathVariable UUID id) {
        return ResponseEntity.ok(notifications.markRead(actor, id));
    }

    @PostMapping("/read-all")
    public ResponseEntity<UnreadCountResponse> markAllRead(@CurrentUser UUID actor) {
        return ResponseEntity.ok(notifications.markAllRead(actor));
    }

    @GetMapping("/preferences")
    public ResponseEntity<NotificationPreferences> preferences(@CurrentUser UUID actor) {
        return ResponseEntity.ok(notifications.preferences(actor));
    }

    @PutMapping("/preferences")
    public ResponseEntity<NotificationPreferences> updatePreferences(@CurrentUser UUID actor,
                                                                     @Valid @RequestBody NotificationPreferences request) {
        return ResponseEntity.ok(notifications.updatePreferences(actor, request));
    }

    /** Announces a plan the caller just listed on Explore under their name. */
    @PostMapping("/plan-shared")
    public ResponseEntity<Map<String, Integer>> planShared(@CurrentUser UUID actor,
                                                           @Valid @RequestBody PlanSharedRequest request) {
        return ResponseEntity.ok(Map.of("notified", notifications.planShared(actor, request.token())));
    }
}
