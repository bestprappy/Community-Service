package com.navio.communityservice.controller;

import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.media.GroupPictureService;
import com.navio.communityservice.media.GroupPictureSlot;
import com.navio.communityservice.media.PictureValidator;
import com.navio.communityservice.security.CommunitySecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(GroupPictureController.class)
@Import(CommunitySecurityConfig.class)
class GroupPictureControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean JwtDecoder decoder;
    @MockitoBean GroupPictureService pictures;

    @Test void anonymousCallersCannotUploadOrRemoveBanners() throws Exception {
        mvc.perform(multipart("/v1/groups/trips/banner").file("file",new byte[]{1})).andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/groups/trips/banner")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(pictures);
    }

    @Test void anonymousCallersCannotUploadOrRemoveIcons() throws Exception {
        mvc.perform(multipart("/v1/groups/trips/icon").file("file",new byte[]{1})).andExpect(status().isUnauthorized());
        mvc.perform(delete("/v1/groups/trips/icon")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        verifyNoInteractions(pictures);
    }

    @Test void guestsCanReadIconsWithoutCachingOrSniffing() throws Exception {
        when(pictures.read("trips", GroupPictureSlot.ICON, null)).thenReturn(new PictureValidator.Picture(new byte[]{1, 2}, "image/png"));
        mvc.perform(get("/v1/groups/trips/icon")).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test void missingIconsReturnTheNotFoundContract() throws Exception {
        when(pictures.read("trips", GroupPictureSlot.ICON, null)).thenThrow(GroupException.notFound());
        mvc.perform(get("/v1/groups/trips/icon")).andExpect(status().isNotFound()).andExpect(jsonPath("$.status").value(404));
    }

    @Test void iconUploadsGoToTheIconSlotForTheValidatedSubject() throws Exception {
        UUID actor=UUID.fromString("00000000-0000-4000-8000-000000000001");
        mvc.perform(multipart("/v1/groups/trips/icon").file("file",new byte[]{1}).with(jwt().jwt(j -> j.subject(actor.toString()))))
                .andExpect(status().isCreated());
        verify(pictures).upload(eq("trips"), eq(GroupPictureSlot.ICON), eq(actor), any());
    }

    @Test void bannerRemovalUsesTheValidatedSubject() throws Exception {
        UUID actor=UUID.fromString("00000000-0000-4000-8000-000000000001");
        mvc.perform(delete("/v1/groups/trips/banner").with(jwt().jwt(j -> j.subject(actor.toString())))).andExpect(status().isNoContent());
        verify(pictures).remove("trips",GroupPictureSlot.BANNER,actor);
    }
}
