-- V23: 移除 TRIALING 狀態
-- 將所有 TRIALING 訂閱改為 CANCELLED（此狀態已不再使用）
UPDATE subscriptions SET status = 'CANCELLED' WHERE status = 'TRIALING';

-- 更新 CHECK constraint：移除 TRIALING，僅允許 ACTIVE/LIFETIME/CANCELLED/PAST_DUE
ALTER TABLE subscriptions DROP CONSTRAINT IF EXISTS subscriptions_status_check;
ALTER TABLE subscriptions ADD CONSTRAINT subscriptions_status_check
    CHECK (status IN ('ACTIVE', 'LIFETIME', 'CANCELLED', 'PAST_DUE'));

-- 更新 default 值
ALTER TABLE subscriptions ALTER COLUMN status SET DEFAULT 'ACTIVE';
