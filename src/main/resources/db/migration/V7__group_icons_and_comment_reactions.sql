-- Additive only: the previous image ignores the new column and table.
ALTER TABLE social.group_profiles ADD COLUMN icon_media_id UUID;
ALTER TABLE social.group_profiles ADD CONSTRAINT group_icon_media_fk
    FOREIGN KEY (group_id, icon_media_id) REFERENCES media.group_media(group_id, id);

CREATE TABLE social.comment_reactions (
    comment_id UUID NOT NULL REFERENCES social.comments(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    reaction VARCHAR(20) NOT NULL
        CHECK (reaction IN ('helpful', 'love', 'funny', 'thanks', 'want_to_go')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (comment_id, user_id, reaction)
);
