-- V30: 多交易所支援 — 新增 exchange 欄位
-- 預設值 'BINANCE' 確保現有資料完全相容

-- 1. trades 表新增 exchange 欄位
ALTER TABLE trades ADD COLUMN exchange VARCHAR(20) DEFAULT 'BINANCE' NOT NULL;

-- 2. trade_events 表新增 exchange 欄位
ALTER TABLE trade_events ADD COLUMN exchange VARCHAR(20) DEFAULT 'BINANCE';

-- 3. 新增索引
CREATE INDEX idx_trades_exchange ON trades (exchange);
CREATE INDEX idx_trades_user_exchange ON trades (user_id, exchange);
