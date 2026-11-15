-- A post can carry up to ten pictures, shown as a gallery in their saved order.
-- Additive only: social.posts.image_key keeps holding the first picture, so an
-- image from before this release still reads and writes the cover after a rollback.
CREATE TABLE IF NOT EXISTS social.post_images (
    post_id UUID NOT NULL REFERENCES social.posts(id) ON DELETE CASCADE,
    position SMALLINT NOT NULL CHECK (position BETWEEN 0 AND 9),
    image_key VARCHAR(255) NOT NULL,
    image_content_type VARCHAR(30) NOT NULL,
    PRIMARY KEY (post_id, position)
);

INSERT INTO social.post_images(post_id, position, image_key, image_content_type)
SELECT id, 0, image_key, image_content_type
FROM social.posts
WHERE image_key IS NOT NULL AND image_content_type IS NOT NULL
ON CONFLICT DO NOTHING;
