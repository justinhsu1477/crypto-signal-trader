-- 為 Beck Tsai 和 Justin Hsu 設定終生免費訂閱（LIFETIME + Pro 方案）
-- 1. 先更新 CHECK CONSTRAINT，加入 LIFETIME（Hibernate auto-ddl 建的，需手動更新）
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_status_check;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_status_check
    CHECK (status IN ('TRIALING', 'ACTIVE', 'LIFETIME', 'CANCELLED', 'PAST_DUE'));

-- 2. Beck Tsai
INSERT INTO subscriptions (user_id, plan_id, status, current_period_start, current_period_end, created_at, updated_at)
SELECT 'beck-tsai', 'pro', 'LIFETIME', NOW(), NULL, NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM subscriptions WHERE user_id = 'beck-tsai' AND status IN ('ACTIVE', 'TRIALING', 'LIFETIME')
);
UPDATE subscriptions SET status = 'LIFETIME', plan_id = 'pro', current_period_end = NULL, updated_at = NOW()
WHERE user_id = 'beck-tsai' AND status IN ('ACTIVE', 'TRIALING');

-- 3. Justin Hsu
INSERT INTO subscriptions (user_id, plan_id, status, current_period_start, current_period_end, created_at, updated_at)
SELECT 'justin-hsu', 'pro', 'LIFETIME', NOW(), NULL, NOW(), NOW()
WHERE NOT EXISTS (
    SELECT 1 FROM subscriptions WHERE user_id = 'justin-hsu' AND status IN ('ACTIVE', 'TRIALING', 'LIFETIME')
);
UPDATE subscriptions SET status = 'LIFETIME', plan_id = 'pro', current_period_end = NULL, updated_at = NOW()
WHERE user_id = 'justin-hsu' AND status IN ('ACTIVE', 'TRIALING');
