-- 部分平倉累計毛利：每次部分平倉時累加盈虧，全平時加總為最終 grossProfit
ALTER TABLE trades ADD COLUMN IF NOT EXISTS partial_profit DOUBLE PRECISION;
