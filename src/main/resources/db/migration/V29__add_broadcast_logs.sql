-- 廣播跟單紀錄：每次廣播執行的結果審計
CREATE TABLE IF NOT EXISTS broadcast_logs (
    id              BIGSERIAL PRIMARY KEY,
    signal_action   VARCHAR(20) NOT NULL,
    symbol          VARCHAR(30) NOT NULL,
    side            VARCHAR(10),
    entry_price     DOUBLE PRECISION,
    stop_loss       DOUBLE PRECISION,
    take_profit     DOUBLE PRECISION,
    close_ratio     DOUBLE PRECISION,
    new_stop_loss   DOUBLE PRECISION,
    new_take_profit DOUBLE PRECISION,
    is_dca          BOOLEAN DEFAULT FALSE,
    source_author   VARCHAR(100),
    total_users     INT NOT NULL DEFAULT 0,
    success_count   INT NOT NULL DEFAULT 0,
    fail_count      INT NOT NULL DEFAULT 0,
    skipped_no_sub  INT NOT NULL DEFAULT 0,
    skipped_no_key  INT NOT NULL DEFAULT 0,
    status          VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',
    user_results    TEXT,
    ai_confidence   INT,
    ai_reasoning    TEXT,
    duration_ms     BIGINT,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_bl_created_at ON broadcast_logs (created_at DESC);
