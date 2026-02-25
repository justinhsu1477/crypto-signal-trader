-- 統一方案價格（對齊 Landing Page 定價）
-- Basic: $9.99 → $19/mo, $99 → $190/yr
-- Pro:   $29.99 → $49/mo, $299 → $490/yr

UPDATE plans SET price_monthly = 19,  price_yearly = 190, updated_at = CURRENT_TIMESTAMP WHERE plan_id = 'basic';
UPDATE plans SET price_monthly = 49,  price_yearly = 490, updated_at = CURRENT_TIMESTAMP WHERE plan_id = 'pro';
