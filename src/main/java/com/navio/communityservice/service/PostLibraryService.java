package com.navio.communityservice.service;
import com.navio.communityservice.dto.PostLibraryEntry;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.*;
import java.util.*;
@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class PostLibraryService {
 private final PostRepository posts;
 private final PostService reader;
 private final SocialGraphService social;
 public Page<PostLibraryEntry> list(UUID actor,String view,String vote,String query,String sort,int page,int size) {
  if(!Set.of("saved","voted","history","comments").contains(view)||!Set.of("all","up","down").contains(vote)
    ||!Set.of("recent","oldest","top").contains(sort)||query.length()>200||page<0||page>100000||size<1||size>50)
   throw GroupException.invalid("Invalid collection filters or pagination");
  return posts.library(actor,view,vote,query.strip(),sort,PageRequest.of(page,size));
 }
 private void requireReadable(UUID actor,UUID id){var post=reader.read(id,actor);social.requireCanInteract(actor,post.authorId());}
 @Transactional public void save(UUID actor,UUID id){requireReadable(actor,id);posts.savePost(actor,id);}
 @Transactional public void unsave(UUID actor,UUID id){posts.unsavePost(actor,id);}
 @Transactional public void visit(UUID actor,UUID id){requireReadable(actor,id);posts.recordVisit(actor,id);}
 @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${navio.community.history.cleanup-ms:86400000}",initialDelayString="${navio.community.history.cleanup-ms:86400000}")
 @Transactional public void expireHistory(){posts.expireVisits();}
 @Transactional public void clearHistory(UUID actor){posts.clearVisits(actor);}
}
