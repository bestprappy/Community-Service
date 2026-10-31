package com.navio.communityservice.controller;

import com.navio.communityservice.dto.NotificationEventDtos.QuarantinedEventResponse;
import com.navio.communityservice.dto.NotificationEventDtos.ReplayResponse;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.security.CurrentUser;
import com.navio.communityservice.security.PlatformModerator;
import com.navio.communityservice.service.QuarantinedEventService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Quarantined notification events, for platform administrators (ADMIN or OWNER
 * from the validated token). Moderators review content, not infrastructure.
 */
@RestController
@RequestMapping("/v1/admin/notification-events")
public class NotificationEventAdminController {

    private final QuarantinedEventService events;
    private final List<String> audiences;

    public NotificationEventAdminController(QuarantinedEventService events,
                                            @Value("${navio.security.keycloak.audiences}") List<String> audiences) {
        this.events = events;
        this.audiences = List.copyOf(audiences);
    }

    @GetMapping
    public ResponseEntity<Page<QuarantinedEventResponse>> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "true") boolean open,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        requireAdministrator(jwt);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(events.list(open, page, size));
    }

    @PostMapping("/{id}/replay")
    public ResponseEntity<ReplayResponse> replay(@AuthenticationPrincipal Jwt jwt, @CurrentUser UUID actor,
                                                 @PathVariable UUID id) {
        requireAdministrator(jwt);
        return ResponseEntity.ok(events.replay(id, actor));
    }

    private void requireAdministrator(Jwt jwt) {
        if (!PlatformModerator.isAdministrator(jwt, audiences)) {
            throw GroupException.forbidden("Only platform administrators can manage notification events");
        }
    }
}
