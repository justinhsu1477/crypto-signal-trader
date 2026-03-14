-- V36: 新增 changelog、trade_notes、balance_snapshots 三張表

-- ==================== Changelog ====================
CREATE TABLE IF NOT EXISTS changelog_entries (
    id              BIGSERIAL PRIMARY KEY,
    version         VARCHAR(50)  NOT NULL,      -- e.g. "1.5.0"
    title           VARCHAR(200) NOT NULL,
    content         TEXT         NOT NULL,       -- Markdown 格式
    category        VARCHAR(50)  DEFAULT 'UPDATE',  -- UPDATE / FEATURE / FIX / SECURITY
    published       BOOLEAN      DEFAULT FALSE,
    published_at    TIMESTAMP,
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW()
);

CREATE INDEX idx_changelog_published ON changelog_entries(published, published_at DESC);

-- ==================== Trade Notes (覆盤筆記) ====================
CREATE TABLE IF NOT EXISTS trade_notes (
    id              BIGSERIAL PRIMARY KEY,
    trade_id        VARCHAR(255) NOT NULL,
    user_id         VARCHAR(255) NOT NULL,
    note            TEXT,                        -- 覆盤筆記內容
    tags            VARCHAR(500),                -- 逗號分隔標籤: "追高,情緒交易"
    rating          INTEGER,                     -- 1-5 自評分數
    created_at      TIMESTAMP    DEFAULT NOW(),
    updated_at      TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uq_trade_note_user UNIQUE (trade_id, user_id)
);

CREATE INDEX idx_trade_notes_user ON trade_notes(user_id);
CREATE INDEX idx_trade_notes_trade ON trade_notes(trade_id);

-- ==================== Balance Snapshots (資產快照) ====================
CREATE TABLE IF NOT EXISTS balance_snapshots (
    id              BIGSERIAL PRIMARY KEY,
    user_id         VARCHAR(255) NOT NULL,
    snapshot_date   DATE         NOT NULL,
    balance         DECIMAL(20,8) NOT NULL,      -- 當日帳戶淨值
    created_at      TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uq_balance_snapshot UNIQUE (user_id, snapshot_date)
);

CREATE INDEX idx_balance_snapshots_user_date ON balance_snapshots(user_id, snapshot_date DESC);
