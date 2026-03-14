-- AI 客服對話紀錄
CREATE TABLE chat_conversations (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255) NOT NULL,
    line_user_id VARCHAR(255) NOT NULL,
    session_id VARCHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    intent_type VARCHAR(50),
    token_count INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chat_conv_session ON chat_conversations(session_id);
CREATE INDEX idx_chat_conv_user_created ON chat_conversations(user_id, created_at DESC);
CREATE INDEX idx_chat_conv_created ON chat_conversations(created_at);
