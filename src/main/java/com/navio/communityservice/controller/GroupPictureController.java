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
    public GroupDetailResponse upload(@PathVariable String slug, @CurrentUser UUID actor,
            @RequestPart("file") MultipartFile file) {
        return pictures.upload(slug, actor, file);
    }
    @GetMapping
    public ResponseEntity<byte[]> read(@PathVariable String slug, @CurrentUser UUID actor) {
        var picture = pictures.read(slug, actor);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(picture.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentLength(picture.bytes().length).body(picture.bytes());
    }
}
