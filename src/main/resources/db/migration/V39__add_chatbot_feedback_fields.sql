-- Chatbot Feedback Loop：反應收集 + 滿意度統計
ALTER TABLE chat_conversations ADD COLUMN IF NOT EXISTS feedback_rating INTEGER;
ALTER TABLE chat_conversations ADD COLUMN IF NOT EXISTS feedback_at TIMESTAMP;
ALTER TABLE chat_conversations ADD COLUMN IF NOT EXISTS discord_message_id VARCHAR(50);

-- Discord message ID 索引（反應事件反查用）
CREATE INDEX IF NOT EXISTS idx_chat_conv_discord_msg_id ON chat_conversations(discord_message_id);

-- intent_type 索引（Analytics 意圖統計用）
CREATE INDEX IF NOT EXISTS idx_chat_conv_intent_type ON chat_conversations(intent_type);
