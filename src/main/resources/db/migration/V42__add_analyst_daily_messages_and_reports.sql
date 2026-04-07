-- V42: 分析師每日訊息收集 + 跨分析師 AI 日報

-- 1. 每日分析師訊息（per analyst per day，append 累積）
CREATE TABLE analyst_daily_messages (
    id              BIGSERIAL PRIMARY KEY,
    analyst_name    VARCHAR(100)  NOT NULL,
    channel_id      VARCHAR(50)   NOT NULL,
    message_date    DATE          NOT NULL,
    content         TEXT          NOT NULL DEFAULT '',
    message_count   INT           NOT NULL DEFAULT 0,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_analyst_date UNIQUE (analyst_name, message_date)
);

CREATE INDEX idx_adm_date ON analyst_daily_messages (message_date);
CREATE INDEX idx_adm_channel ON analyst_daily_messages (channel_id);

-- 2. 跨分析師 AI 報告（per day）
CREATE TABLE analyst_reports (
    id              BIGSERIAL PRIMARY KEY,
    report_date     DATE          NOT NULL UNIQUE,
    analyst_count   INT           NOT NULL DEFAULT 0,
    report_content  TEXT,
    report_data     JSONB,
    ai_tokens_used  INT,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ar_date ON analyst_reports (report_date);
