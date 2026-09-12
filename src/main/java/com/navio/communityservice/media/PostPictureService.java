package com.navio.communityservice.media;

import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.repository.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PostPictureService {
    private final ObjectStorage storage;
    private final PostRepository posts;

    /** Called inside the post write transaction, after authorization. */
    public PostRepository.ImageAsset upload(UUID postId, MultipartFile file) {
        var picture = PictureValidator.validate(file);
        String key = "posts/" + postId + "/" + UUID.randomUUID();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) { if (status != STATUS_COMMITTED) cleanup(key); }
        });
        storage.put(key, picture.bytes(), picture.contentType());
        return new PostRepository.ImageAsset(key, picture.contentType());
    }

    public PictureValidator.Picture read(UUID postId) {
        var asset = posts.image(postId).orElseThrow(GroupException::notFound);
        return new PictureValidator.Picture(storage.get(asset.key()), asset.contentType());
    }

    public void deleteAfterCommit(UUID postId) {
        posts.image(postId).ifPresent(asset -> TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { cleanup(asset.key()); }
        }));
    }

    private void cleanup(String key) {
        try { storage.delete(key); }
        catch (RuntimeException ex) { log.warn("Failed to clean up post picture {}", key); }
    }
}
