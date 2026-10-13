package com.navio.communityservice;

import com.navio.communityservice.media.ObjectStorage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "COMMUNITY_TEST_DB_URL", matches = ".+")
class PostPostgresIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ObjectStorage storage;
    final UUID owner = UUID.randomUUID(), member = UUID.randomUUID();
    UUID groupId;
    String slug;
    Map<String, byte[]> objects = new HashMap<>();

    @BeforeEach void group() throws Exception {
        lenient().doAnswer(call -> { objects.put(call.getArgument(0),call.getArgument(1)); return null; }).when(storage).put(anyString(),any(),anyString());
        lenient().when(storage.get(anyString())).thenAnswer(call -> objects.get(call.getArgument(0)));
        lenient().doAnswer(call -> { objects.remove(call.getArgument(0)); return null; }).when(storage).delete(anyString());
        var result = mvc.perform(post("/v1/groups").with(jwt().jwt(j -> j.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("name","Post test " + owner,"description","Trip notes"))))
                .andExpect(status().isCreated()).andReturn();
        var group = json.readTree(result.getResponse().getContentAsString());
        slug = group.get("slug").asText(); groupId = UUID.fromString(group.get("id").asText());
    }

    @AfterEach void cleanup() { if (groupId != null) jdbc.update("DELETE FROM social.groups WHERE id=?",groupId); }

    JsonNode create(String title, boolean image) throws Exception {
        String body = json.writeValueAsString(Map.of("groupSlug",slug,"title",title,"body","Notes <script>alert(1)</script>"));
        var request = image ? multipart("/v1/posts").file(new MockMultipartFile("post","post.json","application/json",body.getBytes()))
                .file(new MockMultipartFile("file","../../exploit.svg","image/svg+xml",picture())) : post("/v1/posts").contentType(MediaType.APPLICATION_JSON).content(body);
        return json.readTree(mvc.perform(request.with(jwt().jwt(j -> j.subject(owner.toString()))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    @Test void postsPicturesVotesCommentsAndDeletionPersistAcrossRequests() throws Exception {
        var post = create("A route",true); String path = "/v1/posts/" + post.get("id").asText();
        assertThat(objects).hasSize(1);
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.title").value("A route"));
        byte[] image = mvc.perform(get(path+"/image")).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(image)).getWidth()).isEqualTo(2);
        for (int vote : List.of(1,1,-1,0)) mvc.perform(put(path+"/vote").with(jwt().jwt(j -> j.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"value\":"+vote+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.upvotes").value(vote)).andExpect(jsonPath("$.viewerVote").value(vote));
        var comment = json.readTree(mvc.perform(post(path+"/comments").with(jwt().jwt(j -> j.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"A question\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String commentId = comment.get("id").asText();
        mvc.perform(post(path+"/comments").with(jwt().jwt(j -> j.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"A reply\",\"parentCommentId\":\""+commentId+"\"}"))
                .andExpect(status().isCreated());
        mvc.perform(get(path)).andExpect(jsonPath("$.commentCount").value(2));
        mvc.perform(patch(path+"/comments/"+commentId).with(jwt().jwt(j -> j.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"A clearer question\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body").value("A clearer question")).andExpect(jsonPath("$.edited").value(true));
        mvc.perform(delete(path+"/comments/"+commentId).with(jwt().jwt(j -> j.subject(owner.toString())))).andExpect(status().isNoContent());
        mvc.perform(get(path+"/comments")).andExpect(jsonPath("$.content[0].deleted").value(true)).andExpect(jsonPath("$.content[0].edited").value(false))
                .andExpect(jsonPath("$.content[1].body").value("A reply"));
        mvc.perform(get(path)).andExpect(jsonPath("$.commentCount").value(1));
        String replyId = json.readTree(mvc.perform(get(path+"/comments")).andReturn().getResponse().getContentAsString())
                .get("content").get(1).get("id").asText();
        mvc.perform(delete(path+"/comments/"+replyId).with(jwt().jwt(j -> j.subject(owner.toString())))).andExpect(status().isNoContent());
        mvc.perform(get(path+"/comments")).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get(path)).andExpect(jsonPath("$.commentCount").value(0));
        mvc.perform(patch(path).with(jwt().jwt(j -> j.subject(owner.toString()))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Renamed\",\"body\":\"Updated\"}")).andExpect(status().isOk());
        mvc.perform(get(path)).andExpect(jsonPath("$.title").value("Renamed"));
        mvc.perform(delete(path).with(jwt().jwt(j -> j.subject(owner.toString())))).andExpect(status().isNoContent());
        mvc.perform(get(path)).andExpect(status().isNotFound());
        assertThat(objects).isEmpty();
        assertThat(jdbc.queryForObject("SELECT post_count FROM social.groups WHERE id=?",Integer.class,groupId)).isZero();
    }

    @Test void nonmembersAndOtherAuthorsCannotMutatePosts() throws Exception {
        String path = "/v1/posts/"+create("Ownership",false).get("id").asText();
        mvc.perform(post("/v1/posts").with(jwt().jwt(j -> j.subject(member.toString()))).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("groupSlug",slug,"title","Blocked","body","")))).andExpect(status().isForbidden());
        mvc.perform(post("/v1/groups/"+slug+"/members/me").with(jwt().jwt(j -> j.subject(member.toString())))).andExpect(status().isOk());
        mvc.perform(patch(path).with(jwt().jwt(j -> j.subject(member.toString()))).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Stolen\",\"body\":\"\"}")).andExpect(status().isForbidden());
        mvc.perform(delete(path).with(jwt().jwt(j -> j.subject(member.toString())))).andExpect(status().isForbidden());
    }

    @Test void searchPaginationAndHiddenImagesRespectGroupVisibility() throws Exception {
        String path = "/v1/posts/"+create("Literal % route",true).get("id").asText();
        create("Other route",false);
        mvc.perform(get("/v1/posts").param("group",slug).param("q","%"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/v1/posts").param("group",slug).param("size","1"))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.last").value(false));
        jdbc.update("UPDATE social.groups SET status='hidden' WHERE id=?",groupId);
        mvc.perform(get(path+"/image")).andExpect(status().isNotFound());
        mvc.perform(get("/v1/posts").param("group",slug)).andExpect(status().isNotFound());
        mvc.perform(get(path+"/image").with(jwt().jwt(j -> j.subject(owner.toString())))).andExpect(status().isOk());
    }

    @Test void repeatingTheSamePublishDoesNotDuplicateThePostOrUpload() throws Exception {
        UUID requestId = UUID.randomUUID();
        String body = json.writeValueAsString(Map.of("requestId", requestId, "groupSlug", slug, "title", "Retry", "body", "Notes"));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(multipart("/v1/posts")
                    .file(new MockMultipartFile("post", "post.json", "application/json", body.getBytes()))
                    .file(new MockMultipartFile("file", "banner.png", "image/png", picture()))
                    .with(jwt().jwt(j -> j.subject(owner.toString()))))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(requestId.toString()));
        }
        assertThat(objects).hasSize(1);
        verify(storage, times(1)).put(anyString(), any(), anyString());
        assertThat(jdbc.queryForObject("SELECT post_count FROM social.groups WHERE id=?", Integer.class, groupId)).isEqualTo(1);
        mvc.perform(post("/v1/posts").with(jwt().jwt(j -> j.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("Retry", "Changed")))
                .andExpect(status().isConflict());
    }

    private byte[] picture() throws IOException {
        var out = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",out); return out.toByteArray();
    }

    @Test void bannersAreValidatedReplacedAndRemovedWithTheirStoredObjects() throws Exception {
        String path="/v1/groups/"+slug+"/banner";
        mvc.perform(multipart(path).file(new MockMultipartFile("file","banner.png","image/png",picture()))
                .with(jwt().jwt(j -> j.subject(member.toString())))).andExpect(status().isForbidden());
        assertThat(objects).isEmpty();
        for (int attempt=0;attempt<2;attempt++) {
            mvc.perform(multipart(path).file(new MockMultipartFile("file","banner.png","image/png",picture()))
                    .with(jwt().jwt(j -> j.subject(owner.toString())))).andExpect(status().isCreated());
            assertThat(objects).hasSize(1);
            mvc.perform(get(path)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        }
        mvc.perform(delete(path).with(jwt().jwt(j -> j.subject(owner.toString())))).andExpect(status().isNoContent());
        assertThat(objects).isEmpty();
        mvc.perform(get(path)).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM media.group_media WHERE group_id=?",Integer.class,groupId)).isZero();
    }

    @Test void iconsLiveBesideTheBannerAndAppearOnListsAndPosts() throws Exception {
        String icon="/v1/groups/"+slug+"/icon", banner="/v1/groups/"+slug+"/banner";
        mvc.perform(multipart(icon).file(new MockMultipartFile("file","icon.png","image/png",picture()))
                .with(jwt().jwt(j -> j.subject(member.toString())))).andExpect(status().isForbidden());
        var detail = json.readTree(mvc.perform(multipart(icon).file(new MockMultipartFile("file","icon.png","image/png",picture()))
                .with(jwt().jwt(j -> j.subject(owner.toString())))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String iconId = detail.get("iconMediaId").asText();
        assertThat(detail.get("bannerMediaId").isNull()).isTrue();
        mvc.perform(multipart(banner).file(new MockMultipartFile("file","banner.png","image/png",picture()))
                .with(jwt().jwt(j -> j.subject(owner.toString())))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.iconMediaId").value(iconId));
        assertThat(objects).hasSize(2);
        mvc.perform(get(icon)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/v1/groups")).andExpect(jsonPath("$.content[?(@.slug=='"+slug+"')].iconMediaId").value(iconId));
        mvc.perform(get("/v1/groups/search").param("q",owner.toString()))
                .andExpect(jsonPath("$.content[?(@.slug=='"+slug+"')].iconMediaId").value(iconId));
        mvc.perform(get("/v1/groups/mine").with(jwt().jwt(j -> j.subject(owner.toString()))))
                .andExpect(jsonPath("$.content[?(@.slug=='"+slug+"')].bannerMediaId").isNotEmpty());
        mvc.perform(get("/v1/posts/"+create("With icon",false).get("id").asText())).andExpect(jsonPath("$.groupIconMediaId").value(iconId));
        mvc.perform(delete(icon).with(jwt().jwt(j -> j.subject(owner.toString())))).andExpect(status().isNoContent());
        mvc.perform(get(icon)).andExpect(status().isNotFound());
        mvc.perform(get(banner)).andExpect(status().isOk());
        assertThat(objects).hasSize(1);
    }

    @Test void reactionsAreCountedPerMemberAndClearedWhenTheCommentIsRemoved() throws Exception {
        String path = "/v1/posts/"+create("Reactions",false).get("id").asText();
        mvc.perform(post("/v1/groups/"+slug+"/members/me").with(jwt().jwt(j -> j.subject(member.toString())))).andExpect(status().isOk());
        String commentId = json.readTree(mvc.perform(post(path+"/comments").with(jwt().jwt(j -> j.subject(owner.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Try the night market\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(post(path+"/comments").with(jwt().jwt(j -> j.subject(member.toString())))
                .contentType(MediaType.APPLICATION_JSON).content("{\"body\":\"Thanks!\",\"parentCommentId\":\""+commentId+"\"}"))
                .andExpect(status().isCreated());
        String reactions = path+"/comments/"+commentId+"/reactions/";
        for (var actor : List.of(owner, member, member)) {
            mvc.perform(put(reactions+"want_to_go").with(jwt().jwt(j -> j.subject(actor.toString())))).andExpect(status().isOk());
        }
        mvc.perform(put(reactions+"helpful").with(jwt().jwt(j -> j.subject(member.toString()))))
                .andExpect(jsonPath("$.reactions[0].reaction").value("helpful")).andExpect(jsonPath("$.reactions[0].count").value(1))
                .andExpect(jsonPath("$.reactions[1].reaction").value("want_to_go")).andExpect(jsonPath("$.reactions[1].count").value(2))
                .andExpect(jsonPath("$.reactions[1].reacted").value(true));
        mvc.perform(get(path+"/comments")).andExpect(jsonPath("$.content[0].reactions[1].count").value(2))
                .andExpect(jsonPath("$.content[0].reactions[1].reacted").value(false));
        mvc.perform(put(reactions+"angry").with(jwt().jwt(j -> j.subject(member.toString())))).andExpect(status().isBadRequest());
        mvc.perform(put(reactions+"love").with(jwt().jwt(j -> j.subject(UUID.randomUUID().toString())))).andExpect(status().isForbidden());
        mvc.perform(delete(reactions+"want_to_go").with(jwt().jwt(j -> j.subject(member.toString())))).andExpect(status().isNoContent());
        mvc.perform(get(path+"/comments").with(jwt().jwt(j -> j.subject(member.toString()))))
                .andExpect(jsonPath("$.content[0].reactions[1].count").value(1)).andExpect(jsonPath("$.content[0].reactions[1].reacted").value(false));
        // Deleting a comment with replies keeps a placeholder, which must not keep its reactions.
        mvc.perform(delete(path+"/comments/"+commentId).with(jwt().jwt(j -> j.subject(owner.toString())))).andExpect(status().isNoContent());
        mvc.perform(get(path+"/comments")).andExpect(jsonPath("$.content[0].deleted").value(true))
                .andExpect(jsonPath("$.content[0].reactions").isEmpty());
        mvc.perform(put(reactions+"love").with(jwt().jwt(j -> j.subject(member.toString())))).andExpect(status().isBadRequest());
    }

    @Test void theHomeFeedShowsOnlyJoinedCommunitiesAndBestFavoursRecentActivity() throws Exception {
        create("Fresh question",false);
        var old = create("Old favourite",false).get("id").asText();
        jdbc.update("UPDATE social.posts SET created_at=now() - interval '30 days' WHERE id=?::uuid",old);
        jdbc.update("INSERT INTO social.post_votes(post_id,user_id,value) VALUES(?::uuid,?,1),(?::uuid,?,1)",old,UUID.randomUUID(),old,UUID.randomUUID());
        mvc.perform(get("/v1/posts").param("joined","true").with(jwt().jwt(j -> j.subject(member.toString()))))
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/v1/posts").param("joined","true")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/v1/posts").param("joined","true").param("sort","best").with(jwt().jwt(j -> j.subject(owner.toString()))))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.content[0].title").value("Fresh question"));
        mvc.perform(get("/v1/posts").param("group",slug).param("sort","top"))
                .andExpect(jsonPath("$.content[0].title").value("Old favourite"));
    }
}
