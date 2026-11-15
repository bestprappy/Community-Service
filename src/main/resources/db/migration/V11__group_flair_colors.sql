-- Flairs take a color from the admin palette instead of a fixed tone.
-- Nullable so an image from before this release can still insert rows; the
-- service resolves a missing color from the row's tone.
ALTER TABLE social.group_flairs ADD COLUMN IF NOT EXISTS color_id VARCHAR(40);

UPDATE social.group_flairs SET color_id = CASE tone
    WHEN 'reliable' THEN 'emerald'
    WHEN 'question' THEN 'blue'
    WHEN 'unsourced' THEN 'graphite'
    WHEN 'speculation' THEN 'amber'
    WHEN 'itinerary' THEN 'teal'
    WHEN 'food' THEN 'coral'
    WHEN 'ev' THEN 'lime'
    ELSE 'teal'
END
WHERE color_id IS NULL;
