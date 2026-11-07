CREATE TABLE social.saved_posts (
 user_id uuid NOT NULL, post_id uuid NOT NULL REFERENCES social.posts(id) ON DELETE CASCADE,
 saved_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id,post_id)
);
CREATE INDEX saved_posts_user_time ON social.saved_posts(user_id,saved_at DESC);
CREATE TABLE social.post_visits (
 user_id uuid NOT NULL, post_id uuid NOT NULL REFERENCES social.posts(id) ON DELETE CASCADE,
 opened_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id,post_id)
);
CREATE INDEX post_visits_user_time ON social.post_visits(user_id,opened_at DESC);
CREATE INDEX post_visits_expiry ON social.post_visits(opened_at);
