-- Text-only wrapper: unlike polymorphic array_to_string, TEXT[] formatting is immutable.
CREATE FUNCTION social.group_search_text(input_values TEXT[]) RETURNS TEXT
LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
AS $$ SELECT array_to_string(input_values, ' ') $$;

CREATE TABLE IF NOT EXISTS social.groups (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(140) NOT NULL UNIQUE,
    description TEXT NOT NULL,
    country VARCHAR(120),
    places TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    tags TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    created_by_user_id UUID NOT NULL,
    is_official BOOLEAN NOT NULL DEFAULT false,
    status VARCHAR(30) NOT NULL DEFAULT 'active'
        CHECK (status IN ('active', 'archived', 'hidden')),
    member_count INT NOT NULL DEFAULT 0 CHECK (member_count >= 0),
    post_count INT NOT NULL DEFAULT 0 CHECK (post_count >= 0),
    weekly_visitor_count INT NOT NULL DEFAULT 0 CHECK (weekly_visitor_count >= 0),
    weekly_contribution_count INT NOT NULL DEFAULT 0 CHECK (weekly_contribution_count >= 0),
    search_vector TSVECTOR GENERATED ALWAYS AS (
        to_tsvector('simple', coalesce(name, '') || ' ' || coalesce(description, '') || ' ' ||
            coalesce(country, '') || ' ' || social.group_search_text(places) || ' ' || social.group_search_text(tags))
    ) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_social_groups_search ON social.groups USING GIN(search_vector);
CREATE INDEX IF NOT EXISTS idx_social_groups_tags ON social.groups USING GIN(tags);
CREATE INDEX IF NOT EXISTS idx_social_groups_discovery ON social.groups(status, is_official DESC, member_count DESC);

CREATE TABLE IF NOT EXISTS social.group_profiles (
    group_id UUID PRIMARY KEY REFERENCES social.groups(id) ON DELETE CASCADE,
    banner_media_id UUID,
    banner_url TEXT,
    summary TEXT NOT NULL,
    moderator_ids UUID[] NOT NULL DEFAULT ARRAY[]::UUID[],
    metadata_jsonb JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS social.group_memberships (
    group_id UUID NOT NULL REFERENCES social.groups(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    role VARCHAR(30) NOT NULL DEFAULT 'member'
        CHECK (role IN ('member', 'moderator', 'admin')),
    state VARCHAR(30) NOT NULL DEFAULT 'joined'
        CHECK (state IN ('joined', 'muted', 'banned', 'left')),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_social_group_memberships_user ON social.group_memberships(user_id, state, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_social_group_memberships_group_state ON social.group_memberships(group_id, state);

CREATE TABLE IF NOT EXISTS social.group_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES social.groups(id) ON DELETE CASCADE,
    title VARCHAR(120) NOT NULL,
    description TEXT NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_social_group_rules_order ON social.group_rules(group_id, display_order, created_at);

CREATE TABLE IF NOT EXISTS social.group_flairs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES social.groups(id) ON DELETE CASCADE,
    flair_type VARCHAR(20) NOT NULL CHECK (flair_type IN ('post', 'user')),
    label VARCHAR(80) NOT NULL,
    tone VARCHAR(30) NOT NULL DEFAULT 'reliable',
    display_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_social_group_flairs_group_type ON social.group_flairs(group_id, flair_type, display_order);

CREATE TABLE IF NOT EXISTS social.group_resources (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id UUID NOT NULL REFERENCES social.groups(id) ON DELETE CASCADE,
    label VARCHAR(120) NOT NULL,
    url TEXT,
    display_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_social_group_resources_order ON social.group_resources(group_id, display_order, created_at);

