-- =============================================
-- V24: Email OTP hash 儲存
-- =============================================

-- 由 6 位明文 OTP 改為儲存 hash，需放大欄位長度
ALTER TABLE email_verification_codes
    ALTER COLUMN code TYPE VARCHAR(128);
