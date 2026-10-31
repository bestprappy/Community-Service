package com.navio.communityservice.controller;

import com.navio.communityservice.dto.NotificationEventDtos.ReplayResponse;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.security.CommunitySecurityConfig;
import com.navio.communityservice.service.NotificationStreamHub;
import com.navio.communityservice.service.QuarantinedEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({NotificationStreamController.class, NotificationEventAdminController.class})
@Import(CommunitySecurityConfig.class)
class NotificationDeliveryControllerTests {

    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final Instant EXPIRES = Instant.parse("2030-01-01T00:05:00Z");

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean NotificationStreamHub hub;
    @MockitoBean QuarantinedEventService events;

    @Test
    void anonymousCallersCannotOpenAStreamOrTouchQuarantinedEvents() throws Exception {
        mvc.perform(get("/v1/notifications/stream").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/admin/notification-events")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(post("/v1/admin/notification-events/" + OTHER + "/replay")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(hub, events);
    }

    @Test
    void theStreamBelongsToTheTokenSubjectAndEndsWithTheToken() throws Exception {
        when(hub.connect(USER, EXPIRES)).thenReturn(new SseEmitter());

        mvc.perform(get("/v1/notifications/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt().jwt(token -> token.subject(USER.toString()).expiresAt(EXPIRES))))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string("Cache-Control", "no-store"));

        verify(hub).connect(USER, EXPIRES);
    }

    @Test
    void noParameterOrHeaderCanRedirectTheStreamToAnotherAccount() throws Exception {
        when(hub.connect(eq(USER), any())).thenReturn(new SseEmitter());

        mvc.perform(get("/v1/notifications/stream").param("recipient", OTHER.toString()).header("X-User-Id", OTHER)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt().jwt(token -> token.subject(USER.toString()).expiresAt(EXPIRES))))
                .andExpect(request().asyncStarted());

        verify(hub).connect(USER, EXPIRES);
        verify(hub, never()).connect(eq(OTHER), any());
    }

    @Test
    void aFullInstanceRefusesTheStreamWithRetryAfter() throws Exception {
        when(hub.connect(any(), any())).thenThrow(new NotificationStreamHub.StreamCapacityException());

        mvc.perform(get("/v1/notifications/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .with(jwt().jwt(token -> token.subject(USER.toString()).expiresAt(EXPIRES))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"));
    }

    @Test
    void ordinaryUsersAndModeratorsCannotManageQuarantinedEvents() throws Exception {
        mvc.perform(get("/v1/admin/notification-events").with(jwt().jwt(token -> token.subject(USER.toString()))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(post("/v1/admin/notification-events/" + OTHER + "/replay")
                        .with(jwt().jwt(token -> token.subject(USER.toString())
                                .claim("realm_access", Map.of("roles", List.of("MODERATOR"))))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        verifyNoInteractions(events);
    }

    @Test
    void anAdministratorListsAndReplaysQuarantinedEvents() throws Exception {
        when(events.list(anyBoolean(), anyInt(), anyInt())).thenReturn(Page.empty());
        when(events.replay(OTHER, USER)).thenReturn(new ReplayResponse(OTHER, "NOTIFIED"));
        var admin = jwt().jwt(token -> token.subject(USER.toString())
                .claim("realm_access", Map.of("roles", List.of("admin"))));

        mvc.perform(get("/v1/admin/notification-events").with(admin)).andExpect(status().isOk());
        mvc.perform(post("/v1/admin/notification-events/" + OTHER + "/replay").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("NOTIFIED"));

        verify(events).list(true, 0, 20);
        verify(events).replay(OTHER, USER);
    }

    @Test
    void aReplayThatStillCannotBeProcessedReportsUnprocessableEntity() throws Exception {
        when(events.replay(OTHER, USER)).thenThrow(new GroupException(HttpStatus.UNPROCESSABLE_ENTITY, "still malformed"));

        mvc.perform(post("/v1/admin/notification-events/" + OTHER + "/replay")
                        .with(jwt().jwt(token -> token.subject(USER.toString())
                                .claim("realm_access", Map.of("roles", List.of("OWNER"))))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.message").value("still malformed"));
    }
}
