package com.navio.communityservice;

import com.navio.communityservice.media.ObjectStorage;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named="COMMUNITY_TEST_DB_URL",matches=".+")
class CommunityLibraryPostgresTests {
 @Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @MockitoBean ObjectStorage storage;
 UUID actor=UUID.randomUUID(),other=UUID.randomUUID(),group=UUID.randomUUID(),post=UUID.randomUUID();
 String root="/v1/social/me/library";
 @BeforeEach void seed(){
  jdbc.update("INSERT INTO social.groups(id,name,slug,description,created_by_user_id,owner_user_id) VALUES(?,?,?,'',?,?)",group,"Library test","library-"+group,other,other);
  jdbc.update("INSERT INTO social.posts(id,group_id,author_id,title,body) VALUES(?,?,?,'Japan art','Museum notes')",post,group,other);
 }
 @AfterEach void cleanup(){jdbc.update("DELETE FROM social.groups WHERE id=?",group);}
 @Test void bookmarksArePrivateIdempotentAndImmediatelyRemovable() throws Exception {
  mvc.perform(get(root)).andExpect(status().isUnauthorized());
  for(int i=0;i<2;i++)mvc.perform(put(root+"/saved/"+post).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isNoContent());
  mvc.perform(get(root).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].post.saved").value(true));
  mvc.perform(get(root).with(jwt().jwt(j->j.subject(other.toString())))).andExpect(jsonPath("$.totalElements").value(0));
  mvc.perform(delete(root+"/saved/"+post).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isNoContent());
  mvc.perform(get(root).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.totalElements").value(0));
 }
 @Test void votesReflectCurrentStateAndCommentsAreOneRowEach() throws Exception {
  jdbc.update("INSERT INTO social.post_votes(post_id,user_id,value) VALUES(?,?,1)",post,actor);
  mvc.perform(get(root).param("view","voted").param("vote","up").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.totalElements").value(1));
  mvc.perform(get(root).param("view","voted").param("vote","down").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.totalElements").value(0));
  jdbc.update("DELETE FROM social.post_votes WHERE user_id=?",actor);
  mvc.perform(get(root).param("view","voted").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.totalElements").value(0));
  for(int i=0;i<2;i++)jdbc.update("INSERT INTO social.comments(id,post_id,author_id,body) VALUES(?,?,?,?)",UUID.randomUUID(),post,actor,"My museum comment "+i);
  jdbc.update("INSERT INTO social.comments(id,post_id,author_id,body,deleted) VALUES(?,?,?,'removed',true)",UUID.randomUUID(),post,actor);
  mvc.perform(get(root).param("view","comments").param("q","museum comment").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.content[0].commentBody").isString());
 }
 @Test void visitsDeduplicateExpireAndHiddenOrBlockedPostsDisappear() throws Exception {
  for(int i=0;i<2;i++)mvc.perform(put(root+"/history/"+post).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isNoContent());
  mvc.perform(get(root).param("view","history").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.totalElements").value(1));
  jdbc.update("UPDATE social.post_visits SET opened_at=now()-interval '31 days' WHERE user_id=?",actor);
  mvc.perform(get(root).param("view","history").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.totalElements").value(0));
  mvc.perform(put(root+"/saved/"+post).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isNoContent());
  jdbc.update("UPDATE social.groups SET status='hidden' WHERE id=?",group);
  mvc.perform(get(root).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.totalElements").value(0));
  mvc.perform(put(root+"/saved/"+post).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isNotFound());
  // Removing a private bookmark remains possible after losing content access.
  mvc.perform(delete(root+"/saved/"+post).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isNoContent());
 }
 @Test void invalidFiltersAreRejectedAndSearchIsLiteral() throws Exception {
  mvc.perform(get(root).param("view","invalid").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isBadRequest());
  mvc.perform(get(root).param("page","-1").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isBadRequest());
  mvc.perform(get(root).param("q","%_").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.totalElements").value(0));
 }
 @Test void blockedAuthorsAreExcludedAndCannotBeSaved() throws Exception {
  mvc.perform(put(root+"/saved/"+post).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isNoContent());
  jdbc.update("INSERT INTO social.user_blocks(blocker_id,blocked_id) VALUES(?,?)",other,actor);
  try {
   mvc.perform(get(root).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.totalElements").value(0));
   mvc.perform(put(root+"/saved/"+post).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isForbidden());
   mvc.perform(put(root+"/history/"+post).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isForbidden());
  } finally {jdbc.update("DELETE FROM social.user_blocks WHERE blocker_id=? AND blocked_id=?",other,actor);}
 }
 @Test void paginationSortingClearAndCascadePreserveIsolation() throws Exception {
  UUID second=UUID.randomUUID();
  jdbc.update("INSERT INTO social.posts(id,group_id,author_id,title,body) VALUES(?,?,?,'Second','text')",second,group,other);
  for(UUID id:List.of(post,second))mvc.perform(put(root+"/saved/"+id).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isNoContent());
  jdbc.update("UPDATE social.saved_posts SET saved_at=now()-interval '1 day' WHERE user_id=? AND post_id=?",actor,post);
  mvc.perform(get(root).param("size","1").param("sort","oldest").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.content[0].post.id").value(post.toString())).andExpect(jsonPath("$.totalPages").value(2));
  mvc.perform(get(root).param("size","1").param("page","1").param("sort","oldest").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.content[0].post.id").value(second.toString()));
  mvc.perform(get(root).param("sort","top").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isOk());
  for(UUID who:List.of(actor,other))mvc.perform(put(root+"/history/"+post).with(jwt().jwt(j->j.subject(who.toString())))).andExpect(status().isNoContent());
  mvc.perform(delete(root+"/history").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(status().isNoContent());
  mvc.perform(get(root).param("view","history").with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.totalElements").value(0));
  mvc.perform(get(root).param("view","history").with(jwt().jwt(j->j.subject(other.toString())))).andExpect(jsonPath("$.totalElements").value(1));
  jdbc.update("DELETE FROM social.posts WHERE id=?",post);
  mvc.perform(get(root).with(jwt().jwt(j->j.subject(actor.toString())))).andExpect(jsonPath("$.totalElements").value(1));
 }
}
