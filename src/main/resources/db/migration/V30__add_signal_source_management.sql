-- =============================================
-- V30: 訊號來源管理 + 用戶綁定
-- =============================================

-- 訊號來源主表（對應 Discord 群組/頻道）
CREATE TABLE IF NOT EXISTS signal_sources (
    id              BIGSERIAL PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,          -- Admin 內部名稱（如「陳哥VIP群」）
    display_name    VARCHAR(100) NOT NULL,          -- 用戶看到的別名（如「訊號源 A」）
    channel_id      VARCHAR(50),                    -- Discord channel ID
    guild_id        VARCHAR(50),                    -- Discord guild ID
    description     TEXT,                           -- Admin 備註
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_ss_channel_guild
    ON signal_sources (channel_id, guild_id);
CREATE INDEX IF NOT EXISTS idx_ss_enabled
    ON signal_sources (enabled);

-- 用戶-訊號來源綁定（多對多結構，MVP 階段 Service 層限制一對一）
CREATE TABLE IF NOT EXISTS user_signal_sources (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(36) NOT NULL REFERENCES users(user_id) ON DELETE CASCADE,
    source_id       BIGINT NOT NULL REFERENCES signal_sources(id) ON DELETE CASCADE,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    assigned_at     TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_user_source UNIQUE (user_id, source_id)
);

CREATE INDEX IF NOT EXISTS idx_uss_user_id ON user_signal_sources (user_id);
CREATE INDEX IF NOT EXISTS idx_uss_source_id ON user_signal_sources (source_id);

-- BroadcastLog 新增來源相關欄位
ALTER TABLE broadcast_logs ADD COLUMN IF NOT EXISTS source_id BIGINT;
ALTER TABLE broadcast_logs ADD COLUMN IF NOT EXISTS skipped_not_assigned INT DEFAULT 0;
