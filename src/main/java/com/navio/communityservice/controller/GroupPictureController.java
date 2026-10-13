package com.navio.communityservice.controller;
import com.navio.communityservice.dto.GroupDetailResponse;
import com.navio.communityservice.media.GroupPictureService;
import com.navio.communityservice.media.GroupPictureSlot;
import com.navio.communityservice.media.PictureValidator;
import com.navio.communityservice.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;
@RestController @RequiredArgsConstructor @RequestMapping("/v1/groups/{slug}")
public class GroupPictureController {
    private final GroupPictureService pictures;
    @PostMapping(path = "/banner", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GroupDetailResponse> uploadBanner(@PathVariable String slug, @CurrentUser UUID actor,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pictures.upload(slug, GroupPictureSlot.BANNER, actor, file));
    }
    @DeleteMapping("/banner")
    public ResponseEntity<Void> removeBanner(@PathVariable String slug, @CurrentUser UUID actor) {
        pictures.remove(slug, GroupPictureSlot.BANNER, actor);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/banner")
    public ResponseEntity<byte[]> readBanner(@PathVariable String slug, @CurrentUser UUID actor) {
        return picture(pictures.read(slug, GroupPictureSlot.BANNER, actor));
    }
    @PostMapping(path = "/icon", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GroupDetailResponse> uploadIcon(@PathVariable String slug, @CurrentUser UUID actor,
            @RequestPart("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pictures.upload(slug, GroupPictureSlot.ICON, actor, file));
    }
    @DeleteMapping("/icon")
    public ResponseEntity<Void> removeIcon(@PathVariable String slug, @CurrentUser UUID actor) {
        pictures.remove(slug, GroupPictureSlot.ICON, actor);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/icon")
    public ResponseEntity<byte[]> readIcon(@PathVariable String slug, @CurrentUser UUID actor) {
        return picture(pictures.read(slug, GroupPictureSlot.ICON, actor));
    }
    private static ResponseEntity<byte[]> picture(PictureValidator.Picture picture) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(picture.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .contentLength(picture.bytes().length).body(picture.bytes());
    }
}
