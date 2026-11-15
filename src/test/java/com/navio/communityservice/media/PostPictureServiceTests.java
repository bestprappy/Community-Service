package com.navio.communityservice.media;

import com.navio.communityservice.repository.PostRepository;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.*;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PostPictureServiceTests {
    ObjectStorage storage = mock(ObjectStorage.class);
    PostRepository posts = mock(PostRepository.class);
    PostPictureService service = new PostPictureService(storage, posts);
    @BeforeEach void start() { TransactionSynchronizationManager.initSynchronization(); }
    @AfterEach void stop() { TransactionSynchronizationManager.clearSynchronization(); }

    @Test void failedStorageWritesAreCleanedUpOnRollback() throws Exception {
        doThrow(new RuntimeException("storage timeout")).when(storage).put(anyString(), any(), anyString());
        assertThatThrownBy(() -> service.upload(UUID.randomUUID(), new MockMultipartFile("file", PictureValidatorTests.png(2,2))))
                .isInstanceOf(RuntimeException.class);
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        verify(storage).delete(startsWith("posts/"));
    }

    @Test void committedPicturesAreKept() throws Exception {
        var asset = service.upload(UUID.randomUUID(), new MockMultipartFile("file", PictureValidatorTests.png(2,2)));
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        assertThat(asset.contentType()).isEqualTo("image/png");
        verify(storage, never()).delete(anyString());
    }

    @Test void deletingAPostRemovesEveryGalleryPictureOnlyAfterCommit() {
        UUID id = UUID.randomUUID();
        when(posts.imageKeys(id)).thenReturn(new java.util.LinkedHashSet<>(java.util.List.of("posts/a", "posts/b", "posts/c")));
        service.deleteAfterCommit(id);
        verify(storage, never()).delete(anyString());
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(storage).delete("posts/a"); verify(storage).delete("posts/b"); verify(storage).delete("posts/c");
    }

    @Test void oneFailedCleanupDoesNotStopTheRest() {
        UUID id = UUID.randomUUID();
        when(posts.imageKeys(id)).thenReturn(new java.util.LinkedHashSet<>(java.util.List.of("posts/a", "posts/b")));
        doThrow(new RuntimeException("gone")).when(storage).delete("posts/a");
        service.deleteAfterCommit(id);
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        verify(storage).delete("posts/b");
    }
}
