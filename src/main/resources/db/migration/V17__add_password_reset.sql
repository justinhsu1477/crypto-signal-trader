-- V17: 新增修改密碼 + 忘記密碼功能
-- 1. users 表加 password_changed_at（用於 JWT token invalidation）
-- 2. 新增 password_reset_tokens 表（忘記密碼 email reset token）

ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMP;

CREATE TABLE password_reset_tokens (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(255) NOT NULL,
    token_hash      VARCHAR(255) NOT NULL,
    expires_at      TIMESTAMP NOT NULL,
    used            BOOLEAN DEFAULT false NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW() NOT NULL
);

CREATE INDEX idx_prt_token_hash ON password_reset_tokens (token_hash);
CREATE INDEX idx_prt_user_id_created ON password_reset_tokens (user_id, created_at);
