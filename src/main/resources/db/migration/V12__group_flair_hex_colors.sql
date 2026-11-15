-- Flairs take any color a moderator picks, plus white or black text, instead
-- of an admin palette id. Nullable so an image from before this release can
-- still insert rows; the service resolves missing values from color_id/tone.
ALTER TABLE social.group_flairs ADD COLUMN IF NOT EXISTS color_hex VARCHAR(7);
ALTER TABLE social.group_flairs ADD COLUMN IF NOT EXISTS text_color VARCHAR(5);

UPDATE social.group_flairs SET color_hex = CASE color_id
    WHEN 'teal' THEN '#00ae84' WHEN 'cyan' THEN '#00b2b2' WHEN 'blue' THEN '#008cdf'
    WHEN 'indigo' THEN '#5d67ee' WHEN 'violet' THEN '#8654cc' WHEN 'rose' THEN '#d83e89'
    WHEN 'coral' THEN '#f0584b' WHEN 'amber' THEN '#ea8a18' WHEN 'emerald' THEN '#009342'
    WHEN 'pine' THEN '#00603c' WHEN 'navy' THEN '#005189' WHEN 'slate' THEN '#3b4372'
    WHEN 'plum' THEN '#613888' WHEN 'wine' THEN '#972767' WHEN 'brick' THEN '#ac312a'
    WHEN 'rust' THEN '#a94700' WHEN 'lime' THEN '#56932b' WHEN 'olive' THEN '#677014'
    WHEN 'gold' THEN '#aa7e00' WHEN 'sky' THEN '#089ac3' WHEN 'fuchsia' THEN '#b243ba'
    WHEN 'mocha' THEN '#7c5336' WHEN 'stone' THEN '#766d62' WHEN 'graphite' THEN '#4e535b'
    ELSE '#00ae84'
END
WHERE color_hex IS NULL;

UPDATE social.group_flairs SET text_color = 'white' WHERE text_color IS NULL;
