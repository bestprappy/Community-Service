CREATE EXTENSION IF NOT EXISTS pg_trgm;

COMMENT ON SCHEMA social IS 'Community groups, posts, comments, moderation, and event outbox data';
COMMENT ON SCHEMA notif IS 'Community-owned notifications and consumed integration events';
COMMENT ON SCHEMA media IS 'Community-owned media metadata and upload sessions';
