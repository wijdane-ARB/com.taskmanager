-- ── 1. USERS TABLE ─────────────────────────────────────────────────────────
CREATE TABLE users (
                       id                 UUID PRIMARY KEY,
                       display_name       VARCHAR(100) NOT NULL,
                       email              VARCHAR(255) NOT NULL UNIQUE,
                       password_hash      VARCHAR(60)  NOT NULL,
                       is_pro_subscriber BOOLEAN      NOT NULL DEFAULT FALSE,
                       email_verified     BOOLEAN      NOT NULL DEFAULT FALSE,
                       created_at         TIMESTAMP WITH TIME ZONE NOT NULL,
                       updated_at         TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX idx_users_email ON users(email);

-- ── 2. PROJECTS TABLE ──────────────────────────────────────────────────────
CREATE TABLE projects (
                          id          UUID PRIMARY KEY,
                          name        VARCHAR(150) NOT NULL,
                          description VARCHAR(500),
                          color       VARCHAR(7)   DEFAULT '#6366F1',
                          status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
                          owner_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                          created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
                          updated_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_projects_owner_id ON projects(owner_id);
CREATE INDEX idx_projects_status   ON projects(status);

-- ── 3. TASKS TABLE ─────────────────────────────────────────────────────────
CREATE TABLE tasks (
                       id                         UUID PRIMARY KEY,
                       title                      VARCHAR(255) NOT NULL,
                       description                VARCHAR(2000),
                       due_date                   TIMESTAMP WITH TIME ZONE,
                       scheduled_time             TIMESTAMP WITH TIME ZONE,
                       is_recurring               BOOLEAN      NOT NULL DEFAULT FALSE,
                       recurrence_rule            VARCHAR(100),
                       status                     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
                       priority_level             VARCHAR(10)  NOT NULL DEFAULT 'NORMAL',
                       actual_start_time          TIMESTAMP WITH TIME ZONE,
                       completed_at               TIMESTAMP WITH TIME ZONE,
                       estimated_duration_minutes INTEGER,
                       reopened_count             INTEGER      NOT NULL DEFAULT 0,
                       snoozed_count              INTEGER      NOT NULL DEFAULT 0,
                       day_of_week_created        INTEGER      NOT NULL,
                       hour_of_day_created       INTEGER      NOT NULL,
                       owner_id                   UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                       project_id                 UUID         NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
                       created_at                 TIMESTAMP WITH TIME ZONE NOT NULL,
                       updated_at                 TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_tasks_project_id      ON tasks(project_id);
CREATE INDEX idx_tasks_owner_id        ON tasks(owner_id);
CREATE INDEX idx_tasks_status          ON tasks(status);
CREATE INDEX idx_tasks_due_date        ON tasks(due_date);
CREATE INDEX idx_tasks_owner_status_due ON tasks(owner_id, status, due_date);

-- ── 4. REFRESH TOKENS TABLE ────────────────────────────────────────────────
CREATE TABLE refresh_tokens (
                                id          UUID PRIMARY KEY,
                                token       VARCHAR(36)  NOT NULL UNIQUE,
                                user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                expires_at  TIMESTAMP WITH TIME ZONE NOT NULL,
                                revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
                                device_info  VARCHAR(255),
                                created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX idx_refresh_tokens_token    ON refresh_tokens(token);
CREATE INDEX idx_refresh_tokens_user_id  ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_expires  ON refresh_tokens(expires_at);

-- ── 5. VERIFICATION CODES TABLE ───────────────────────────────────────────
CREATE TABLE verification_codes (
                                    id         UUID PRIMARY KEY,
                                    user_id    UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
                                    code       VARCHAR(6)  NOT NULL,
                                    type       VARCHAR(20) NOT NULL,
                                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                                    used       BOOLEAN     NOT NULL DEFAULT FALSE,
                                    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_verification_codes_user_type ON verification_codes(user_id, type, used);
CREATE INDEX idx_verification_codes_expires   ON verification_codes(expires_at);