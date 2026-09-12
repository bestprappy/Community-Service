CREATE TABLE social.posts (
    id UUID PRIMARY KEY,
    group_id UUID NOT NULL REFERENCES social.groups(id) ON DELETE CASCADE,
    author_id UUID NOT NULL,
    title VARCHAR(300) NOT NULL,
    body TEXT NOT NULL,
    link_url VARCHAR(2048),
    flair_id UUID REFERENCES social.group_flairs(id) ON DELETE SET NULL,
    shared_trip_id VARCHAR(160),
    image_key VARCHAR(255),
    image_content_type VARCHAR(30),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX posts_group_created ON social.posts(group_id, created_at DESC, id);
CREATE INDEX posts_created ON social.posts(created_at DESC, id);
CREATE TABLE social.comments (
    id UUID PRIMARY KEY,
    post_id UUID NOT NULL REFERENCES social.posts(id) ON DELETE CASCADE,
    parent_comment_id UUID,
    author_id UUID NOT NULL,
    body TEXT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(post_id, id),
    FOREIGN KEY(post_id, parent_comment_id) REFERENCES social.comments(post_id, id)
);
CREATE INDEX comments_post_created ON social.comments(post_id, created_at, id);
CREATE TABLE social.post_votes (
    post_id UUID NOT NULL REFERENCES social.posts(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    value SMALLINT NOT NULL CHECK(value IN (-1, 1)),
    PRIMARY KEY(post_id, user_id)
);
CREATE TABLE social.comment_votes (
    comment_id UUID NOT NULL REFERENCES social.comments(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    value SMALLINT NOT NULL CHECK(value IN (-1, 1)),
    PRIMARY KEY(comment_id, user_id)
);
