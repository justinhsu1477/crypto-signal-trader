-- 調整方案價格
-- Basic: $19/mo → $99/mo, $190/yr → $990/yr
-- Pro:   $49/mo → $199/mo, $490/yr → $1990/yr

UPDATE plans SET price_monthly = 99,  price_yearly = 990,  price_usdt = 99,  updated_at = CURRENT_TIMESTAMP WHERE plan_id = 'basic';
UPDATE plans SET price_monthly = 199, price_yearly = 1990, price_usdt = 199, updated_at = CURRENT_TIMESTAMP WHERE plan_id = 'pro';
