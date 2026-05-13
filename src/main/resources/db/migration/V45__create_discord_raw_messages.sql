-- Per-message Discord archive for audit + missed-signal detection + eval harness training.
-- Every received message gets a row, regardless of whether AI parsed it as a signal.
-- AI parser fills in parser_action / parser_skipped_reason later. signal_id is linked
-- when a corresponding signal row is created via /api/broadcast-trade.

CREATE TABLE discord_raw_messages (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(64) NOT NULL UNIQUE,                -- Discord message ID, prevents dup
    source_platform VARCHAR(32) NOT NULL DEFAULT 'DISCORD',
    source_channel_id VARCHAR(64) NOT NULL,
    source_channel_name VARCHAR(255),
    source_guild_id VARCHAR(64),
    source_author_name VARCHAR(255),
    message_timestamp TIMESTAMP NOT NULL,                  -- when Discord message was sent

    content TEXT,
    has_attachments BOOLEAN NOT NULL DEFAULT FALSE,
    attachment_count INTEGER NOT NULL DEFAULT 0,
    attachment_sha256 VARCHAR(64),                          -- first image's sha256, if any
    has_embed_images BOOLEAN NOT NULL DEFAULT FALSE,
    has_reference BOOLEAN NOT NULL DEFAULT FALSE,           -- is reply / quote

    -- AI parser result (filled after parsing)
    parser_action VARCHAR(32),                              -- ENTRY / CLOSE / MOVE_SL / CANCEL / INFO / null=未處理
    parser_skipped_reason VARCHAR(64),                      -- BLACKLIST / DEDUP / EMPTY / FILTERED / null
    signal_id VARCHAR(64),                                  -- FK to signals when applicable

    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Indices for common queries:
CREATE INDEX idx_drm_channel_time ON discord_raw_messages(source_channel_id, message_timestamp DESC);
CREATE INDEX idx_drm_author_time ON discord_raw_messages(source_author_name, message_timestamp DESC);
CREATE INDEX idx_drm_signal_id ON discord_raw_messages(signal_id) WHERE signal_id IS NOT NULL;
-- Speed up the «missed signal» audit query (messages without signal_id from a specific author):
CREATE INDEX idx_drm_audit ON discord_raw_messages(source_author_name, message_timestamp DESC)
    WHERE signal_id IS NULL AND parser_action IS NULL;
