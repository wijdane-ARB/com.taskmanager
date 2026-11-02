-- Backfill creator_id for legacy rows using project owner_id
UPDATE tasks t
SET creator_id = p.owner_id
FROM projects p
WHERE t.project_id = p.id AND t.creator_id IS NULL;

UPDATE tasks t
SET creator_id = tm.owner_id
FROM projects p
JOIN teams tm ON p.team_id = tm.id
WHERE t.project_id = p.id AND t.creator_id IS NULL;

-- Drop the old V8 cascade constraint and recreate as SET NULL
ALTER TABLE tasks DROP CONSTRAINT IF EXISTS fk_tasks_creator;

ALTER TABLE tasks ADD CONSTRAINT fk_tasks_creator
    FOREIGN KEY (creator_id) REFERENCES users(id) ON DELETE SET NULL;