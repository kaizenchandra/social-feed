ALTER TABLE post_media
    ADD COLUMN thumbnail_key varchar(200);
ALTER TABLE post_media
    ADD COLUMN thumbnail_attempts int NOT NULL DEFAULT 0;
CREATE INDEX media_pending_thumbnail ON post_media (created_at) WHERE complete AND thumbnail_key IS NULL AND thumbnail_attempts<3 AND content_type LIKE 'image/%';
