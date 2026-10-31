package com.navio.communityservice.controller;

import com.navio.communityservice.dto.SocialDtos.CreateReportRequest;
import com.navio.communityservice.dto.SocialDtos.ReportResponse;
import com.navio.communityservice.dto.SocialDtos.ResolveReportRequest;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.security.CurrentUser;
import com.navio.communityservice.security.PlatformModerator;
import com.navio.communityservice.service.ReportService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Filing reports (any signed-in member) and reviewing them (platform moderators,
 * admins and owners, from the validated token's Keycloak roles).
 */
@RestController
public class ReportController {

    private final ReportService reports;
    private final List<String> audiences;

    public ReportController(ReportService reports,
                            @Value("${navio.security.keycloak.audiences}") List<String> audiences) {
        this.reports = reports;
        this.audiences = List.copyOf(audiences);
    }

    @PostMapping("/v1/reports")
    public ResponseEntity<ReportResponse> report(@CurrentUser UUID actor, @Valid @RequestBody CreateReportRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(reports.report(actor, request));
    }

    @GetMapping("/v1/admin/reports")
    public ResponseEntity<Page<ReportResponse>> queue(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "open") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        requireModerator(jwt);
        return ResponseEntity.ok(reports.queue(status, page, size));
    }

    @PatchMapping("/v1/admin/reports/{id}")
    public ResponseEntity<ReportResponse> resolve(@AuthenticationPrincipal Jwt jwt, @CurrentUser UUID actor,
            @PathVariable UUID id, @Valid @RequestBody ResolveReportRequest request) {
        requireModerator(jwt);
        return ResponseEntity.ok(reports.resolve(actor, id, request));
    }

    private void requireModerator(Jwt jwt) {
        if (!PlatformModerator.is(jwt, audiences)) throw GroupException.forbidden("Only platform moderators can review reports");
    }
}
