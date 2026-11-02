-- Ensure no duplicate case-insensitive emails exist before locking them down
UPDATE users SET email = lower(email) WHERE email <> lower(email);
UPDATE team_invites SET invited_email = lower(invited_email);
CREATE UNIQUE INDEX idx_users_email_lower ON users (lower(email));