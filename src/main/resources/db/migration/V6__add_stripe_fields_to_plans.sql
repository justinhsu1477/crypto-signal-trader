-- =============================================
-- V6: plans 表新增 Stripe 相關欄位
-- =============================================
-- 用途：儲存 Stripe Price ID 和 Payment Link URL
-- 設定完 Stripe Dashboard 後，手動 UPDATE 對應值：
--   UPDATE plans SET stripe_price_id = 'price_xxx', stripe_payment_link_url = 'https://buy.stripe.com/xxx'
--   WHERE plan_id = 'basic';

ALTER TABLE plans ADD COLUMN IF NOT EXISTS stripe_price_id VARCHAR(255);
ALTER TABLE plans ADD COLUMN IF NOT EXISTS stripe_payment_link_url VARCHAR(500);

CREATE INDEX IF NOT EXISTS idx_plans_stripe_price_id ON plans (stripe_price_id);
