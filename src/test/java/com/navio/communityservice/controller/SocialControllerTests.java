package com.navio.communityservice.controller;

import com.navio.communityservice.dto.SocialDtos.SocialSummaryResponse;
import com.navio.communityservice.dto.SocialDtos.UnreadCountResponse;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.security.CommunitySecurityConfig;
import com.navio.communityservice.service.CommentService;
import com.navio.communityservice.service.NotificationService;
import com.navio.communityservice.service.PostService;
import com.navio.communityservice.service.ReportService;
import com.navio.communityservice.service.SocialGraphService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({SocialController.class, NotificationController.class, ReportController.class, PostController.class})
@Import(CommunitySecurityConfig.class)
class SocialControllerTests {

    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000002");

    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean SocialGraphService social;
    @MockitoBean NotificationService notifications;
    @MockitoBean ReportService reports;
    @MockitoBean PostService posts;
    @MockitoBean CommentService comments;

    private static MockHttpServletRequestBuilder signedIn(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(token -> token.subject(USER.toString())));
    }

    @Test
    void anonymousCallersReachNoSocialNotificationOrReportRoute() throws Exception {
        String user = "/v1/social/users/" + OTHER;
        String notification = "/v1/notifications/" + OTHER;
        for (var request : List.of(get(user + "/summary"), put(user + "/follow"), delete(user + "/follow"),
                put(user + "/alerts"), put(user + "/block"), delete(user + "/block"), get(user + "/followers"),
                get(user + "/following"), get(user + "/posts"), get("/v1/social/blocks"), get("/v1/social/settings"),
                put("/v1/social/settings"), get("/v1/notifications"), get("/v1/notifications/unread-count"),
                post(notification + "/read"), post("/v1/notifications/read-all"), get("/v1/notifications/preferences"),
                put("/v1/notifications/preferences"), post("/v1/notifications/plan-shared"), post("/v1/reports"),
                get("/v1/admin/reports"), patch("/v1/admin/reports/" + OTHER),
                put("/v1/posts/" + OTHER + "/pin"), delete("/v1/posts/" + OTHER + "/pin"))) {
            mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        }
        verifyNoInteractions(social, notifications, reports, posts);
    }

    @Test
    void followingActsAsTheTokenSubjectNotAHeader() throws Exception {
        when(social.follow(USER, OTHER)).thenReturn(new SocialSummaryResponse(OTHER, 1, 0, "everyone", true,
                true, false, true, false, false, false));
        mvc.perform(signedIn(put("/v1/social/users/" + OTHER + "/follow")).header("X-User-Id", OTHER.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.viewerFollows").value(true));
        verify(social).follow(USER, OTHER);
    }

    @Test
    void selfFollowIsABadRequestWithTheSharedErrorShape() throws Exception {
        when(social.follow(USER, USER)).thenThrow(GroupException.invalid("You cannot follow yourself"));
        mvc.perform(signedIn(put("/v1/social/users/" + USER + "/follow")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("You cannot follow yourself"));
    }

    @Test
    void literalNotificationRoutesAreNotTreatedAsIds() throws Exception {
        when(notifications.unreadCount(USER)).thenReturn(new UnreadCountResponse(4));
        mvc.perform(signedIn(get("/v1/notifications/unread-count"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(4));
        when(notifications.markAllRead(USER)).thenReturn(new UnreadCountResponse(0));
        mvc.perform(signedIn(post("/v1/notifications/read-all"))).andExpect(status().isOk());
        verify(notifications).markAllRead(USER);
    }

    @Test
    void invalidReportReasonsAndVisibilityValuesAreRejectedBeforeTheService() throws Exception {
        mvc.perform(signedIn(post("/v1/reports")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetType\":\"post\",\"targetId\":\"" + OTHER + "\",\"reason\":\"boring\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.validationErrors.reason").exists());
        mvc.perform(signedIn(put("/v1/social/settings")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"connectionsVisibility\":\"world\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(reports, social);
    }

    @Test
    void ordinaryMembersAndGroupModeratorsCannotOpenTheReportQueue() throws Exception {
        mvc.perform(signedIn(get("/v1/admin/reports"))).andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(get("/v1/admin/reports").with(jwt().jwt(token -> token.subject(USER.toString())
                        .claim("resource_access", Map.of("some-other-client", Map.of("roles", List.of("ADMIN")))))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(reports);
    }

    @Test
    void platformModeratorsCanReviewReports() throws Exception {
        when(reports.queue(eq("open"), anyInt(), anyInt())).thenReturn(org.springframework.data.domain.Page.empty());
        mvc.perform(get("/v1/admin/reports").with(jwt().jwt(token -> token.subject(USER.toString())
                        .claim("realm_access", Map.of("roles", List.of("moderator"))))))
                .andExpect(status().isOk());
        verify(reports).queue(eq("open"), anyInt(), anyInt());
    }

    @Test
    void pinningIsDelegatedWithTheCaller() throws Exception {
        mvc.perform(signedIn(delete("/v1/posts/" + OTHER + "/pin"))).andExpect(status().isNoContent());
        verify(posts).pin(OTHER, USER, false);
    }

    @Test
    void planAnnouncementsValidateTheTokenShape() throws Exception {
        mvc.perform(signedIn(post("/v1/notifications/plan-shared")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"../../etc\"}")).andExpect(status().isBadRequest());
        verify(notifications, org.mockito.Mockito.never()).planShared(any(), anyString());
    }
}
