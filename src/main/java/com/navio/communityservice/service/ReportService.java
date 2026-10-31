package com.navio.communityservice.service;

import com.navio.communityservice.dto.SocialDtos.CreateReportRequest;
import com.navio.communityservice.dto.SocialDtos.ReportResponse;
import com.navio.communityservice.dto.SocialDtos.ResolveReportRequest;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.repository.CommentRepository;
import com.navio.communityservice.repository.PostRepository;
import com.navio.communityservice.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Reporting and the platform moderation queue.
 *
 * <p>Any signed-in member can report a profile, post or comment once per target
 * while the report is open, up to {@link #DAILY_LIMIT} reports a day. Platform
 * moderators review the queue: dismiss, or action it and optionally remove the
 * reported post or comment. Suspending an account stays in the user management
 * admin, which owns account state.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class ReportService {

    public static final int DAILY_LIMIT = 20;
    private static final Set<String> STATUSES = Set.of("open", "actioned", "dismissed");

    private final ReportRepository reports;
    private final PostRepository posts;
    private final CommentRepository comments;
    private final PostService postService;

    @Transactional
    public ReportResponse report(UUID reporter, CreateReportRequest request) {
        UUID targetUser = switch (request.targetType()) {
            case "user" -> request.targetId();
            case "post" -> posts.find(request.targetId(), reporter)
                    .orElseThrow(() -> new GroupException(HttpStatus.NOT_FOUND, "Post not found")).authorId();
            case "comment" -> comments.findAnyById(request.targetId())
                    .orElseThrow(() -> new GroupException(HttpStatus.NOT_FOUND, "Comment not found")).authorId();
            default -> throw GroupException.invalid("Unknown report target");
        };
        if (reporter.equals(targetUser)) throw GroupException.invalid("You cannot report yourself or your own content");
        if (reports.countLastDay(reporter) >= DAILY_LIMIT) {
            throw new GroupException(HttpStatus.TOO_MANY_REQUESTS, "You have sent many reports today; try again tomorrow");
        }
        String details = request.details() == null || request.details().isBlank() ? null : request.details().strip();
        UUID id = UUID.randomUUID();
        if (!reports.create(id, reporter, request.targetType(), request.targetId(), targetUser, request.reason(), details)) {
            throw GroupException.conflict("You already reported this; our moderators will review it");
        }
        log.info("Report {} filed against a {}", id, request.targetType());
        return reports.find(id).orElseThrow();
    }

    public Page<ReportResponse> queue(String status, int page, int size) {
        if (!STATUSES.contains(status)) throw GroupException.invalid("Unknown report status");
        return reports.queue(status, PostService.pagination(page, size));
    }

    @Transactional
    public ReportResponse resolve(UUID moderator, UUID id, ResolveReportRequest request) {
        var report = reports.find(id).orElseThrow(() -> new GroupException(HttpStatus.NOT_FOUND, "Report not found"));
        if (!"open".equals(report.status())) throw GroupException.conflict("This report was already resolved");
        String note = request.note() == null || request.note().isBlank() ? null : request.note().strip();
        if (request.removeContent()) {
            if (!"actioned".equals(request.status())) throw GroupException.invalid("Removing content actions the report");
            switch (report.targetType()) {
                case "post" -> postService.removeByPlatformModerator(report.targetId());
                case "comment" -> comments.findAnyById(report.targetId())
                        .ifPresent(comment -> comments.softDelete(comment.id()));
                default -> throw GroupException.invalid("Profiles are suspended from the user admin, not removed here");
            }
            reports.resolveOthersForTarget(report.targetType(), report.targetId(), note, moderator);
        }
        if (!reports.resolve(id, request.status(), note, moderator) && !request.removeContent()) {
            throw GroupException.conflict("This report was already resolved");
        }
        log.info("Report {} resolved as {} by a platform moderator", id, request.status());
        return reports.find(id).orElseThrow();
    }
}
