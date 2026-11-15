package com.navio.communityservice.service;
import com.navio.communityservice.dto.*;
import com.navio.communityservice.model.*;
import com.navio.communityservice.repository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class GroupContentServiceTest {
    @Test void patchChangesOnlyPresentFieldsAndSupportsClearingNullableFields() {
        var access = mock(GroupAccessService.class);
        var profiles = mock(GroupProfileRepository.class);
        var views = mock(GroupViewService.class);
        var service = new GroupContentService(access, profiles, mock(GroupRuleRepository.class),
                mock(GroupFlairRepository.class), mock(GroupResourceRepository.class), views, mock(GroupMediaRepository.class), mock(com.navio.communityservice.media.GroupPictureService.class));
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        Group group = Group.builder().id(id).name("EV").slug("ev").description("original").country("Thailand").build();
        GroupProfile profile = GroupProfile.builder().groupId(id).summary("summary").build();
        when(access.lock("ev", user)).thenReturn(group);
        when(profiles.findById(id)).thenReturn(Optional.of(profile));
        var patch = new UpdateGroupProfileRequest(); patch.setCountry("Vietnam");
        service.updateProfile("ev", user, patch);
        assertThat(group.getName()).isEqualTo("EV"); assertThat(group.getSlug()).isEqualTo("ev");
        assertThat(group.getDescription()).isEqualTo("original"); assertThat(profile.getSummary()).isEqualTo("summary");
        assertThat(group.getCountry()).isEqualTo("Vietnam");
        var clear = new UpdateGroupProfileRequest(); clear.setBannerUrl(null);
        service.updateProfile("ev", user, clear);
        assertThat(profile.getBannerUrl()).isNull();
        assertThat(group.getCountry()).isEqualTo("Vietnam");
        verify(access, times(2)).requireContentEditor(group, user);
    }
    @SuppressWarnings("unchecked")
    @Test void flairsSaveAnyColorAndTextOrTheColorTheirPaletteIdOrToneStoodFor() {
        var access = mock(GroupAccessService.class);
        var flairs = mock(GroupFlairRepository.class);
        var service = new GroupContentService(access, mock(GroupProfileRepository.class), mock(GroupRuleRepository.class),
                flairs, mock(GroupResourceRepository.class), mock(GroupViewService.class), mock(GroupMediaRepository.class), mock(com.navio.communityservice.media.GroupPictureService.class));
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        when(access.lock("ev", user)).thenReturn(Group.builder().id(id).slug("ev").status("active").build());
        service.replaceFlairs("ev", user, new ReplaceFlairsRequest(List.of(
                new FlairRequest(null, "Charging", null, null, "#FF7A00", "black"),
                new FlairRequest(null, "Older client", null, "violet", null, null),
                new FlairRequest("post", "Question", "question", null, null, null),
                new FlairRequest(null, "Plain", null, null, null, null))));
        ArgumentCaptor<List<GroupFlair>> saved = ArgumentCaptor.forClass(List.class);
        verify(flairs).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(GroupFlair::getFlairType).containsOnly("post");
        assertThat(saved.getValue()).extracting(GroupFlair::getColorHex).containsExactly("#ff7a00", "#8654cc", "#008cdf", "#00ae84");
        assertThat(saved.getValue()).extracting(GroupFlair::getTextColor).containsExactly("black", "white", "white", "white");
        assertThat(saved.getValue()).extracting(GroupFlair::getTone).containsExactly("reliable", "reliable", "question", "reliable");
    }
}
