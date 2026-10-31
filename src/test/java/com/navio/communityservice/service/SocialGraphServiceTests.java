package com.navio.communityservice.service;

import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.repository.NotificationRepository;
import com.navio.communityservice.repository.PostRepository;
import com.navio.communityservice.repository.SocialGraphRepository;
import com.navio.communityservice.repository.SocialGraphRepository.Follow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SocialGraphServiceTests {

    private static final UUID ME = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID THEM = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-4000-8000-000000000003");

    @Mock SocialGraphRepository graph;
    @Mock NotificationRepository notifications;
    @Mock PostRepository posts;
    @InjectMocks SocialGraphService service;

    private static HttpStatus statusOf(Throwable error) {
        return ((GroupException) error).getStatus();
    }

    @Test
    void nobodyCanFollowThemselves() {
        assertThatThrownBy(() -> service.follow(ME, ME)).isInstanceOf(GroupException.class)
                .extracting(SocialGraphServiceTests::statusOf).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(graph, never()).follow(any(), any());
    }

    @Test
    void aBlockInEitherDirectionPreventsFollowing() {
        when(graph.blockedEitherWay(ME, THEM)).thenReturn(true);
        assertThatThrownBy(() -> service.follow(ME, THEM)).extracting(SocialGraphServiceTests::statusOf)
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(graph, never()).follow(any(), any());
        verify(notifications, never()).notifyNewFollower(any(), any());
    }

    @Test
    void aRepeatedFollowDoesNotNotifyAgain() {
        when(graph.follow(ME, THEM)).thenReturn(false);
        lenient().when(graph.connectionsVisibility(THEM)).thenReturn("everyone");
        service.follow(ME, THEM);
        verify(notifications, never()).notifyNewFollower(any(), any());
    }

    @Test
    void aNewFollowNotifiesTheFollowedAccount() {
        when(graph.follow(ME, THEM)).thenReturn(true);
        lenient().when(graph.connectionsVisibility(THEM)).thenReturn("everyone");
        service.follow(ME, THEM);
        verify(notifications).notifyNewFollower(ME, THEM);
    }

    @Test
    void blockingRemovesFollowsBothWaysAndTheirNotifications() {
        lenient().when(graph.connectionsVisibility(THEM)).thenReturn("everyone");
        service.block(ME, THEM);
        verify(graph).block(ME, THEM);
        verify(graph).removeFollowsBetween(ME, THEM);
        verify(notifications).deleteBetween(ME, THEM);
    }

    @Test
    void changingAlertsRequiresAnExistingFollow() {
        when(graph.setNotify(ME, THEM, false)).thenReturn(false);
        assertThatThrownBy(() -> service.setAlerts(ME, THEM, false)).extracting(SocialGraphServiceTests::statusOf)
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void followersOnlyListsAreHiddenFromNonFollowers() {
        when(graph.connectionsVisibility(THEM)).thenReturn("followers");
        when(graph.findFollow(STRANGER, THEM)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.followers(THEM, STRANGER, null, 0, 20)).extracting(SocialGraphServiceTests::statusOf)
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(graph, never()).followers(any(), any(), any(), any());
    }

    @Test
    void followersOnlyListsAreVisibleToFollowersAndTheOwner() {
        when(graph.connectionsVisibility(THEM)).thenReturn("followers");
        when(graph.findFollow(ME, THEM)).thenReturn(Optional.of(new Follow(true)));
        service.followers(THEM, ME, null, 0, 20);
        service.following(THEM, THEM, null, 0, 20);
        verify(graph).followers(any(), any(), any(), any());
        verify(graph).following(any(), any(), any(), any());
    }

    @Test
    void onlyMeListsAreHiddenEvenFromFollowers() {
        when(graph.connectionsVisibility(THEM)).thenReturn("only_me");
        assertThatThrownBy(() -> service.following(THEM, ME, null, 0, 20)).extracting(SocialGraphServiceTests::statusOf)
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void searchNarrowingIsBounded() {
        List<UUID> tooMany = java.util.stream.IntStream.range(0, 101).mapToObj(index -> UUID.randomUUID()).toList();
        assertThatThrownBy(() -> service.followers(THEM, ME, tooMany, 0, 20)).extracting(SocialGraphServiceTests::statusOf)
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void blockedAccountsCannotReadEachOthersProfilePosts() {
        when(graph.blockedEitherWay(THEM, ME)).thenReturn(true);
        assertThatThrownBy(() -> service.profilePosts(THEM, ME, 0, 20)).extracting(SocialGraphServiceTests::statusOf)
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(posts, never()).listByAuthor(any(), any(), any());
    }

    @Test
    void interactingWithYourOwnContentIsAlwaysAllowed() {
        assertThatCode(() -> service.requireCanInteract(ME, ME)).doesNotThrowAnyException();
        when(graph.blockedEitherWay(ME, THEM)).thenReturn(true);
        assertThatThrownBy(() -> service.requireCanInteract(ME, THEM)).isInstanceOf(GroupException.class);
    }

    @Test
    void theSummaryReportsBothBlockDirectionsSeparately() {
        when(graph.connectionsVisibility(THEM)).thenReturn("everyone");
        when(graph.hasBlocked(ME, THEM)).thenReturn(false);
        when(graph.hasBlocked(THEM, ME)).thenReturn(true);
        var summary = service.summary(THEM, ME);
        assertThat(summary.viewerBlocked()).isFalse();
        assertThat(summary.blockedViewer()).isTrue();
        assertThat(summary.self()).isFalse();
    }
}
