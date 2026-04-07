-- 每日訊號日報
CREATE TABLE daily_signal_reports (
    id BIGSERIAL PRIMARY KEY,
    report_date DATE NOT NULL UNIQUE,
    total_signals INT NOT NULL DEFAULT 0,
    total_sources INT NOT NULL DEFAULT 0,
    long_count INT NOT NULL DEFAULT 0,
    short_count INT NOT NULL DEFAULT 0,
    avg_confidence DOUBLE PRECISION,
    report_data JSONB NOT NULL DEFAULT '{}',
    ai_analysis TEXT,
    ai_tokens_used INT DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_dsr_report_date ON daily_signal_reports(report_date DESC);
