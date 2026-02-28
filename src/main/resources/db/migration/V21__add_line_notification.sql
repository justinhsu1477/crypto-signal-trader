-- V21: LINE 通知支援
-- 1. user_line_bindings: 用戶 LINE 帳號綁定（1:1 對應 users）
-- 2. line_linking_codes: 臨時連結碼（用戶在網站產生，在 LINE 發送綁定）
-- 3. users 新增 line_notification_enabled 主開關

-- 用戶 LINE 綁定記錄
CREATE TABLE IF NOT EXISTS user_line_bindings (
    user_id       VARCHAR(255) PRIMARY KEY REFERENCES users(user_id),
    line_user_id  VARCHAR(255) NOT NULL UNIQUE,
    display_name  VARCHAR(255),
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    linked_at     TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ulb_line_user_id ON user_line_bindings(line_user_id);

-- LINE 連結碼（臨時：用戶在網站產生 code，在 LINE 發送此 code 綁定）
CREATE TABLE IF NOT EXISTS line_linking_codes (
    code        VARCHAR(8) PRIMARY KEY,
    user_id     VARCHAR(255) NOT NULL REFERENCES users(user_id),
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at  TIMESTAMP NOT NULL,
    used        BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_llc_user_id ON line_linking_codes(user_id);
CREATE INDEX IF NOT EXISTS idx_llc_expires ON line_linking_codes(expires_at);

-- users 表新增 LINE 通知主開關
ALTER TABLE users ADD COLUMN IF NOT EXISTS line_notification_enabled BOOLEAN NOT NULL DEFAULT TRUE;
