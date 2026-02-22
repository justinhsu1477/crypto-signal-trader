-- V7: 加入 trades.created_at 索引，提升 dashboard 查詢效能
-- Dashboard 的 trade history / performance 查詢大量依賴 created_at 排序與範圍篩選
CREATE INDEX IF NOT EXISTS idx_trades_created_at ON trades(created_at);
