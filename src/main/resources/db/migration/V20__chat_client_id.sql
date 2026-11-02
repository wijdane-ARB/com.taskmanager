ALTER TABLE chat_messages ADD COLUMN client_id UUID NULL;
CREATE UNIQUE INDEX idx_chat_messages_client_id ON chat_messages(team_id, sender_id, client_id) WHERE client_id IS NOT NULL;
