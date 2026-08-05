-- V3__create_teams_and_project_team_scope.sql
--
-- 1) Creates teams / team_members / team_invites as real migrations.
--    These previously only existed via Hibernate's ddl-auto=update in dev —
--    never actually created by Flyway, so a fresh database would be missing
--    them entirely (and the chat-fix migration that references teams(id)
--    would fail to apply).
--
-- 2) Adds projects.team_id — the access-boundary column Project.java has
--    required ever since the team-scoped model was introduced, but which
--    no migration ever added. Backfills a personal team per existing user
--    so pre-existing projects get a home.
--
-- NOTE: projects.owner_id is left as-is. It's still the correct column —
-- Project.java maps it to the audit-only `createdBy` field via
-- @JoinColumn(name = "owner_id"). Only the Java-side meaning changed, not
-- the column name, so no rename is needed here.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── Team tables ──────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS teams (
                                     id         UUID PRIMARY KEY,
                                     name       VARCHAR(150) NOT NULL,
    owner_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                                                      );
CREATE INDEX IF NOT EXISTS idx_teams_owner_id ON teams(owner_id);

CREATE TABLE IF NOT EXISTS team_members (
                                            id        UUID PRIMARY KEY,
                                            team_id   UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    joined_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                                                                             CONSTRAINT uq_team_members_team_user UNIQUE (team_id, user_id)
    );

CREATE TABLE IF NOT EXISTS team_invites (
                                            id            UUID PRIMARY KEY,
                                            team_id       UUID NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    invited_email VARCHAR(255) NOT NULL,
    invited_by    UUID NOT NULL REFERENCES users(id),
    status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    responded_at  TIMESTAMP WITH TIME ZONE
                                                                                                 );
CREATE INDEX IF NOT EXISTS idx_team_invites_email_status ON team_invites(invited_email, status);

-- ── Backfill: one personal team per existing user ───────────────────────
-- Ensures "a project is created by a user" keeps working even for users
-- who never explicitly created a team.
INSERT INTO teams (id, name, owner_id, created_at, updated_at)
SELECT gen_random_uuid(), u.display_name || '''s Team', u.id, now(), now()
FROM users u
WHERE NOT EXISTS (SELECT 1 FROM teams t WHERE t.owner_id = u.id);

INSERT INTO team_members (id, team_id, user_id, joined_at)
SELECT gen_random_uuid(), t.id, t.owner_id, now()
FROM teams t
WHERE NOT EXISTS (
    SELECT 1 FROM team_members tm WHERE tm.team_id = t.id AND tm.user_id = t.owner_id
);

-- ── projects.team_id ─────────────────────────────────────────────────────
ALTER TABLE projects ADD COLUMN team_id UUID;

-- Every existing project's team_id becomes its creator's (owner_id's)
-- personal team — preserves current visibility for the creator while
-- satisfying the new team-scoped access boundary.
UPDATE projects p
SET team_id = t.id
    FROM teams t
WHERE t.owner_id = p.owner_id;

-- Defensive check before locking the column NOT NULL.
DO $$
DECLARE
orphaned INTEGER;
BEGIN
SELECT COUNT(*) INTO orphaned FROM projects WHERE team_id IS NULL;
IF orphaned > 0 THEN
        RAISE EXCEPTION 'projects has % row(s) with no resolvable team_id — investigate before proceeding', orphaned;
END IF;
END $$;

ALTER TABLE projects ALTER COLUMN team_id SET NOT NULL;
ALTER TABLE projects ADD CONSTRAINT fk_projects_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_projects_team_id ON projects(team_id);