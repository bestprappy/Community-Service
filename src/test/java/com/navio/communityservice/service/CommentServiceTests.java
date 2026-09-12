package com.navio.communityservice.service;

import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.repository.CommentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
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
}
