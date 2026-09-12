package com.navio.communityservice.controller;
import com.navio.communityservice.dto.GroupDetailResponse;
import com.navio.communityservice.media.GroupPictureService;
import com.navio.communityservice.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;
@RestController @RequiredArgsConstructor @RequestMapping("/v1/groups/{slug}/banner")
public class GroupPictureController {
    private final GroupPictureService pictures;
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GroupDetailResponse> upload(@PathVariable String slug, @CurrentUser UUID actor,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pictures.upload(slug, actor, file));
    }
    @DeleteMapping
    public ResponseEntity<Void> remove(@PathVariable String slug, @CurrentUser UUID actor) {
        pictures.remove(slug, actor);
        return ResponseEntity.noContent().build();
    }
    @GetMapping
    public ResponseEntity<byte[]> read(@PathVariable String slug, @CurrentUser UUID actor) {
        var picture = pictures.read(slug, actor);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(picture.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentLength(picture.bytes().length).body(picture.bytes());
    }
}
