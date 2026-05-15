-- Admin 操作稽核日誌
-- 與 audit_logs（記錄一般用戶的 LOGIN / VIEW 等行為）分離，
-- admin_audit_log 專記錄 admin 對「物件」的修改：誰、改什麼、改前/改後 hash、為什麼。
--
-- 設計重點：
-- - 不存改動前後全文（敏感資料）— 只存 SHA-256 前 16 hex
-- - append-only：應用程式不提供 DELETE 端點；DB 層也建議僅授予 INSERT/SELECT
-- - 對應 docs/admin-permission-model.md

CREATE TABLE IF NOT EXISTS admin_audit_log (
    id              BIGSERIAL PRIMARY KEY,
    admin_user_id   VARCHAR(36) NOT NULL,
    action          VARCHAR(64) NOT NULL,        -- e.g. UPDATE_CUSTOM_PROMPT
    target_type     VARCHAR(32) NOT NULL,        -- e.g. SIGNAL_SOURCE
    target_id       VARCHAR(64) NOT NULL,        -- e.g. signal_source.id 字串化
    before_hash     CHAR(16),                    -- SHA-256 前 16 hex
    after_hash      CHAR(16),                    -- SHA-256 前 16 hex
    ip_address      VARCHAR(45),
    reason          TEXT,                        -- admin 提供的修改理由
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_admin
    ON admin_audit_log(admin_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_target
    ON admin_audit_log(target_type, target_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_action
    ON admin_audit_log(action, created_at DESC);
