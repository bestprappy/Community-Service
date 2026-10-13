package com.navio.communityservice.service;

import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.repository.CommentRepository;
import com.navio.communityservice.support.CommentReaction;
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
class CommentServiceTests {
    @Mock PostService posts;
    @Mock CommentRepository comments;
    @InjectMocks CommentService service;
    private static final UUID POST = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000003");
    private static final UUID PARENT = UUID.fromString("00000000-0000-4000-8000-000000000004");
    private static final UUID COMMENT = UUID.fromString("00000000-0000-4000-8000-000000000005");
    private static final UUID GROUP = UUID.fromString("00000000-0000-4000-8000-000000000006");

    @Test void repliesCannotReferenceCommentsFromAnotherPost() {
        when(comments.find(POST, USER, USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(POST, USER, new CreateCommentRequest("Reply", USER))).isInstanceOf(GroupException.class);
        verify(comments, never()).create(any(), any(), any(), any());
    }

    @Test void membershipRejectionPreventsCommentWrites() {
        when(posts.writable(POST, USER)).thenThrow(GroupException.forbidden("Join first"));
        assertThatThrownBy(() -> service.create(POST, USER, new CreateCommentRequest("Reply", null))).isInstanceOf(GroupException.class);
        verifyNoInteractions(comments);
    }

    @Test void authorsEditTheirOwnComment() {
        when(comments.find(POST, COMMENT, USER)).thenReturn(Optional.of(comment(COMMENT, null, USER, false)));
        service.update(POST, COMMENT, USER, new UpdateCommentRequest("Fixed typo"));
        verify(comments).update(COMMENT, "Fixed typo");
    }

    @Test void moderatorsCannotRewriteSomeoneElsesComment() {
        when(comments.find(POST, COMMENT, OTHER)).thenReturn(Optional.of(comment(COMMENT, null, USER, false)));
        assertThatThrownBy(() -> service.update(POST, COMMENT, OTHER, new UpdateCommentRequest("Rewritten")))
                .isInstanceOfSatisfying(GroupException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(403));
        verify(comments, never()).update(any(), any());
    }

    @Test void deletedCommentsCannotBeEdited() {
        when(comments.find(POST, COMMENT, USER)).thenReturn(Optional.of(comment(COMMENT, null, USER, true)));
        assertThatThrownBy(() -> service.update(POST, COMMENT, USER, new UpdateCommentRequest("Back again")))
                .isInstanceOfSatisfying(GroupException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(400));
        verify(comments, never()).update(any(), any());
    }

    @Test void deletingACommentWithRepliesLeavesAnUnavailablePlaceholder() {
        when(posts.writable(POST, USER)).thenReturn(post());
        when(comments.find(POST, COMMENT, USER)).thenReturn(Optional.of(comment(COMMENT, null, USER, false)));
        when(comments.hasReplies(COMMENT)).thenReturn(true);
        service.delete(POST, COMMENT, USER);
        verify(comments).softDelete(COMMENT);
        verify(comments, never()).remove(any());
    }

    @Test void deletingACommentWithoutRepliesRemovesItEntirely() {
        when(posts.writable(POST, USER)).thenReturn(post());
        when(comments.find(POST, COMMENT, USER)).thenReturn(Optional.of(comment(COMMENT, PARENT, USER, false)));
        when(comments.find(POST, PARENT, USER)).thenReturn(Optional.of(comment(PARENT, null, OTHER, false)));
        service.delete(POST, COMMENT, USER);
        verify(comments).remove(COMMENT);
        verify(comments, never()).softDelete(any());
        verify(comments, never()).remove(PARENT);
    }

    @Test void removingTheLastReplyAlsoRemovesItsDeletedParent() {
        when(posts.writable(POST, USER)).thenReturn(post());
        when(comments.find(POST, COMMENT, USER)).thenReturn(Optional.of(comment(COMMENT, PARENT, USER, false)));
        when(comments.find(POST, PARENT, USER)).thenReturn(Optional.of(comment(PARENT, null, OTHER, true)));
        service.delete(POST, COMMENT, USER);
        verify(comments).remove(COMMENT);
        verify(comments).remove(PARENT);
    }

    @Test void deletedParentsWithOtherRepliesStayAsPlaceholders() {
        when(posts.writable(POST, USER)).thenReturn(post());
        when(comments.find(POST, COMMENT, USER)).thenReturn(Optional.of(comment(COMMENT, PARENT, USER, false)));
        when(comments.find(POST, PARENT, USER)).thenReturn(Optional.of(comment(PARENT, null, OTHER, true)));
        when(comments.hasReplies(COMMENT)).thenReturn(false);
        when(comments.hasReplies(PARENT)).thenReturn(true);
        service.delete(POST, COMMENT, USER);
        verify(comments).remove(COMMENT);
        verify(comments, never()).remove(PARENT);
    }

    @Test void unknownReactionsAreRejectedBeforeTouchingThePost() {
        assertThatThrownBy(() -> service.react(POST, COMMENT, USER, "angry"))
                .isInstanceOfSatisfying(GroupException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(400));
        verifyNoInteractions(posts, comments);
    }

    @Test void nonMembersCannotReact() {
        when(posts.writable(POST, USER)).thenThrow(GroupException.forbidden("Active group membership is required"));
        assertThatThrownBy(() -> service.react(POST, COMMENT, USER, "love"))
                .isInstanceOfSatisfying(GroupException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(403));
        verifyNoInteractions(comments);
    }

    @Test void deletedCommentsCannotReceiveReactions() {
        when(comments.find(POST, COMMENT, USER)).thenReturn(Optional.of(comment(COMMENT, null, OTHER, true)));
        assertThatThrownBy(() -> service.react(POST, COMMENT, USER, "helpful"))
                .isInstanceOfSatisfying(GroupException.class, ex -> assertThat(ex.getStatus().value()).isEqualTo(400));
        verify(comments, never()).react(any(), any(), any());
    }

    @Test void membersReactAndRemoveTheirOwnReaction() {
        when(comments.find(POST, COMMENT, USER)).thenReturn(Optional.of(comment(COMMENT, null, OTHER, false)));
        service.react(POST, COMMENT, USER, "want_to_go");
        service.unreact(POST, COMMENT, USER, "want_to_go");
        verify(comments).react(COMMENT, USER, CommentReaction.WANT_TO_GO);
        verify(comments).unreact(COMMENT, USER, CommentReaction.WANT_TO_GO);
    }

    private static CommentResponse comment(UUID id, UUID parent, UUID author, boolean deleted) {
        return new CommentResponse(id, POST, parent, author, deleted ? "" : "Text", deleted, false,
                Instant.parse("2026-09-12T00:00:00Z"), 0, 0);
    }

    private static PostResponse post() {
        return new PostResponse(POST, GROUP, "trips", "Trips", null, OTHER, "A trip", "Notes", null, null, null, null,
                Instant.parse("2026-09-12T00:00:00Z"), Instant.parse("2026-09-12T00:00:00Z"), 0, 0, 0);
    }
}
