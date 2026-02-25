-- =============================================
-- V16: 統一金額欄位為 DECIMAL(10,2)
--
-- 金額不該用 DOUBLE PRECISION（浮點精度問題）
-- 統一使用 DECIMAL(10,2) 確保金額精確
-- =============================================

-- plans 表
ALTER TABLE plans ALTER COLUMN price_monthly    TYPE DECIMAL(10,2);
ALTER TABLE plans ALTER COLUMN price_yearly     TYPE DECIMAL(10,2);
-- price_usdt 已是 DECIMAL(10,2)，跳過

-- payment_history 表
ALTER TABLE payment_history ALTER COLUMN amount TYPE DECIMAL(10,2);
