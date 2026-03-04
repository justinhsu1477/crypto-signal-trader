-- =============================================
-- V26: OAuth 第三方登入支援
-- 1. user_oauth_providers: 通用 OAuth 提供者綁定（LINE/GOOGLE/DISCORD）
-- 2. oauth_states: CSRF state 暫存（10 分鐘過期）
-- 3. users.password_hash / email 改為可為空（OAuth-only 用戶）
-- =============================================

-- 1. 通用 OAuth 提供者綁定表
CREATE TABLE IF NOT EXISTS user_oauth_providers (
    id                  BIGSERIAL PRIMARY KEY,
    user_id             VARCHAR(255) NOT NULL REFERENCES users(user_id),
    provider            VARCHAR(30)  NOT NULL,
    provider_user_id    VARCHAR(255) NOT NULL,
    display_name        VARCHAR(255),
    email               VARCHAR(255),
    access_token        VARCHAR(1024),
    refresh_token       VARCHAR(1024),
    metadata            TEXT,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_oauth_provider_user UNIQUE (provider, provider_user_id)
);

CREATE INDEX IF NOT EXISTS idx_uop_user_id ON user_oauth_providers(user_id);

-- 2. OAuth state 暫存表（CSRF 保護）
CREATE TABLE IF NOT EXISTS oauth_states (
    state           VARCHAR(64) PRIMARY KEY,
    provider        VARCHAR(30) NOT NULL,
    code_verifier   VARCHAR(128),
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_os_expires ON oauth_states(expires_at);

-- 3. users.password_hash 改為可為空（OAuth-only 用戶無密碼）
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

-- 4. users.email 改為可為空（純 LINE 用戶可能無 email）
-- PostgreSQL: NULL 不參與 UNIQUE 比較，多個 NULL email 不衝突
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
