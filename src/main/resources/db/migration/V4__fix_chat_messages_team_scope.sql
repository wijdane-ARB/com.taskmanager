-- V4__fix_chat_messages_team_scope.sql
--
-- Supersedes the previously-attempted "V3__fix_chat_messages_team_scope.sql".
-- That version did a blind `RENAME COLUMN project_id TO team_id` with no
-- value remapping — every pre-existing chat row would keep its old
-- project_id UUID, now mislabeled as team_id. On a non-empty table that
-- either fails the new FK outright, or — worse, if a project_id and a
-- team_id ever happened to collide — silently reattributes chat history
-- to the wrong team.
--
-- This version resolves each row's correct team via projects.team_id
-- before dropping project_id, so existing chat history is preserved
-- and correctly reattributed.
--
-- Depends on V3__create_teams_and_project_team_scope.sql having already
-- run (teams table and projects.team_id must exist).
--
-- Delete/replace the old "V3__fix_chat_messages_team_scope.sql" file with
-- this one if it was never applied to a real database — if it WAS already
-- applied somewhere, that environment needs a hand-rolled repair instead
-- of this migration (Flyway won't let you re-run or edit an applied
-- version; talk to whoever owns that environment before deploying this).

ALTER TABLE chat_messages ADD COLUMN team_id UUID;

UPDATE chat_messages cm
SET team_id = p.team_id
    FROM projects p
WHERE p.id = cm.project_id;

-- Fail loudly instead of silently dropping/orphaning rows we can't resolve.
DO $$
DECLARE
orphaned INTEGER;
BEGIN
SELECT COUNT(*) INTO orphaned FROM chat_messages WHERE team_id IS NULL;
IF orphaned > 0 THEN
        RAISE EXCEPTION 'chat_messages has % row(s) with no resolvable team_id — investigate before proceeding', orphaned;
END IF;
END $$;

ALTER TABLE chat_messages ALTER COLUMN team_id SET NOT NULL;
ALTER TABLE chat_messages DROP CONSTRAINT fk_chat_project;
ALTER TABLE chat_messages DROP COLUMN project_id;
ALTER TABLE chat_messages ADD CONSTRAINT fk_chat_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE;

DROP INDEX IF EXISTS idx_chat_project_id;
CREATE INDEX idx_chat_team_id ON chat_messages(team_id);