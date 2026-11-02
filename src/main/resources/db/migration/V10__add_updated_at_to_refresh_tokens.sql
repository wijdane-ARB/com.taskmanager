ALTER TABLE refresh_tokens ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;
UPDATE refresh_tokens SET updated_at = created_at;
ALTER TABLE refresh_tokens ALTER COLUMN updated_at SET NOT NULL;