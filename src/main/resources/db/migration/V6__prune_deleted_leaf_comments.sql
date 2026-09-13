-- Deleted comments are only kept as placeholders while they still have replies.
-- Remove existing placeholders without replies, repeating so emptied parent chains are removed too.
DO $$
BEGIN
    LOOP
        DELETE FROM social.comments c
        WHERE c.deleted
          AND NOT EXISTS (SELECT 1 FROM social.comments r WHERE r.parent_comment_id = c.id);
        EXIT WHEN NOT FOUND;
    END LOOP;
END $$;
