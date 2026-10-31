package com.navio.communityservice.controller;

import com.navio.communityservice.security.CurrentUser;
import com.navio.communityservice.service.NotificationStreamHub;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * The caller's live inbox hints. The recipient is always the validated token's
 * subject; there is no parameter that could name anyone else.
 */
@RestController
@RequestMapping("/v1/notifications")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "navio.notifications.stream.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationStreamController {

    private final NotificationStreamHub hub;

    /**
     * A refused stream answers with a bare status rather than a JSON error body:
     * an EventSource cannot read a body, and a JSON body is not acceptable to a
     * caller that asked only for {@code text/event-stream}.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@CurrentUser UUID actor, @AuthenticationPrincipal Jwt jwt) {
        try {
            SseEmitter emitter = hub.connect(actor, jwt == null ? null : jwt.getExpiresAt());
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .header("X-Accel-Buffering", "no")
                    .body(emitter);
        } catch (NotificationStreamHub.StreamCapacityException busy) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "30").build();
        }
    }
}
