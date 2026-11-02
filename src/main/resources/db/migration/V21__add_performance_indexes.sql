CREATE INDEX idx_team_members_user_id ON team_members(user_id);
CREATE INDEX idx_chat_messages_team_time ON chat_messages(team_id, timestamp DESC);
CREATE INDEX idx_notifications_user_time ON notifications(user_id, created_at DESC);
