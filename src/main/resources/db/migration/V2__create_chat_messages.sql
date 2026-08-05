CREATE TABLE chat_messages (
                               id UUID PRIMARY KEY,
                               project_id UUID NOT NULL,
                               sender_id UUID NOT NULL,
                               content VARCHAR(2000) NOT NULL,
                               timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
                               CONSTRAINT fk_chat_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
                               CONSTRAINT fk_chat_sender FOREIGN KEY (sender_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_chat_project_id ON chat_messages(project_id);