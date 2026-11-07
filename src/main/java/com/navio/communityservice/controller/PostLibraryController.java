package com.navio.communityservice.controller;
import com.navio.communityservice.dto.PostLibraryEntry;
import com.navio.communityservice.security.CurrentUser;
import com.navio.communityservice.service.PostLibraryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import java.util.UUID;
@RestController @RequestMapping("/v1/social/me/library") @RequiredArgsConstructor
public class PostLibraryController {
 private final PostLibraryService service;
 @GetMapping public Page<PostLibraryEntry> list(@CurrentUser UUID actor,@RequestParam(defaultValue="saved") String view,
  @RequestParam(defaultValue="all") String vote,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="recent") String sort,
  @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return service.list(actor,view,vote,q,sort,page,size);}
 @PutMapping("/saved/{id}") public ResponseEntity<Void> save(@CurrentUser UUID actor,@PathVariable UUID id){service.save(actor,id);return ResponseEntity.noContent().build();}
 @DeleteMapping("/saved/{id}") public ResponseEntity<Void> unsave(@CurrentUser UUID actor,@PathVariable UUID id){service.unsave(actor,id);return ResponseEntity.noContent().build();}
 @PutMapping("/history/{id}") public ResponseEntity<Void> visit(@CurrentUser UUID actor,@PathVariable UUID id){service.visit(actor,id);return ResponseEntity.noContent().build();}
 @DeleteMapping("/history") public ResponseEntity<Void> clear(@CurrentUser UUID actor){service.clearHistory(actor);return ResponseEntity.noContent().build();}
}
