ALTER TABLE tasks RENAME COLUMN owner_id TO assignee_id;
ALTER TABLE tasks ALTER COLUMN assignee_id DROP NOT NULL;

DO $$
DECLARE
    fk_name text;
BEGIN
    SELECT tc.constraint_name INTO fk_name
    FROM information_schema.table_constraints tc
    JOIN information_schema.key_column_usage kcu
      ON tc.constraint_name = kcu.constraint_name
    WHERE tc.table_name = 'tasks'
      AND kcu.column_name = 'assignee_id'
      AND tc.constraint_type = 'FOREIGN KEY';

    IF fk_name IS NOT NULL THEN
        EXECUTE format('ALTER TABLE tasks RENAME CONSTRAINT %I TO tasks_assignee_id_fkey', fk_name);
    END IF;
END $$;

DROP INDEX IF EXISTS idx_tasks_owner_id;
CREATE INDEX idx_tasks_assignee_id ON tasks(assignee_id);

DROP INDEX IF EXISTS idx_tasks_owner_status_due;
CREATE INDEX idx_tasks_assignee_status_due ON tasks(assignee_id, status, due_date);