package com.navio.communityservice.controller;

import com.navio.communityservice.dto.*;
import com.navio.communityservice.security.CurrentUser;
import com.navio.communityservice.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

@RestController
@RequestMapping("/v1/posts")
@RequiredArgsConstructor
public class PostController {
    private final PostService posts;

    @GetMapping
    public ResponseEntity<Page<PostResponse>> list(@CurrentUser UUID actor,
            @RequestParam(required = false) String group, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "new") String sort, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(posts.list(group, actor, q, sort, page, size));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PostResponse> read(@PathVariable UUID id, @CurrentUser UUID actor) {
        return ResponseEntity.ok(posts.read(id, actor));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PostResponse> create(@CurrentUser UUID actor, @Valid @RequestBody CreatePostRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(posts.create(actor, request, null));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PostResponse> createWithImage(@CurrentUser UUID actor,
            @Valid @RequestPart("post") CreatePostRequest request, @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(posts.create(actor, request, file));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<PostResponse> update(@PathVariable UUID id, @CurrentUser UUID actor,
            @Valid @RequestBody UpdatePostRequest request) {
        return ResponseEntity.ok(posts.update(id, actor, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @CurrentUser UUID actor) {
        posts.delete(id, actor);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/vote")
    public ResponseEntity<PostResponse> vote(@PathVariable UUID id, @CurrentUser UUID actor, @Valid @RequestBody VoteRequest request) {
        return ResponseEntity.ok(posts.vote(id, actor, request.value()));
    }

    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable UUID id, @CurrentUser UUID actor) {
        var picture = posts.image(id, actor);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(picture.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentLength(picture.bytes().length).body(picture.bytes());
    }
}
