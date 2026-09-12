package com.navio.communityservice.controller;

import com.navio.communityservice.dto.*;
import com.navio.communityservice.security.CurrentUser;
import com.navio.communityservice.service.CommentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/v1/posts/{postId}/comments")
@RequiredArgsConstructor
public class CommentController {
    private final CommentService comments;

    @GetMapping
    public ResponseEntity<Page<CommentResponse>> list(@PathVariable UUID postId, @CurrentUser UUID actor,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(comments.list(postId, actor, page, size));
    }

    @PostMapping
    public ResponseEntity<CommentResponse> create(@PathVariable UUID postId, @CurrentUser UUID actor,
            @Valid @RequestBody CreateCommentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(comments.create(postId, actor, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID postId, @PathVariable UUID id, @CurrentUser UUID actor) {
        comments.delete(postId, id, actor);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/vote")
    public ResponseEntity<CommentResponse> vote(@PathVariable UUID postId, @PathVariable UUID id,
            @CurrentUser UUID actor, @Valid @RequestBody VoteRequest request) {
        return ResponseEntity.ok(comments.vote(postId, id, actor, request.value()));
    }
}
