-- 模擬交易標記（Paper Trading）
ALTER TABLE trades ADD COLUMN IF NOT EXISTS simulated BOOLEAN NOT NULL DEFAULT false;

-- 部分索引：只索引模擬交易的 OPEN 單（加速定時監控查詢）
CREATE INDEX IF NOT EXISTS idx_trades_simulated_open
    ON trades(simulated, status) WHERE simulated = true;

-- Signal Source 模擬交易開關
ALTER TABLE signal_sources ADD COLUMN IF NOT EXISTS paper_trading_enabled BOOLEAN NOT NULL DEFAULT false;
