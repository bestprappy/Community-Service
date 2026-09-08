package com.navio.communityservice.service;
import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.model.*;
import com.navio.communityservice.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupModerationServiceTest {
    @Mock GroupAccessService access;
    @Mock GroupMembershipRepository memberships;
    @Mock GroupProfileRepository profiles;
    @Mock GroupViewService views;
    @InjectMocks GroupModerationService service;
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
    Group group = Group.builder().id(UUID.randomUUID()).slug("ev").memberCount(3).build();
    GroupMembership member(UUID id, String role) { return GroupMembership.builder().id(new MembershipId(group.getId(), id)).role(role).build(); }
    @BeforeEach void setup() { when(access.lock("ev", a)).thenReturn(group); }
    @Test void replacementDemotesPromotesDeduplicatesAndMirrors() {
        var ma = member(a, "admin"); var mb = member(b, "moderator"); var mc = member(c, "member");
        var profile = GroupProfile.builder().groupId(group.getId()).summary("").moderatorIds(new UUID[]{a, b}).build();
        when(memberships.findByIdGroupIdAndIdUserIdIn(group.getId(), Set.of(b, c))).thenReturn(List.of(mb, mc));
        when(memberships.findByIdGroupIdAndRoleIn(group.getId(), GroupAccessService.MODERATOR_ROLES)).thenReturn(List.of(ma, mb));
        when(profiles.findById(group.getId())).thenReturn(Optional.of(profile));
        service.replaceModerators("ev", a, new ReplaceModeratorsRequest(List.of(b, c, c)));
        assertThat(ma.getRole()).isEqualTo("member");
        assertThat(mb.getRole()).isEqualTo("moderator");
        assertThat(mc.getRole()).isEqualTo("moderator");
        assertThat(profile.getModeratorIds()).containsExactly(b, c);
        assertThat(group.getMemberCount()).isEqualTo(3);
        verify(access).requireModerator(group.getId(), a);
    }
    @Test void nonMemberRejectsBeforeAnyMutation() {
        when(memberships.findByIdGroupIdAndIdUserIdIn(group.getId(), Set.of(c))).thenReturn(List.of());
        assertThatThrownBy(() -> service.replaceModerators("ev", a, new ReplaceModeratorsRequest(List.of(c))))
                .isInstanceOf(GroupException.class);
        verify(memberships, never()).saveAll(any());
        verifyNoInteractions(profiles, views);
    }
    @Test void emptySetIsConflict() {
        assertThatThrownBy(() -> service.replaceModerators("ev", a, new ReplaceModeratorsRequest(List.of())))
                .isInstanceOf(GroupException.class).hasMessageContaining("at least one moderator");
        verifyNoInteractions(memberships, profiles);
    }
    @Test void selectedAdminKeepsRole() {
        var ma = member(a, "admin");
        when(memberships.findByIdGroupIdAndIdUserIdIn(group.getId(), Set.of(a))).thenReturn(List.of(ma));
        when(memberships.findByIdGroupIdAndRoleIn(group.getId(), GroupAccessService.MODERATOR_ROLES)).thenReturn(List.of(ma));
        when(profiles.findById(group.getId())).thenReturn(Optional.of(GroupProfile.builder().summary("").build()));
        service.replaceModerators("ev", a, new ReplaceModeratorsRequest(List.of(a)));
        assertThat(ma.getRole()).isEqualTo("admin");
    }
}
