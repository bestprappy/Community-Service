package com.navio.communityservice.service;
import com.navio.communityservice.dto.*;
import com.navio.communityservice.model.*;
import com.navio.communityservice.repository.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class GroupContentServiceTest {
    @Test void patchChangesOnlyPresentFieldsAndSupportsClearingNullableFields() {
        var access = mock(GroupAccessService.class);
        var profiles = mock(GroupProfileRepository.class);
        var views = mock(GroupViewService.class);
        var service = new GroupContentService(access, profiles, mock(GroupRuleRepository.class),
                mock(GroupFlairRepository.class), mock(GroupResourceRepository.class), views);
        UUID id = UUID.randomUUID(), user = UUID.randomUUID();
        Group group = Group.builder().id(id).name("EV").slug("ev").description("original").country("Thailand").build();
        GroupProfile profile = GroupProfile.builder().groupId(id).summary("summary").build();
        when(access.lock("ev", user)).thenReturn(group);
        when(profiles.findById(id)).thenReturn(Optional.of(profile));
        var patch = new UpdateGroupProfileRequest(); patch.setBannerUrl("https://example.com/banner.jpg");
        service.updateProfile("ev", user, patch);
        assertThat(group.getName()).isEqualTo("EV"); assertThat(group.getSlug()).isEqualTo("ev");
        assertThat(group.getDescription()).isEqualTo("original"); assertThat(profile.getSummary()).isEqualTo("summary");
        assertThat(profile.getBannerUrl()).isEqualTo("https://example.com/banner.jpg");
        var clear = new UpdateGroupProfileRequest(); clear.setBannerUrl(null);
        service.updateProfile("ev", user, clear);
        assertThat(profile.getBannerUrl()).isNull();
        assertThat(group.getCountry()).isEqualTo("Thailand");
        verify(access, times(2)).requireModerator(id, user);
    }
}
