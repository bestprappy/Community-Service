package com.navio.communityservice.service;

import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.integration.SharedPlanDirectory;
import com.navio.communityservice.media.PostPictureService;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;
import com.navio.communityservice.model.Group;
import com.navio.communityservice.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
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
    @Mock SharedPlanDirectory sharedPlans;
    @Mock SocialGraphService socialGraph;
    @Mock NotificationService notifications;
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
        verify(posts, never()).create(any(), any(), any(), any(), any());
        verifyNoInteractions(pictures);
    }

    private PostResponse postBy(UUID author) {
        return new PostResponse(OTHER, ID, "trips", "Trips", null, author, "Title", "", null, null, null, null,
                Instant.EPOCH, Instant.EPOCH, 0, 0, 0, false);
    }

    @Test void communityModeratorsCanRemoveAnotherMembersPost() {
        UUID moderator = UUID.fromString("00000000-0000-4000-8000-000000000004");
        group.setPostCount(3);
        when(posts.find(OTHER, moderator)).thenReturn(Optional.of(postBy(AUTHOR)));
        when(access.lock("trips", moderator)).thenReturn(group);
        service.delete(OTHER, moderator);
        verify(access).requireModerator(ID, moderator);
        verify(posts).delete(OTHER);
        assertThat(group.getPostCount()).isEqualTo(2);
    }

    @Test void ordinaryMembersCannotRemoveSomeoneElsesPost() {
        UUID member = UUID.fromString("00000000-0000-4000-8000-000000000005");
        when(posts.find(OTHER, member)).thenReturn(Optional.of(postBy(AUTHOR)));
        when(access.lock("trips", member)).thenReturn(group);
        doThrow(GroupException.forbidden("Group moderator membership is required")).when(access).requireModerator(ID, member);
        assertThatThrownBy(() -> service.delete(OTHER, member))
                .isInstanceOf(GroupException.class)
                .extracting(ex -> ((GroupException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(posts, never()).delete(any());
        verifyNoInteractions(pictures);
    }

    private static final String PLAN_TOKEN = "Abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE";

    @Test void aPlanThatIsNotListedOnExploreCannotBeAttached() {
        when(sharedPlans.isListedOnExplore(PLAN_TOKEN)).thenReturn(false);
        assertThatThrownBy(() -> service.create(AUTHOR, new CreatePostRequest("trips", "Title", "", null, null, PLAN_TOKEN), null))
                .isInstanceOf(GroupException.class)
                .extracting(ex -> ((GroupException) ex).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(access, posts, pictures, flairs);
    }

    @Test void anUnreachableTripServiceRejectsTheAttachmentAsUnavailable() {
        when(sharedPlans.isListedOnExplore(PLAN_TOKEN))
                .thenThrow(new SharedPlanDirectory.SharedPlanDirectoryUnavailableException("down", null));
        assertThatThrownBy(() -> service.create(AUTHOR, new CreatePostRequest("trips", "Title", "", null, null, PLAN_TOKEN), null))
                .isInstanceOf(GroupException.class)
                .extracting(ex -> ((GroupException) ex).getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verifyNoInteractions(access, posts, pictures);
    }

    @Test void aListedPlanIsStoredWithThePost() {
        when(sharedPlans.isListedOnExplore(PLAN_TOKEN)).thenReturn(true);
        when(access.lock("trips", AUTHOR)).thenReturn(group);
        when(posts.find(any(), eq(AUTHOR))).thenReturn(Optional.empty(), Optional.of(response()));
        var request = new CreatePostRequest("trips", "Title", "", null, null, PLAN_TOKEN);
        service.create(AUTHOR, request, null);
        verify(posts).create(any(), eq(ID), eq(AUTHOR), eq(request), eq(List.of()));
    }

    @Test void postsWithoutAPlanNeverAskTripPlanning() {
        when(access.lock("trips", AUTHOR)).thenReturn(group);
        when(posts.find(any(), eq(AUTHOR))).thenReturn(Optional.empty(), Optional.of(response()));
        service.create(AUTHOR, new CreatePostRequest("trips", "Title", "", null, null, null), null);
        verifyNoInteractions(sharedPlans);
    }

    @Test void rejectsMoreThanTenPicturesBeforeTouchingTheCommunity() {
        var files = new ArrayList<MultipartFile>();
        for (int i = 0; i <= PostService.MAX_PICTURES; i++) files.add(new MockMultipartFile("file", new byte[]{1}));
        assertThatThrownBy(() -> service.create(AUTHOR, new CreatePostRequest("trips", "Title", "", null, null, null), files))
                .isInstanceOf(GroupException.class).hasMessageContaining("at most 10 pictures");
        verifyNoInteractions(access, posts, pictures, sharedPlans);
    }

    @Test void storesEveryPictureInTheOrderTheAuthorChose() {
        when(access.lock("trips", AUTHOR)).thenReturn(group);
        when(posts.find(any(), eq(AUTHOR))).thenReturn(Optional.empty(), Optional.of(response()));
        MultipartFile first = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1});
        MultipartFile second = new MockMultipartFile("file", "b.png", "image/png", new byte[]{2});
        var firstAsset = new PostRepository.ImageAsset("posts/a", "image/png");
        var secondAsset = new PostRepository.ImageAsset("posts/b", "image/jpeg");
        when(pictures.upload(any(), same(first))).thenReturn(firstAsset);
        when(pictures.upload(any(), same(second))).thenReturn(secondAsset);
        var request = new CreatePostRequest("trips", "Title", "", null, null, null);
        service.create(AUTHOR, request, List.of(first, second));
        verify(posts).create(any(), eq(ID), eq(AUTHOR), eq(request), eq(List.of(firstAsset, secondAsset)));
    }

    @Test void retryingAPublishWithADifferentPictureCountIsAConflict() {
        UUID requestId = UUID.fromString("00000000-0000-4000-8000-000000000009");
        when(access.lock("trips", AUTHOR)).thenReturn(group);
        var saved = new PostResponse(requestId, ID, "trips", "Trips", null, AUTHOR, "Title", "", null, null, null,
                "/v1/posts/" + requestId + "/image", Instant.EPOCH, Instant.EPOCH, 0, 0, 0, false, false,
                List.of("/v1/posts/" + requestId + "/images/0"));
        when(posts.find(requestId, AUTHOR)).thenReturn(Optional.of(saved));
        var request = new CreatePostRequest("trips", "Title", "", null, null, null, requestId);
        var files = List.<MultipartFile>of(new MockMultipartFile("file", new byte[]{1}), new MockMultipartFile("file", new byte[]{2}));
        assertThatThrownBy(() -> service.create(AUTHOR, request, files))
                .isInstanceOf(GroupException.class).extracting("status").isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(pictures);
    }

    @Test void galleryPicturesUseTheSameVisibilityGateAsTheirPost() {
        when(posts.find(ID, null)).thenReturn(Optional.of(response()));
        when(access.readable("trips", null)).thenThrow(GroupException.notFound());
        assertThatThrownBy(() -> service.image(ID, 3, null)).isInstanceOf(GroupException.class);
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
        return new PostResponse(ID, ID, "trips", "Trips", null, AUTHOR, "Title", "", null, null, null, null,
                Instant.EPOCH, Instant.EPOCH, 0, 0, 0);
    }
}
