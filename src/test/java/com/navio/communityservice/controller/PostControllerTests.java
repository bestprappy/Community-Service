package com.navio.communityservice.controller;

import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.media.PictureValidator;
import com.navio.communityservice.security.CommunitySecurityConfig;
import com.navio.communityservice.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.util.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({PostController.class, CommentController.class})
@Import(CommunitySecurityConfig.class)
class PostControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean PostService posts;
    @MockitoBean CommentService comments;
    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final String BASE = "/v1/posts/" + ID;
    private static final String BODY = "{\"groupSlug\":\"trips\",\"title\":\"A trip\",\"body\":\"Notes\"}";

    @Test void anonymousMutationsNeverReachTheServices() throws Exception {
        for (var request : List.of(post("/v1/posts"), patch(BASE), delete(BASE), put(BASE + "/vote"),
                post(BASE + "/comments"), delete(BASE + "/comments/" + ID), put(BASE + "/comments/" + ID + "/vote"))) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        }
        mvc.perform(multipart("/v1/posts").file("file", new byte[]{1})).andExpect(status().isUnauthorized());
        verifyNoInteractions(posts, comments);
    }

    @Test void guestsReadPostsWithoutTrustingIdentityHeaders() throws Exception {
        when(posts.read(ID, null)).thenReturn(response());
        mvc.perform(get(BASE).header("X-User-Id", USER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ID.toString())).andExpect(jsonPath("$.viewerVote").value(0));
        verify(posts).read(ID, null);
    }

    @Test void createsMultipartPostsUsingTheValidatedSubject() throws Exception {
        when(posts.create(eq(USER), any(), any())).thenReturn(response());
        mvc.perform(multipart("/v1/posts").file(new MockMultipartFile("post", "post.json", "application/json", BODY.getBytes()))
                        .file(new MockMultipartFile("file", "test.png", "image/png", new byte[]{1,2}))
                        .with(jwt().jwt(j -> j.subject(USER.toString()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value("A trip"));
        verify(posts).create(eq(USER), argThat(r -> r.groupSlug().equals("trips")), any());
    }

    @Test void rejectsInvalidPostsBeforeAnyWrite() throws Exception {
        for (String body : List.of(BODY.replace("A trip", " "), BODY.replace("Notes", "x".repeat(40001)),
                BODY.replace("\"Notes\"", "null"), BODY.replace("\"body\"", "\"linkUrl\":\"javascript:alert(1)\",\"body\""))) {
            mvc.perform(post("/v1/posts").with(jwt().jwt(j -> j.subject(USER.toString())))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400));
        }
        verifyNoInteractions(posts);
    }

    @Test void invalidVotesCannotBecomeArbitraryScores() throws Exception {
        for (String body : List.of("{\"value\":2}", "{\"value\":null}", "{}")) {
            mvc.perform(put(BASE + "/vote").with(jwt().jwt(j -> j.subject(USER.toString())))
                            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(posts);
    }

    @Test void imageReadsHaveFixedTypeAndNoSniffHeaders() throws Exception {
        when(posts.image(ID, null)).thenReturn(new PictureValidator.Picture(new byte[]{1, 2}, "image/png"));
        mvc.perform(get(BASE + "/image")).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void hiddenImageReadsReturnTheDomainErrorContract() throws Exception {
        when(posts.image(ID, null)).thenThrow(GroupException.notFound());
        mvc.perform(get(BASE + "/image")).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    private PostResponse response() {
        return new PostResponse(ID, ID, "trips", "Trips", USER, "A trip", "Notes", null, null, null, null,
                Instant.parse("2026-09-12T00:00:00Z"), Instant.parse("2026-09-12T00:00:00Z"), 0, 0, 0);
    }
}
