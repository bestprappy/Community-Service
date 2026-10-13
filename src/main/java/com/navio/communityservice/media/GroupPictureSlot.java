package com.navio.communityservice.media;

import com.navio.communityservice.model.GroupProfile;
import java.util.UUID;

/** The pictures a community profile owns. Each slot references at most one stored media row. */
public enum GroupPictureSlot {
    BANNER("banner"), ICON("icon");

    private final String path;
    GroupPictureSlot(String path) { this.path = path; }
    public String path() { return path; }

    UUID mediaId(GroupProfile profile) {
        return this == BANNER ? profile.getBannerMediaId() : profile.getIconMediaId();
    }

    /** The banner also keeps its legacy URL column, which older clients read. */
    void assign(GroupProfile profile, UUID mediaId, String url) {
        if (this == BANNER) {
            profile.setBannerMediaId(mediaId);
            profile.setBannerUrl(url);
        } else {
            profile.setIconMediaId(mediaId);
        }
    }
}
