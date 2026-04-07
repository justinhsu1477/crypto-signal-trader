-- Discord 用戶綁定
CREATE TABLE IF NOT EXISTS user_discord_bindings (
    user_id VARCHAR(255) PRIMARY KEY,
    discord_user_id VARCHAR(255) NOT NULL UNIQUE,
    display_name VARCHAR(255),
    enabled BOOLEAN DEFAULT true,
    linked_at TIMESTAMP,
    updated_at TIMESTAMP
);

CREATE INDEX idx_udb_discord_user_id ON user_discord_bindings(discord_user_id);

-- chat_conversations 增加多頻道支援
ALTER TABLE chat_conversations ADD COLUMN IF NOT EXISTS channel VARCHAR(20) DEFAULT 'LINE';
ALTER TABLE chat_conversations ADD COLUMN IF NOT EXISTS channel_user_id VARCHAR(255);

-- 回填 channel_user_id from line_user_id
UPDATE chat_conversations SET channel_user_id = line_user_id WHERE channel_user_id IS NULL;

-- line_user_id 改為可 null（Discord 訊息沒有 line_user_id）
ALTER TABLE chat_conversations ALTER COLUMN line_user_id DROP NOT NULL;
