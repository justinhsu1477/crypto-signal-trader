-- V41: 清理 AI parser 錯誤產生的異常 paper trading 記錄
-- 問題: entry_price 嚴重偏離市場價 或 side 為 NULL
-- 處理方式: 標記為 INVALID，保留資料不刪除

-- 1. 異常 entry_price 的交易（BTCUSDT 正常範圍 ~$68K-$73K）
UPDATE trades
SET status       = 'INVALID',
    exit_reason  = 'AI parser error: abnormal entry_price far from market price'
WHERE trade_id IN (
    '3871bebc-fe4c-403c-b619-88b30af47b88',  -- 加密大漂亮, entry=$1.482
    'a38495e6-dca0-4703-9fe2-db7183a2da30',  -- 舒琴, entry=$7.24
    '4a350473-3b88-4da6-8dc8-5ed346b7c152',  -- 大鏢客, entry=$2105
    'b0ef24a8-6c8e-4b1e-b3df-c0d765747cab'   -- 大鏢客, entry=$2105
)
AND status != 'INVALID';

-- 2. side 為 NULL 的交易
UPDATE trades
SET status       = 'INVALID',
    exit_reason  = 'AI parser error: missing trade side (NULL)'
WHERE trade_id IN (
    '56ff9ee0-4a72-4466-a4c5-f4851e1a2d92',  -- 三馬哥, BTCUSDT
    '28363fdc-2d83-48f8-9b9a-89da58be2b43',  -- 三馬哥, BTCUSDT
    'd0666996-e1d2-4ad3-aafe-19ae7b62d0ff',  -- 大鏢客, BTCUSDT
    '4a350473-3b88-4da6-8dc8-5ed346b7c152',  -- 大鏢客 (also abnormal price)
    'b0ef24a8-6c8e-4b1e-b3df-c0d765747cab'   -- 大鏢客 (also abnormal price)
)
AND status != 'INVALID';
