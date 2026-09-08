package com.navio.communityservice.service;

import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.model.*;
import com.navio.communityservice.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GroupServiceTest {
    @Mock GroupRepository groups;
    @Mock GroupProfileRepository profiles;
    @Mock GroupMembershipRepository memberships;
    @Mock GroupAccessService access;
    @Mock GroupViewService views;
    @InjectMocks GroupService service;
    final UUID user = UUID.randomUUID();
    @Test void createsAtomicInitialStateAndNormalizesLabels() {
        UUID id = UUID.randomUUID();
        when(groups.saveAndFlush(any())).thenAnswer(call -> { Group g = call.getArgument(0); g.setId(id); return g; });
        service.create(user, new CreateGroupRequest("  Thailand EV Charging  ", " Charging tips ", null,
                List.of(" Bangkok ", "", "Bangkok"), List.of(" ev ", "ev")));
        ArgumentCaptor<Group> group = ArgumentCaptor.forClass(Group.class);
        verify(groups).saveAndFlush(group.capture());
        assertThat(group.getValue().getName()).isEqualTo("Thailand EV Charging");
        assertThat(group.getValue().getSlug()).isEqualTo("thailand-ev-charging");
        assertThat(group.getValue().getPlaces()).containsExactly("Bangkok");
        assertThat(group.getValue().getTags()).containsExactly("ev");
        assertThat(group.getValue().getMemberCount()).isEqualTo(1);
        verify(profiles).save(argThat(p -> p.getGroupId().equals(id) && Arrays.equals(p.getModeratorIds(), new UUID[]{user})));
        verify(memberships).save(argThat(m -> m.getId().equals(new MembershipId(id, user))
                && m.getRole().equals("admin") && m.getState().equals("joined")));
    }
    @Test void rejectsInvalidNamesBeforePersistence() {
        for (String name : List.of("  ", "x".repeat(121), "!!!", "mine", "SEARCH")) {
            assertThatThrownBy(() -> service.create(user, new CreateGroupRequest(name, "description", null, null, null)))
                    .isInstanceOf(GroupException.class);
        }
        verifyNoInteractions(groups, profiles, memberships);
    }
    @Test void duplicateConstraintPropagatesWithoutCreatingChildren() {
        when(groups.saveAndFlush(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate"));
        assertThatThrownBy(() -> service.create(user, new CreateGroupRequest("EV", "description", null, null, null)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        verifyNoInteractions(profiles, memberships);
    }
    @Test void blankSearchNeverQueriesRepository() {
        for (String q : Arrays.asList(null, "", "  ")) assertThat(service.search(null, q, PageRequest.of(0, 20))).isEmpty();
        verifyNoInteractions(groups);
    }
    @Test void searchMapsDatabaseHitsAndGuestMembership() {
        GroupSearchRow row = mock(GroupSearchRow.class);
        when(row.getName()).thenReturn("EV Charging");
        when(groups.search(null, "ev charging", PageRequest.of(0, 20))).thenReturn(new PageImpl<>(List.of(row)));
        var result = service.search(null, " ev charging ", PageRequest.of(0, 20)).getContent().getFirst();
        assertThat(result.name()).isEqualTo("EV Charging");
        assertThat(result.joined()).isFalse();
        assertThat(result.muted()).isFalse();
        assertThat(result.role()).isNull();
    }
    @Test void mineDelegatesOnePaginatedQuery() {
        when(groups.mine(user, PageRequest.of(0, 20))).thenReturn(Page.empty());
        assertThat(service.mine(user, PageRequest.of(0, 20))).isEmpty();
        verify(groups).mine(user, PageRequest.of(0, 20));
        verifyNoInteractions(memberships, views);
    }
}
