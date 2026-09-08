package com.navio.communityservice;

import com.navio.communityservice.dto.CreateGroupRequest;
import com.navio.communityservice.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses an explicitly supplied disposable PostgreSQL database; no H2 substitutions or extra dependencies. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@EnabledIfEnvironmentVariable(named = "COMMUNITY_TEST_DB_URL", matches = ".+")
class GroupPostgresIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired GroupService groups;
    @Autowired GroupMembershipService membershipService;
    @Autowired GroupModerationService moderationService;
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    String path;
    UUID groupId;
    @BeforeEach void createGroup() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"name\":\"  Thailand EV Charging " + suffix + "  \",\"description\":\"EV charging tips\","
                + "\"country\":\"Thailand\",\"places\":[\" Bangkok \",\"\",\"Bangkok\"],\"tags\":[\"ev\"]}";
        var result = mvc.perform(post("/v1/groups").header("X-User-Id", a).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.memberCount").value(1))
                .andExpect(jsonPath("$.role").value("admin")).andExpect(jsonPath("$.joined").value(true))
                .andExpect(jsonPath("$.moderatorIds[0]").value(a.toString())).andExpect(jsonPath("$.places.length()").value(1))
                .andReturn();
        JsonNode created = json.readTree(result.getResponse().getContentAsString());
        groupId = UUID.fromString(created.get("id").asText());
        path = "/v1/groups/" + created.get("slug").asText();
    }
    @AfterEach void removeOnlyThisTestsGroup() {
        if (groupId != null) jdbc.update("delete from social.groups where id = ?", groupId);
    }
    String moderators(UUID... ids) throws Exception {
        return json.writeValueAsString(Map.of("userIds", ids));
    }
    void join(UUID user) throws Exception {
        mvc.perform(post(path + "/members/me").header("X-User-Id", user)).andExpect(status().isOk());
    }
    @Test void requestedWorkflowEndToEnd() throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk()).andExpect(jsonPath("$.joined").value(false))
                .andExpect(jsonPath("$.muted").value(false)).andExpect(jsonPath("$.role").isEmpty());
        join(b); join(b);
        mvc.perform(put(path + "/moderators").header("X-User-Id", a).contentType(MediaType.APPLICATION_JSON).content(moderators(a,b,b)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.moderatorIds.length()").value(2))
                .andExpect(jsonPath("$.memberCount").value(2)).andExpect(jsonPath("$.role").value("admin"));
        mvc.perform(put(path + "/moderators").header("X-User-Id", a).contentType(MediaType.APPLICATION_JSON).content(moderators(b)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value("member"))
                .andExpect(jsonPath("$.moderatorIds[0]").value(b.toString()));
        mvc.perform(put(path + "/moderators").header("X-User-Id", b).contentType(MediaType.APPLICATION_JSON).content(moderators(b,c)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from social.group_memberships where group_id=? and user_id=?", Integer.class, groupId,c)).isZero();
        mvc.perform(patch(path + "/members/me").header("X-User-Id", b).contentType(MediaType.APPLICATION_JSON).content("{\"muted\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.muted").value(true)).andExpect(jsonPath("$.joined").value(true))
                .andExpect(jsonPath("$.memberCount").value(2));
        var before = json.readTree(mvc.perform(get(path)).andReturn().getResponse().getContentAsString());
        var after = json.readTree(mvc.perform(patch(path + "/profile").header("X-User-Id", b).contentType(MediaType.APPLICATION_JSON)
                .content("{\"bannerUrl\":\"https://example.com/banner.jpg\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (String field : List.of("name", "slug", "description", "summary")) assertThat(after.get(field)).isEqualTo(before.get(field));
        mvc.perform(delete(path + "/members/me").header("X-User-Id", b)).andExpect(status().isConflict());
        mvc.perform(get("/v1/groups/search").param("q", "ev charging")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '"+groupId+"')]").isNotEmpty());
        mvc.perform(get("/v1/groups/search").param("q", "")).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        for (UUID user : List.of(a,b)) mvc.perform(get("/v1/groups/mine").header("X-User-Id", user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(groupId.toString())).andExpect(jsonPath("$.content[0].joined").value(true));
        assertThat(jdbc.queryForObject("select member_count from social.groups where id=?", Integer.class, groupId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select moderator_ids = ARRAY[?]::uuid[] from social.group_profiles where group_id=?", Boolean.class,b,groupId)).isTrue();
    }
    @Test void realUniqueConstraintRollsBackDuplicateCreate() throws Exception {
        String name = jdbc.queryForObject("select name from social.groups where id=?", String.class, groupId);
        mvc.perform(post("/v1/groups").header("X-User-Id", c).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name",name,"description","duplicate"))))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("select count(*) from social.groups where name=?",Integer.class,name)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from social.group_memberships where group_id=?",Integer.class,groupId)).isEqualTo(1);
    }
    @Test void everyModeratorOperationRejectsOrdinaryMembersAndNonMembersDespiteGlobalRoles() throws Exception {
        join(b);
        for (UUID user : List.of(b,c)) {
            var requests = List.of(get(path+"/members"),put(path+"/moderators").content(moderators(a)),
                    patch(path+"/profile").content("{}"),put(path+"/rules").content("{\"rules\":[]}"),
                    put(path+"/flairs").content("{\"flairs\":[]}"),put(path+"/resources").content("{\"resources\":[]}"));
            for (var request : requests) mvc.perform(request.header("X-User-Id",user).header("X-User-Roles","ADMIN,MODERATOR")
                    .contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden());
        }
    }
    @Test void collectionsReplaceInOrderAndProfileCanClearNullableFields() throws Exception {
        mvc.perform(put(path+"/rules").header("X-User-Id",a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rules\":[{\"title\":\" Be kind \",\"description\":\"Respect others\"},{\"title\":\"No spam\",\"description\":\"Stay relevant\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rules[0].title").value("Be kind"))
                .andExpect(jsonPath("$.rules[1].displayOrder").value(1));
        mvc.perform(put(path+"/rules").header("X-User-Id",a).contentType(MediaType.APPLICATION_JSON).content("{\"rules\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rules").isEmpty());
        mvc.perform(put(path+"/flairs").header("X-User-Id",a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"flairs\":[{\"flairType\":\"post\",\"label\":\"EV\",\"tone\":\"ev\"},{\"flairType\":\"user\",\"label\":\"Driver\",\"tone\":\"reliable\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.postFlairs.length()").value(1)).andExpect(jsonPath("$.userFlairs.length()").value(1));
        mvc.perform(put(path+"/resources").header("X-User-Id",a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"resources\":[{\"label\":\"Map\",\"url\":\"https://example.com/map\"}]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.resources[0].displayOrder").value(0));
        mvc.perform(patch(path+"/profile").header("X-User-Id",a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"country\":null,\"bannerUrl\":null,\"tags\":[\" uniquezephyr \",\"uniquezephyr\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.country").isEmpty()).andExpect(jsonPath("$.tags.length()").value(1));
        mvc.perform(get("/v1/groups/search").param("q","uniquezephyr")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(groupId.toString()));
        mvc.perform(patch(path+"/profile").header("X-User-Id",a).contentType(MediaType.APPLICATION_JSON).content("{\"description\":null}"))
                .andExpect(status().isBadRequest());
    }
    @Test void mineExcludesLeftAndBannedAndVisibilityIsEnforced() throws Exception {
        join(b);
        mvc.perform(delete(path+"/members/me").header("X-User-Id",b)).andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(1));
        mvc.perform(delete(path+"/members/me").header("X-User-Id",b)).andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(1));
        mvc.perform(get("/v1/groups/mine").header("X-User-Id",b)).andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        jdbc.update("update social.group_memberships set state='banned' where group_id=? and user_id=?",groupId,b);
        mvc.perform(post(path+"/members/me").header("X-User-Id",b)).andExpect(status().isForbidden());
        jdbc.update("update social.groups set status='hidden' where id=?",groupId);
        mvc.perform(get(path)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("X-User-Id",b)).andExpect(status().isNotFound());
        mvc.perform(get(path).header("X-User-Id",a)).andExpect(status().isOk());
        mvc.perform(get("/v1/groups")).andExpect(status().isOk()).andExpect(jsonPath("$.content[?(@.id == '"+groupId+"')]").isEmpty());
        mvc.perform(get("/v1/groups/search").param("q","ev charging")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '"+groupId+"')]").isEmpty());
    }
    @Test void concurrentJoinsAndLeavesKeepCounterAccurate() throws Exception {
        String slug = path.substring("/v1/groups/".length());
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Callable<Object>> joins = new ArrayList<>();
            for (int i=0;i<12;i++) joins.add(() -> membershipService.join(slug,b));
            for (var result : executor.invokeAll(joins)) result.get(10,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select member_count from social.groups where id=?",Integer.class,groupId)).isEqualTo(2);
            List<Callable<Object>> leaves = new ArrayList<>();
            for (int i=0;i<12;i++) leaves.add(() -> membershipService.leave(slug,b));
            for (var result : executor.invokeAll(leaves)) result.get(10,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("select member_count from social.groups where id=?",Integer.class,groupId)).isEqualTo(1);
        }
    }
    @Test void concurrentModeratorLeavesCannotRemoveTheLastModerator() throws Exception {
        join(b);
        String slug = path.substring("/v1/groups/".length());
        moderationService.replaceModerators(slug, a, new com.navio.communityservice.dto.ReplaceModeratorsRequest(List.of(a,b)));
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> calls = List.of(() -> tryLeave(slug,a), () -> tryLeave(slug,b));
            List<Boolean> results = new ArrayList<>();
            for (var result : executor.invokeAll(calls)) results.add(result.get(10,TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(true,false);
        }
        assertThat(jdbc.queryForObject("select member_count from social.groups where id=?",Integer.class,groupId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select cardinality(moderator_ids) from social.group_profiles where group_id=?",Integer.class,groupId)).isEqualTo(1);
    }
    private boolean tryLeave(String slug, UUID user) {
        try { membershipService.leave(slug,user); return true; }
        catch (com.navio.communityservice.exception.GroupException ex) {
            assertThat(ex.getStatus().value()).isEqualTo(409);
            return false;
        }
    }
    @Test void paginatedQueriesReturnTotalsAndCallerFlagsWithoutDetailFetches() throws Exception {
        String slug = path.substring("/v1/groups/".length());
        membershipService.mute(slug,a,true);
        String secondName = "EV Charging Secondary " + UUID.randomUUID();
        var second = groups.create(a, new CreateGroupRequest(secondName,"EV charging",null,null,null));
        try {
            jdbc.update("update social.groups set is_official=true where id=?",groupId);
            mvc.perform(get("/v1/groups").header("X-User-Id",a).param("size","1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.content[0].id").value(groupId.toString()))
                    .andExpect(jsonPath("$.content[0].muted").value(true));
            mvc.perform(get("/v1/groups/mine").header("X-User-Id",a).param("size","1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.content[0].id").value(second.id().toString()));
            mvc.perform(get("/v1/groups/search").header("X-User-Id",a).param("q","ev charging").param("size","1"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                    .andExpect(jsonPath("$.content[0].joined").value(true));
        } finally { jdbc.update("delete from social.groups where id=?",second.id()); }
    }

}
