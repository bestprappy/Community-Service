-- =============================================================================
--  Following, blocking, reporting, profile pins and in-app notifications.
-- =============================================================================
--  Additive only: new tables plus one nullable column on social.posts, so the
--  previous image keeps working against this schema after a rollback. User ids
--  are Keycloak subjects, like every other author/member id in this service.
-- =============================================================================

-- One row per (follower, followee). notify=false is "follow without alerts".
CREATE TABLE social.user_follows (
    follower_id UUID        NOT NULL,
    followee_id UUID        NOT NULL,
    notify      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_social_user_follows PRIMARY KEY (follower_id, followee_id),
    CONSTRAINT ck_social_user_follows_not_self CHECK (follower_id <> followee_id)
);
CREATE INDEX idx_social_user_follows_followee ON social.user_follows (followee_id, created_at DESC);
CREATE INDEX idx_social_user_follows_follower ON social.user_follows (follower_id, created_at DESC);

-- A block is one-directional in storage but symmetric in effect: neither side
-- can follow, interact with or notify the other.
CREATE TABLE social.user_blocks (
    blocker_id UUID        NOT NULL,
    blocked_id UUID        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_social_user_blocks PRIMARY KEY (blocker_id, blocked_id),
    CONSTRAINT ck_social_user_blocks_not_self CHECK (blocker_id <> blocked_id)
);
CREATE INDEX idx_social_user_blocks_blocked ON social.user_blocks (blocked_id);

-- Who may see a user's follower and following lists. Counts stay visible.
CREATE TABLE social.social_settings (
    user_id                UUID PRIMARY KEY,
    connections_visibility VARCHAR(20) NOT NULL DEFAULT 'everyone',
    updated_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_social_settings_visibility CHECK (connections_visibility IN ('everyone', 'followers', 'only_me'))
);

-- Author's choice to feature a post on their public profile. Deleting the post
-- removes the pin with it; a hidden community hides it from the profile.
ALTER TABLE social.posts ADD COLUMN IF NOT EXISTS profile_pinned_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS posts_author_created ON social.posts (author_id, created_at DESC, id);

-- Reports wait in a review queue for platform moderators.
CREATE TABLE social.reports (
    id              UUID PRIMARY KEY,
    reporter_id     UUID          NOT NULL,
    target_type     VARCHAR(20)   NOT NULL,
    target_id       UUID          NOT NULL,
    target_user_id  UUID,
    reason          VARCHAR(30)   NOT NULL,
    details         VARCHAR(1000),
    status          VARCHAR(20)   NOT NULL DEFAULT 'open',
    resolution_note VARCHAR(1000),
    resolved_by     UUID,
    resolved_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_social_reports_target CHECK (target_type IN ('user', 'post', 'comment')),
    CONSTRAINT ck_social_reports_reason CHECK (reason IN ('spam', 'harassment', 'hate', 'inappropriate',
                                                          'impersonation', 'misinformation', 'other')),
    CONSTRAINT ck_social_reports_status CHECK (status IN ('open', 'actioned', 'dismissed')),
    CONSTRAINT ck_social_reports_resolution CHECK ((status = 'open') = (resolved_at IS NULL))
);
-- One open report per reporter and target: repeated clicks do not flood the queue.
CREATE UNIQUE INDEX uq_social_reports_open ON social.reports (reporter_id, target_type, target_id) WHERE status = 'open';
CREATE INDEX idx_social_reports_queue ON social.reports (status, created_at);
CREATE INDEX idx_social_reports_reporter ON social.reports (reporter_id, created_at DESC);

-- In-app notifications. dedupe_key makes every fan-out idempotent per recipient.
CREATE TABLE notif.notifications (
    id           UUID PRIMARY KEY,
    recipient_id UUID         NOT NULL,
    type         VARCHAR(30)  NOT NULL,
    actor_id     UUID         NOT NULL,
    post_id      UUID REFERENCES social.posts (id) ON DELETE CASCADE,
    plan_token   VARCHAR(64),
    plan_title   VARCHAR(200),
    dedupe_key   VARCHAR(200) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    read_at      TIMESTAMPTZ,
    CONSTRAINT ck_notif_notifications_type CHECK (type IN ('new_follower', 'followed_post', 'followed_plan')),
    CONSTRAINT ck_notif_notifications_subject CHECK (
        (type = 'new_follower' AND post_id IS NULL AND plan_token IS NULL)
        OR (type = 'followed_post' AND post_id IS NOT NULL)
        OR (type = 'followed_plan' AND plan_token IS NOT NULL)),
    CONSTRAINT uq_notif_notifications_dedupe UNIQUE (recipient_id, dedupe_key)
);
CREATE INDEX idx_notif_notifications_recipient ON notif.notifications (recipient_id, created_at DESC, id);
CREATE INDEX idx_notif_notifications_unread ON notif.notifications (recipient_id) WHERE read_at IS NULL;
CREATE INDEX idx_notif_notifications_actor ON notif.notifications (actor_id, recipient_id);

-- Per-type opt-outs. A missing row means every type is on.
CREATE TABLE notif.preferences (
    user_id       UUID PRIMARY KEY,
    new_follower  BOOLEAN     NOT NULL DEFAULT TRUE,
    followed_post BOOLEAN     NOT NULL DEFAULT TRUE,
    followed_plan BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
