-- Add creator_id to tasks table
ALTER TABLE tasks ADD COLUMN creator_id UUID;

-- Backfill creator_id from assignee_id (or some other logic if needed)
UPDATE tasks SET creator_id = assignee_id WHERE creator_id IS NULL AND assignee_id IS NOT NULL;

-- If some tasks have no assignee (unlikely based on current code but possible),
-- they might need a fallback if creator_id must be NOT NULL.
-- For now, we'll assume we can find a user or leave it nullable if backfill fails,
-- but the entity says nullable=false, so let's enforce it after backfill.

-- Assuming at least one user exists or all tasks have an assignee.
-- ALTER TABLE tasks ALTER COLUMN creator_id SET NOT NULL;

ALTER TABLE tasks ADD CONSTRAINT fk_tasks_creator FOREIGN KEY (creator_id) REFERENCES users(id) ON DELETE CASCADE;
CREATE INDEX idx_tasks_creator_id ON tasks(creator_id);