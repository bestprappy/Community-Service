package com.navio.communityservice.service;

import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.media.PostPictureService;
import com.navio.communityservice.model.Group;
import com.navio.communityservice.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostServiceTests {
    @Mock PostRepository posts;
    @Mock GroupAccessService access;
    @Mock GroupFlairRepository flairs;
    @Mock PostPictureService pictures;
    @InjectMocks PostService service;
    private static final UUID AUTHOR = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private final Group group = Group.builder().id(ID).slug("trips").status("active").build();

    @Test void nonmembersCannotStorePostsOrPictures() {
        when(access.lock("trips", OTHER)).thenReturn(group);
        doThrow(GroupException.forbidden("Join first")).when(access).requireActiveMember(group, OTHER);
        assertThatThrownBy(() -> service.create(OTHER, new CreatePostRequest("trips", "Title", "", null, null, null), null)).isInstanceOf(GroupException.class);
        verifyNoInteractions(posts, pictures, flairs);
    }

    @Test void foreignFlairsCannotBeAttachedToPosts() {
        when(access.lock("trips", AUTHOR)).thenReturn(group);
        when(flairs.findById(OTHER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(AUTHOR, new CreatePostRequest("trips", "Title", "", null, OTHER, null), null)).isInstanceOf(GroupException.class);
        verify(posts, never()).create(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(pictures);
    }

    @Test void anotherMemberCannotRewriteTheAuthorsPost() {
        when(posts.find(ID, OTHER)).thenReturn(Optional.of(response()));
        when(access.lock("trips", OTHER)).thenReturn(group);
        assertThatThrownBy(() -> service.update(ID, OTHER, new UpdatePostRequest("Changed", ""))).isInstanceOf(GroupException.class);
        verify(posts, never()).update(any(), any());
    }

    @Test void postImagesUseTheSameVisibilityGateAsTheirPost() {
        when(posts.find(ID, null)).thenReturn(Optional.of(response()));
        when(access.readable("trips", null)).thenThrow(GroupException.notFound());
        assertThatThrownBy(() -> service.image(ID, null)).isInstanceOf(GroupException.class);
        verifyNoInteractions(pictures);
    }

    @Test void deletionChecksModeratorPermissionBeforeRemovingAnyData() {
        when(posts.find(ID, OTHER)).thenReturn(Optional.of(response()));
        when(access.lock("trips", OTHER)).thenReturn(group);
        doThrow(GroupException.forbidden("Only author or moderator")).when(access).requireModerator(ID, OTHER);
        assertThatThrownBy(() -> service.delete(ID, OTHER)).isInstanceOf(GroupException.class);
        verify(posts, never()).delete(any()); verifyNoInteractions(pictures);
    }

    @Test void removingAVoteIsAnExplicitZeroOperation() {
        when(posts.find(ID, AUTHOR)).thenReturn(Optional.of(response()));
        when(access.lock("trips", AUTHOR)).thenReturn(group);
        service.vote(ID, AUTHOR, 0);
        verify(posts).vote(ID, AUTHOR, 0);
        verify(access).requireActiveMember(group, AUTHOR);
    }

    @Test void paginationRejectsUnboundedReads() {
        for (int size : List.of(0, 101)) assertThatThrownBy(() -> PostService.pagination(0, size)).isInstanceOf(GroupException.class);
        assertThatThrownBy(() -> PostService.pagination(-1, 20)).isInstanceOf(GroupException.class);
    }

    private PostResponse response() {
        return new PostResponse(ID, ID, "trips", "Trips", AUTHOR, "Title", "", null, null, null, null,
                Instant.EPOCH, Instant.EPOCH, 0, 0, 0);
    }
}
