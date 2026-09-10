CREATE TABLE media.group_media (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES social.groups(id) ON DELETE CASCADE,
    uploaded_by_user_id UUID NOT NULL,
    object_key VARCHAR(255) NOT NULL UNIQUE,
    content_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (group_id, id)
);
-- The old API accepted unverified identifiers. Preserve external URLs for now,
-- but clear identifiers that never referred to managed storage.
UPDATE social.group_profiles SET banner_media_id = NULL;
ALTER TABLE social.group_profiles ADD CONSTRAINT group_banner_media_fk
    FOREIGN KEY (group_id, banner_media_id) REFERENCES media.group_media(group_id, id);
