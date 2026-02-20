-- =============================================
-- V3__add_version_fk_indexes.sql
-- 1. Trade 樂觀鎖 version 欄位
-- 2. Foreign Key: trades.user_id → users.user_id
-- 3. 實用索引補齊
-- =============================================

-- 1. 樂觀鎖 version 欄位（JPA @Version）
ALTER TABLE trades ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;

-- 2. FK: trades.user_id → users.user_id
-- 先清理可能存在的孤兒紀錄（user_id 不在 users 表中）
DELETE FROM trades WHERE user_id IS NOT NULL
    AND user_id NOT IN (SELECT user_id FROM users);

ALTER TABLE trades ADD CONSTRAINT fk_trades_user_id
    FOREIGN KEY (user_id) REFERENCES users(user_id);

-- 3. 補齊實用索引
CREATE INDEX IF NOT EXISTS idx_trades_status ON trades(status);
CREATE INDEX IF NOT EXISTS idx_trades_symbol_status ON trades(symbol, status);
CREATE INDEX IF NOT EXISTS idx_trade_events_trade_id ON trade_events(trade_id);
