package com.navio.communityservice.service;
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
class GroupMembershipServiceTest {
    @Mock GroupAccessService access;
    @Mock GroupMembershipRepository memberships;
    @Mock GroupProfileRepository profiles;
    @InjectMocks GroupMembershipService service;
    UUID user = UUID.randomUUID();
    Group group;
    GroupMembership member;
    @BeforeEach void setup() {
        group = Group.builder().id(UUID.randomUUID()).slug("ev").memberCount(2).build();
        member = GroupMembership.builder().id(new MembershipId(group.getId(), user)).build();
        when(access.lock("ev", user)).thenReturn(group);
        when(memberships.findById(member.getId())).thenReturn(Optional.of(member));
    }
    @Test void joinAndLeaveAreIdempotent() {
        member.setState("left"); group.setMemberCount(1);
        assertThat(service.join("ev", user).memberCount()).isEqualTo(2);
        assertThat(service.join("ev", user).memberCount()).isEqualTo(2);
        assertThat(member.getRole()).isEqualTo("member");
        assertThat(service.leave("ev", user).memberCount()).isEqualTo(1);
        assertThat(service.leave("ev", user).memberCount()).isEqualTo(1);
        assertThat(member.getState()).isEqualTo("left");
        verify(memberships, times(2)).save(member);
        verify(memberships, never()).delete(any());
    }
    @Test void firstJoinCreatesOnlyMemberRole() {
        when(memberships.findById(member.getId())).thenReturn(Optional.empty());
        var result = service.join("ev", user);
        assertThat(result.role()).isEqualTo("member");
        assertThat(result.memberCount()).isEqualTo(3);
    }
    @Test void mutePreservesMembershipAndCounterAndJoinDoesNotUnmute() {
        assertThat(service.mute("ev", user, true).joined()).isTrue();
        assertThat(service.join("ev", user).muted()).isTrue();
        assertThat(group.getMemberCount()).isEqualTo(2);
        assertThat(service.mute("ev", user, false).muted()).isFalse();
        assertThat(group.getMemberCount()).isEqualTo(2);
    }
    @Test void leavingMutedDecrementsOnce() {
        member.setState("muted");
        assertThat(service.leave("ev", user).memberCount()).isEqualTo(1);
        assertThat(service.leave("ev", user).memberCount()).isEqualTo(1);
    }
    @Test void bannedCannotRejoinOrEraseBanByLeaving() {
        member.setState("banned");
        assertThatThrownBy(() -> service.join("ev", user)).isInstanceOf(GroupException.class);
        assertThatThrownBy(() -> service.leave("ev", user)).isInstanceOf(GroupException.class);
        assertThatThrownBy(() -> service.mute("ev", user, true)).isInstanceOf(GroupException.class);
        assertThat(member.getState()).isEqualTo("banned");
        assertThat(group.getMemberCount()).isEqualTo(2);
    }
    @Test void lastModeratorCannotLeaveEvenWhenMuted() {
        member.setRole("admin"); member.setState("muted");
        when(memberships.findByIdGroupIdAndRoleIn(group.getId(), GroupAccessService.MODERATOR_ROLES)).thenReturn(List.of(member));
        assertThatThrownBy(() -> service.leave("ev", user)).isInstanceOf(GroupException.class)
                .hasMessageContaining("Hand over moderation");
        assertThat(member.getState()).isEqualTo("muted");
        assertThat(group.getMemberCount()).isEqualTo(2);
    }
    @Test void moderatorLeavingAndRejoiningMirrorsActiveModeratorsWhilePreservingRole() {
        member.setRole("moderator");
        var other = GroupMembership.builder().id(new MembershipId(group.getId(), UUID.randomUUID())).role("admin").build();
        var profile = GroupProfile.builder().groupId(group.getId()).summary("").build();
        when(memberships.findByIdGroupIdAndRoleIn(group.getId(), GroupAccessService.MODERATOR_ROLES)).thenReturn(List.of(member, other));
        when(profiles.findById(group.getId())).thenReturn(Optional.of(profile));
        service.leave("ev", user);
        assertThat(profile.getModeratorIds()).containsExactly(other.getId().getUserId());
        assertThat(member.getRole()).isEqualTo("moderator");
        service.join("ev", user);
        assertThat(profile.getModeratorIds()).containsExactlyInAnyOrder(user, other.getId().getUserId());
    }
}
