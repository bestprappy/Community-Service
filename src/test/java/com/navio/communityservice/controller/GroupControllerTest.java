package com.navio.communityservice.controller;
import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.*;
import com.navio.communityservice.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.dao.DataIntegrityViolationException;
import java.sql.SQLException;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GroupController.class)
class GroupControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean GroupService groups;
    @MockitoBean GroupMembershipService memberships;
    @MockitoBean GroupModerationService moderation;
    @MockitoBean GroupContentService content;
    final UUID user = UUID.randomUUID();
    @Test void guestsCannotMutateOrReadMembers() throws Exception {
        var requests = List.of(post("/v1/groups"), post("/v1/groups/ev/members/me"), delete("/v1/groups/ev/members/me"),
                patch("/v1/groups/ev/members/me"), get("/v1/groups/mine"), get("/v1/groups/ev/members"),
                put("/v1/groups/ev/moderators"), patch("/v1/groups/ev/profile"), put("/v1/groups/ev/rules"),
                put("/v1/groups/ev/flairs"), put("/v1/groups/ev/resources"));
        for (var request : requests) mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(groups, memberships, moderation, content);
    }
    @Test void guestsCanReadAndLiteralsRouteCorrectly() throws Exception {
        mvc.perform(get("/v1/groups/ev")).andExpect(status().isOk());
        mvc.perform(get("/v1/groups")).andExpect(status().isOk());
        mvc.perform(get("/v1/groups/search")).andExpect(status().isOk());
        mvc.perform(get("/v1/groups/mine").header("X-User-Id", user)).andExpect(status().isOk());
        verify(groups).detail("ev", null);
        verify(groups).discover(isNull(), any());
        verify(groups).search(isNull(), isNull(), any());
        verify(groups).mine(eq(user), any());
    }
    @Test void validationRejectsBlankLongAndMalformedBodies() throws Exception {
        for (String name : List.of(" ", "x".repeat(121))) {
            mvc.perform(post("/v1/groups").header("X-User-Id", user).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"" + name + "\",\"description\":\"tips\"}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.validationErrors.name").exists());
        }
        mvc.perform(post("/v1/groups").header("X-User-Id", user).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/v1/groups").header("X-User-Id", "not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/groups?page=-1")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/groups?size=101")).andExpect(status().isBadRequest());
        mvc.perform(patch("/v1/groups/ev/members/me").header("X-User-Id", user)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
    @Test void profileRejectsImmutableFieldsAndFlairsValidateTone() throws Exception {
        mvc.perform(patch("/v1/groups/ev/profile").header("X-User-Id", user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"New name\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/v1/groups/ev/flairs").header("X-User-Id", user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"flairs\":[{\"flairType\":\"post\",\"label\":\"EV\",\"tone\":\"invalid\"}]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(content);
    }
    @Test void duplicateSlugConstraintMapsTo409WithoutLeakingSql() throws Exception {
        var cause = new org.hibernate.exception.ConstraintViolationException("secret SQL", new SQLException("secret"), "groups_slug_key");
        when(groups.create(eq(user), any())).thenThrow(new DataIntegrityViolationException("secret SQL", cause));
        mvc.perform(post("/v1/groups").header("X-User-Id", user).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"EV\",\"description\":\"tips\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("A group with this slug already exists"));
    }
    @Test void domainErrorsKeepConsistentShape() throws Exception {
        when(memberships.join("ev", user)).thenThrow(GroupException.forbidden("Banned members cannot join this group"));
        mvc.perform(post("/v1/groups/ev/members/me").header("X-User-Id", user))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.timestamp").exists()).andExpect(jsonPath("$.error").value("Forbidden"));
        when(groups.detail("missing", null)).thenThrow(GroupException.notFound());
        mvc.perform(get("/v1/groups/missing")).andExpect(status().isNotFound());
    }
}
