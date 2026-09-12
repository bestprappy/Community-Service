package com.navio.communityservice.service;

import com.navio.communityservice.dto.*;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.media.*;
import com.navio.communityservice.model.Group;
import com.navio.communityservice.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.util.*;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {
    private final PostRepository posts;
    private final GroupAccessService access;
    private final GroupFlairRepository flairs;
    private final PostPictureService pictures;

    public Page<PostResponse> list(String slug, UUID actor, String query, String sort, int page, int size) {
        if (!List.of("new", "top", "best").contains(sort)) throw GroupException.invalid("Unknown post sort");
        if (query.length() > 200) throw GroupException.invalid("Search must be at most 200 characters");
        UUID groupId = slug == null || slug.isBlank() ? null : access.readable(slug, actor).getId();
        return posts.list(groupId, actor, query.strip(), sort, pagination(page, size));
    }

    public PostResponse read(UUID id, UUID actor) {
        var post = find(id, actor);
        access.readable(post.groupSlug(), actor);
        return post;
    }

    public PictureValidator.Picture image(UUID id, UUID actor) {
        read(id, actor);
        return pictures.read(id);
    }

    @Transactional
    public PostResponse create(UUID actor, CreatePostRequest r, MultipartFile file) {
        Group group = access.lock(r.groupSlug(), actor);
        access.requireActiveMember(group, actor);
        UUID id = r.requestId() == null ? UUID.randomUUID() : r.requestId();
        var existing = posts.find(id, actor);
        if (existing.isPresent()) {
            var saved = existing.get();
            if (!saved.authorId().equals(actor) || !saved.groupId().equals(group.getId())
                    || !saved.title().equals(r.title().strip()) || !saved.body().equals(r.body().strip())
                    || !Objects.equals(saved.linkUrl(), r.linkUrl()) || !Objects.equals(saved.flairId(), r.flairId())
                    || !Objects.equals(saved.sharedTripId(), r.sharedTripId()) || (saved.imageUrl() != null) != (file != null)) {
                throw GroupException.conflict("This publish request was already used; start a new post");
            }
            return saved;
        }
        if (r.flairId() != null && flairs.findById(r.flairId())
                .filter(f -> group.getId().equals(f.getGroupId()) && "post".equals(f.getFlairType())).isEmpty()) {
            throw GroupException.invalid("Select a post flair from this community");
        }
        var image = file == null ? null : pictures.upload(id, file);
        posts.create(id, group.getId(), actor, r, image == null ? null : image.key(), image == null ? null : image.contentType());
        group.setPostCount(group.getPostCount() + 1);
        return find(id, actor);
    }

    @Transactional
    public PostResponse update(UUID id, UUID actor, UpdatePostRequest r) {
        var post = writable(id, actor);
        if (!post.authorId().equals(actor)) throw GroupException.forbidden("Only the author can edit this post");
        posts.update(id, r);
        return find(id, actor);
    }

    @Transactional
    public void delete(UUID id, UUID actor) {
        var initial = find(id, actor);
        Group group = access.lock(initial.groupSlug(), actor);
        access.requireActiveMember(group, actor);
        var post = find(id, actor); // Re-read after the shared group lock to avoid concurrent delete/count races.
        requireAuthorOrModerator(post.groupId(), post.authorId(), actor);
        pictures.deleteAfterCommit(id);
        posts.delete(id);
        group.setPostCount(Math.max(0, group.getPostCount() - 1));
    }

    @Transactional
    public PostResponse vote(UUID id, UUID actor, int value) {
        writable(id, actor);
        posts.vote(id, actor, value);
        return find(id, actor);
    }

    /** All post/comment writers serialize with membership, status and moderation changes. */
    public PostResponse writable(UUID id, UUID actor) {
        var initial = find(id, actor);
        var group = access.lock(initial.groupSlug(), actor);
        access.requireActiveMember(group, actor);
        return find(id, actor);
    }

    public void requireAuthorOrModerator(UUID groupId, UUID author, UUID actor) {
        if (!author.equals(actor)) access.requireModerator(groupId, actor);
    }

    private PostResponse find(UUID id, UUID actor) { return posts.find(id, actor).orElseThrow(GroupException::notFound); }

    public static Pageable pagination(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw GroupException.invalid("Page must be nonnegative and size between 1 and 100");
        return PageRequest.of(page, size);
    }
}
