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
    PostPictureService service = new PostPictureService(storage, mock(PostRepository.class));
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
}
