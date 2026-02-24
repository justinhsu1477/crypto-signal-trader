-- V11: 新增 Discord 通知主開關（預設啟用）
-- 用戶可透過 Dashboard API 關閉所有 Discord 通知，不影響跟單執行

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS discord_notification_enabled BOOLEAN NOT NULL DEFAULT TRUE;
