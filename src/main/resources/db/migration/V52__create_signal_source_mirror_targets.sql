-- =============================================
-- V52: 多 mirror target 支援
-- =============================================
--
-- signal_sources 仍代表「一個外部訊號來源」，交易路由只看這張表。
-- signal_source_mirror_targets 代表同一個來源可轉發到多個 admin Discord webhook。
-- webhook URL 延續既有規則：AES-GCM 加密後 base64 入庫，明碼不落 DB。

CREATE TABLE IF NOT EXISTS signal_source_mirror_targets (
    id                  BIGSERIAL PRIMARY KEY,
    source_id           BIGINT NOT NULL REFERENCES signal_sources(id) ON DELETE CASCADE,
    target_guild_id     VARCHAR(64),
    target_channel_id   VARCHAR(64) NOT NULL,
    label               VARCHAR(100),
    mirror_webhook_url  VARCHAR(512) NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_ssmt_source_target_channel UNIQUE (source_id, target_channel_id)
);

CREATE INDEX IF NOT EXISTS idx_ssmt_source_enabled
    ON signal_source_mirror_targets (source_id, enabled);

COMMENT ON TABLE signal_source_mirror_targets IS '一個 signal source 可對應多個 Discord mirror webhook target';
COMMENT ON COLUMN signal_source_mirror_targets.source_id IS '對應 signal_sources.id；交易路由仍以 signal_sources 為準';
COMMENT ON COLUMN signal_source_mirror_targets.target_channel_id IS 'admin 目標 Discord channel ID';
COMMENT ON COLUMN signal_source_mirror_targets.mirror_webhook_url IS 'AES-GCM 加密後 base64 — Discord webhook URL，明碼不入庫';
