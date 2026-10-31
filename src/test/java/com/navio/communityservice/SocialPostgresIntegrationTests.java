package com.navio.communityservice;

import com.navio.communityservice.integration.SharedPlanDirectory;
import com.navio.communityservice.integration.SharedPlanDirectory.ListedPlan;
import com.navio.communityservice.media.ObjectStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Following, blocking, profile posts and notifications through the real HTTP
 * stack against PostgreSQL, because the fan-out and visibility rules live in SQL.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "COMMUNITY_TEST_DB_URL", matches = ".+")
class SocialPostgresIntegrationTests {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ObjectStorage storage;
    @MockitoBean SharedPlanDirectory sharedPlans;

    final UUID author = UUID.randomUUID();
    final UUID fan = UUID.randomUUID();
    final UUID quiet = UUID.randomUUID();
    UUID groupId;
    String slug;

    @BeforeEach
    void group() throws Exception {
        var created = json.readTree(mvc.perform(as(post("/v1/groups"), author).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "Social test " + author, "description", "Trips"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        slug = created.get("slug").asText();
        groupId = UUID.fromString(created.get("id").asText());
    }

    @AfterEach
    void cleanup() {
        if (groupId != null) jdbc.update("DELETE FROM social.groups WHERE id=?", groupId);
        for (UUID user : List.of(author, fan, quiet)) {
            jdbc.update("DELETE FROM social.user_follows WHERE follower_id=? OR followee_id=?", user, user);
            jdbc.update("DELETE FROM social.user_blocks WHERE blocker_id=? OR blocked_id=?", user, user);
            jdbc.update("DELETE FROM notif.notifications WHERE recipient_id=? OR actor_id=?", user, user);
            jdbc.update("DELETE FROM notif.preferences WHERE user_id=?", user);
            jdbc.update("DELETE FROM social.social_settings WHERE user_id=?", user);
            jdbc.update("DELETE FROM social.reports WHERE reporter_id=?", user);
        }
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, UUID user) {
        return request.with(jwt().jwt(token -> token.subject(user.toString())));
    }

    private JsonNode read(MockHttpServletRequestBuilder request, UUID user) throws Exception {
        return json.readTree(mvc.perform(as(request, user)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String publish(String title) throws Exception {
        return json.readTree(mvc.perform(as(post("/v1/posts"), author).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("groupSlug", slug, "title", title, "body", "Notes"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void followingNotifiesOnceAndNewPostsReachOnlyFollowersWithAlertsOn() throws Exception {
        mvc.perform(as(put("/v1/social/users/" + author + "/follow"), fan)).andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerFollows").value(true)).andExpect(jsonPath("$.followerCount").value(1));
        // Unfollow and re-follow: still a single new-follower notification.
        mvc.perform(as(delete("/v1/social/users/" + author + "/follow"), fan)).andExpect(status().isNoContent());
        mvc.perform(as(put("/v1/social/users/" + author + "/follow"), fan)).andExpect(status().isOk());
        mvc.perform(as(put("/v1/social/users/" + author + "/follow"), quiet)).andExpect(status().isOk());
        mvc.perform(as(put("/v1/social/users/" + author + "/alerts"), quiet).contentType(MediaType.APPLICATION_JSON)
                .content("{\"alerts\":false}")).andExpect(status().isOk()).andExpect(jsonPath("$.viewerNotify").value(false));

        var authorInbox = read(get("/v1/notifications"), author);
        assertThat(authorInbox.get("content")).hasSize(2);
        assertThat(authorInbox.get("content").get(0).get("type").asText()).isEqualTo("new_follower");

        String postId = publish("First light");
        var fanInbox = read(get("/v1/notifications"), fan).get("content");
        assertThat(fanInbox).hasSize(1);
        assertThat(fanInbox.get(0).get("postId").asText()).isEqualTo(postId);
        assertThat(fanInbox.get(0).get("postTitle").asText()).isEqualTo("First light");
        assertThat(read(get("/v1/notifications"), quiet).get("content")).isEmpty();
        assertThat(read(get("/v1/notifications/unread-count"), fan).get("unreadCount").asLong()).isEqualTo(1);

        // Deleting the post removes its notification.
        mvc.perform(as(delete("/v1/posts/" + postId), author)).andExpect(status().isNoContent());
        assertThat(read(get("/v1/notifications/unread-count"), fan).get("unreadCount").asLong()).isZero();
    }

    @Test
    void aBurstOfPostsIsCappedPerSenderAndTypePreferencesAreHonoured() throws Exception {
        mvc.perform(as(put("/v1/social/users/" + author + "/follow"), fan)).andExpect(status().isOk());
        for (int index = 0; index < 5; index++) publish("Post " + index);
        assertThat(read(get("/v1/notifications"), fan).get("totalElements").asLong()).isEqualTo(3);

        mvc.perform(as(put("/v1/notifications/preferences"), author).contentType(MediaType.APPLICATION_JSON)
                .content("{\"newFollower\":false,\"followedPost\":true,\"followedPlan\":true}")).andExpect(status().isOk());
        mvc.perform(as(put("/v1/social/users/" + author + "/follow"), quiet)).andExpect(status().isOk());
        assertThat(read(get("/v1/notifications"), author).get("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void markingReadIsScopedToTheRecipient() throws Exception {
        mvc.perform(as(put("/v1/social/users/" + author + "/follow"), fan)).andExpect(status().isOk());
        String id = read(get("/v1/notifications"), author).get("content").get(0).get("id").asText();
        mvc.perform(as(post("/v1/notifications/" + id + "/read"), fan)).andExpect(status().isNotFound());
        mvc.perform(as(post("/v1/notifications/" + id + "/read"), author)).andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
        mvc.perform(as(put("/v1/social/users/" + author + "/follow"), quiet)).andExpect(status().isOk());
        mvc.perform(as(post("/v1/notifications/read-all"), author)).andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));
    }

    @Test
    void blockingSeversFollowsHidesContentAndStopsInteraction() throws Exception {
        mvc.perform(as(put("/v1/social/users/" + author + "/follow"), fan)).andExpect(status().isOk());
        mvc.perform(as(post("/v1/groups/" + slug + "/members/me"), fan)).andExpect(status().is2xxSuccessful());
        String postId = publish("Before the block");

        mvc.perform(as(put("/v1/social/users/" + fan + "/block"), author)).andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerBlocked").value(true)).andExpect(jsonPath("$.followerCount").value(0));

        assertThat(read(get("/v1/notifications"), fan).get("content")).isEmpty();
        assertThat(read(get("/v1/notifications"), author).get("content")).isEmpty();
        mvc.perform(as(put("/v1/social/users/" + author + "/follow"), fan)).andExpect(status().isForbidden());
        mvc.perform(as(get("/v1/social/users/" + author + "/posts"), fan)).andExpect(status().isForbidden());
        mvc.perform(as(post("/v1/posts/" + postId + "/comments"), fan).contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Hello\"}")).andExpect(status().isForbidden());
        mvc.perform(as(put("/v1/posts/" + postId + "/vote"), fan).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":1}")).andExpect(status().isForbidden());
        var feed = read(get("/v1/posts").param("group", slug), fan);
        assertThat(feed.get("content")).isEmpty();
        assertThat(read(get("/v1/social/users/" + author + "/summary"), fan).get("blockedViewer").asBoolean()).isTrue();

        mvc.perform(as(delete("/v1/social/users/" + fan + "/block"), author)).andExpect(status().isNoContent());
        mvc.perform(as(put("/v1/social/users/" + author + "/follow"), fan)).andExpect(status().isOk());
    }

    @Test
    void connectionListsFollowTheOwnersVisibilityAndProfilesShowPinsFirst() throws Exception {
        mvc.perform(as(put("/v1/social/users/" + author + "/follow"), fan)).andExpect(status().isOk());
        mvc.perform(as(put("/v1/social/settings"), author).contentType(MediaType.APPLICATION_JSON)
                .content("{\"connectionsVisibility\":\"followers\"}")).andExpect(status().isOk());
        mvc.perform(as(get("/v1/social/users/" + author + "/followers"), quiet)).andExpect(status().isForbidden());
        var followers = read(get("/v1/social/users/" + author + "/followers"), fan).get("content");
        assertThat(followers.get(0).get("accountId").asText()).isEqualTo(fan.toString());
        assertThat(read(get("/v1/social/users/" + author + "/followers").param("within", quiet.toString()), fan)
                .get("content")).isEmpty();

        String older = publish("Older");
        publish("Newer");
        mvc.perform(as(put("/v1/posts/" + older + "/pin"), fan)).andExpect(status().isForbidden());
        mvc.perform(as(put("/v1/posts/" + older + "/pin"), author)).andExpect(status().isOk())
                .andExpect(jsonPath("$.pinnedOnProfile").value(true));
        var posts = read(get("/v1/social/users/" + author + "/posts"), fan).get("content");
        assertThat(posts.get(0).get("id").asText()).isEqualTo(older);
        assertThat(posts.get(1).get("title").asText()).isEqualTo("Newer");
    }

    @Test
    void planAnnouncementsNeedAnOwnedListingAndDisappearWhenTheyAreUnshared() throws Exception {
        mvc.perform(as(put("/v1/social/users/" + author + "/follow"), fan)).andExpect(status().isOk());
        when(sharedPlans.listedByAuthor(author)).thenReturn(List.of(new ListedPlan("tokenA", "Kyoto in autumn")));
        mvc.perform(as(post("/v1/notifications/plan-shared"), author).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"someoneElse\"}")).andExpect(status().isNotFound());
        mvc.perform(as(post("/v1/notifications/plan-shared"), author).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"tokenA\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.notified").value(1));
        mvc.perform(as(post("/v1/notifications/plan-shared"), author).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"tokenA\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.notified").value(0));

        var notification = read(get("/v1/notifications"), fan).get("content").get(0);
        assertThat(notification.get("planTitle").asText()).isEqualTo("Kyoto in autumn");
        assertThat(notification.get("available").asBoolean()).isTrue();

        when(sharedPlans.listedByAuthor(author)).thenReturn(List.of());
        var unshared = read(get("/v1/notifications"), fan).get("content").get(0);
        assertThat(unshared.get("available").asBoolean()).isFalse();
        assertThat(unshared.get("planToken").isNull()).isTrue();
        assertThat(unshared.get("planTitle").isNull()).isTrue();
    }

    @Test
    void reportsQueueOncePerTargetAndOnlyPlatformModeratorsCanReview() throws Exception {
        String postId = publish("Reported");
        String body = "{\"targetType\":\"post\",\"targetId\":\"" + postId + "\",\"reason\":\"spam\"}";
        String reportId = json.readTree(mvc.perform(as(post("/v1/reports"), fan).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id").asText();
        mvc.perform(as(post("/v1/reports"), fan).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        mvc.perform(as(post("/v1/reports"), author).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());

        mvc.perform(as(get("/v1/admin/reports"), quiet)).andExpect(status().isForbidden());
        var moderator = jwt().jwt(token -> token.subject(quiet.toString()).claim("realm_access", Map.of("roles", List.of("MODERATOR"))));
        mvc.perform(get("/v1/admin/reports").with(moderator)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id=='" + reportId + "')].targetPreview").value("Reported"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/v1/admin/reports/" + reportId)
                        .with(moderator).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"actioned\",\"note\":\"Spam link\",\"removeContent\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("actioned"))
                .andExpect(jsonPath("$.targetAvailable").value(false));
        mvc.perform(get("/v1/posts/" + postId)).andExpect(status().isNotFound());
    }
}
