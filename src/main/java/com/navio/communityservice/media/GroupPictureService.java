package com.navio.communityservice.media;
import com.navio.communityservice.dto.GroupDetailResponse;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.model.*;
import com.navio.communityservice.repository.*;
import com.navio.communityservice.service.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import java.time.Instant;
import java.util.UUID;

@Service @Slf4j
public class GroupPictureService {
    private final GroupAccessService access;
    private final GroupProfileRepository profiles;
    private final GroupMediaRepository media;
    private final GroupViewService views;
    private final ObjectStorage storage;
    private final String publicBaseUrl;
    public GroupPictureService(GroupAccessService access, GroupProfileRepository profiles, GroupMediaRepository media,
            GroupViewService views, ObjectStorage storage, @Value("${navio.community.media.public-base-url:}") String publicBaseUrl) {
        this.access = access; this.profiles = profiles; this.media = media; this.views = views;
        this.storage = storage; this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
    }
    public String bannerUrl(String slug) { return publicBaseUrl + "/v1/groups/" + slug + "/banner"; }
    @Transactional
    public void remove(String slug, UUID actor) {
        Group group = access.lock(slug, actor);
        access.requireContentEditor(group, actor);
        GroupProfile profile = profiles.findById(group.getId()).orElseThrow();
        GroupMedia previous = profile.getBannerMediaId() == null ? null : media.findById(profile.getBannerMediaId()).orElse(null);
        profile.setBannerMediaId(null);
        profile.setBannerUrl(null);
        profiles.saveAndFlush(profile);
        if (previous != null) {
            media.delete(previous);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cleanup(previous.getObjectKey()); }
            });
        }
        group.setUpdatedAt(Instant.now());
    }
    @Transactional
    public GroupDetailResponse upload(String slug, UUID actor, MultipartFile file) {
        Group group = access.lock(slug, actor);
        access.requireContentEditor(group, actor);
        var picture = PictureValidator.validate(file);
        GroupProfile profile = profiles.findById(group.getId()).orElseThrow();
        GroupMedia previous = profile.getBannerMediaId() == null ? null : media.findById(profile.getBannerMediaId()).orElse(null);
        UUID id = UUID.randomUUID();
        String key = "groups/" + group.getId() + "/" + id;
        // Registered before the PUT to also clean up ambiguous timeout outcomes.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) cleanup(key);
            }
        });
        storage.put(key, picture.bytes(), picture.contentType());
        media.saveAndFlush(GroupMedia.builder().id(id).groupId(group.getId()).uploadedByUserId(actor)
                .objectKey(key).contentType(picture.contentType()).sizeBytes(picture.bytes().length).createdAt(Instant.now()).build());
        profile.setBannerMediaId(id);
        profile.setBannerUrl(bannerUrl(slug));
        profiles.saveAndFlush(profile); // Switch the FK before removing the previous metadata row.
        if (previous != null) {
            media.delete(previous);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cleanup(previous.getObjectKey()); }
            });
        }
        group.setUpdatedAt(Instant.now());
        return views.detail(group, actor);
    }
    @Transactional(readOnly = true)
    public PictureValidator.Picture read(String slug, UUID actor) {
        Group group = access.readable(slug, actor);
        UUID id = profiles.findById(group.getId()).orElseThrow().getBannerMediaId();
        if (id == null) throw GroupException.notFound();
        GroupMedia asset = media.findById(id).filter(m -> group.getId().equals(m.getGroupId())).orElseThrow(GroupException::notFound);
        return new PictureValidator.Picture(storage.get(asset.getObjectKey()), asset.getContentType());
    }
    private void cleanup(String key) {
        try { storage.delete(key); }
        catch (RuntimeException ex) { log.warn("Failed to clean up uncommitted picture object {}", key); }
    }
}
