ALTER TABLE social.groups ADD COLUMN owner_user_id UUID;
UPDATE social.groups SET owner_user_id = created_by_user_id;
ALTER TABLE social.groups ALTER COLUMN owner_user_id SET NOT NULL;

-- Repair founders demoted or allowed to leave by the former moderator replacement API.
INSERT INTO social.group_memberships (group_id, user_id, role, state)
SELECT id, owner_user_id, 'admin', 'joined' FROM social.groups
ON CONFLICT (group_id, user_id) DO UPDATE SET role = 'admin',
    state = CASE WHEN social.group_memberships.state = 'muted' THEN 'muted' ELSE 'joined' END;
UPDATE social.group_memberships m SET role = 'moderator'
FROM social.groups g WHERE m.group_id = g.id AND m.user_id <> g.owner_user_id AND m.role = 'admin';
UPDATE social.groups g SET member_count = (
    SELECT count(*) FROM social.group_memberships m WHERE m.group_id = g.id AND m.state IN ('joined', 'muted'));
UPDATE social.group_profiles p SET moderator_ids = ARRAY(
    SELECT m.user_id FROM social.group_memberships m WHERE m.group_id = p.group_id
        AND m.role IN ('admin', 'moderator') AND m.state IN ('joined', 'muted') ORDER BY m.user_id);
