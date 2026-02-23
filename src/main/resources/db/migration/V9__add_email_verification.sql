-- =============================================
-- V9: Email OTP 驗證
-- =============================================

-- 1. users 表加 email_verified 欄位
-- 既有用戶預設 true（不影響現有帳號），新註冊用戶由程式設為 false
ALTER TABLE users ADD COLUMN email_verified BOOLEAN DEFAULT true NOT NULL;

-- 2. OTP 驗證碼表
CREATE TABLE email_verification_codes (
    id              BIGSERIAL PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    code            VARCHAR(6) NOT NULL,
    attempts        INT DEFAULT 0 NOT NULL,
    used            BOOLEAN DEFAULT false NOT NULL,
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP DEFAULT NOW() NOT NULL
);

-- 查詢最新有效 OTP + 統計發送頻率
CREATE INDEX idx_evc_email_active ON email_verification_codes (email, used, expires_at);
