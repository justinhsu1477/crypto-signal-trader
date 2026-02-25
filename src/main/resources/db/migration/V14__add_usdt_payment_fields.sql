-- =============================================
-- USDT TRC20 付款支援
-- =============================================

-- plans 表新增 USDT 價格
ALTER TABLE plans ADD COLUMN IF NOT EXISTS price_usdt DECIMAL(10,2);

-- 設定 USDT 價格（對齊 Landing Page）
UPDATE plans SET price_usdt = 0    WHERE plan_id = 'free';
UPDATE plans SET price_usdt = 19   WHERE plan_id = 'basic';
UPDATE plans SET price_usdt = 49   WHERE plan_id = 'pro';

-- payment_history 表新增加密貨幣欄位
ALTER TABLE payment_history ADD COLUMN IF NOT EXISTS tx_hash VARCHAR(100);
ALTER TABLE payment_history ADD COLUMN IF NOT EXISTS network VARCHAR(20);
ALTER TABLE payment_history ADD COLUMN IF NOT EXISTS wallet_address VARCHAR(100);

-- tx_hash 唯一約束（防止同一筆交易重複使用）
CREATE UNIQUE INDEX IF NOT EXISTS idx_ph_tx_hash ON payment_history(tx_hash) WHERE tx_hash IS NOT NULL;
