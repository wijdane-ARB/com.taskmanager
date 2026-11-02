ALTER TABLE tasks DROP CONSTRAINT tasks_assignee_id_fkey;
ALTER TABLE tasks ADD CONSTRAINT tasks_assignee_id_fkey
    FOREIGN KEY (assignee_id) REFERENCES users(id) ON DELETE SET NULL;
