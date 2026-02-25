-- =============================================
-- V15: 補齊 Foreign Key 約束 — 確保資料完整性
-- =============================================

-- ==================== 1. subscriptions ====================
-- 清理孤兒紀錄（如有）
DELETE FROM subscriptions WHERE user_id NOT IN (SELECT user_id FROM users);
DELETE FROM subscriptions WHERE plan_id IS NOT NULL
    AND plan_id NOT IN (SELECT plan_id FROM plans);

-- FK: subscriptions.user_id → users.user_id
ALTER TABLE subscriptions ADD CONSTRAINT fk_sub_user_id
    FOREIGN KEY (user_id) REFERENCES users(user_id)
    ON DELETE RESTRICT;

-- FK: subscriptions.plan_id → plans.plan_id
ALTER TABLE subscriptions ADD CONSTRAINT fk_sub_plan_id
    FOREIGN KEY (plan_id) REFERENCES plans(plan_id)
    ON DELETE RESTRICT;


-- ==================== 2. payment_history ====================
-- 清理孤兒紀錄（如有）
DELETE FROM payment_history WHERE user_id NOT IN (SELECT user_id FROM users);
DELETE FROM payment_history WHERE subscription_id IS NOT NULL
    AND subscription_id NOT IN (SELECT id FROM subscriptions);

-- FK: payment_history.user_id → users.user_id
ALTER TABLE payment_history ADD CONSTRAINT fk_ph_user_id
    FOREIGN KEY (user_id) REFERENCES users(user_id)
    ON DELETE RESTRICT;

-- FK: payment_history.subscription_id → subscriptions.id
ALTER TABLE payment_history ADD CONSTRAINT fk_ph_subscription_id
    FOREIGN KEY (subscription_id) REFERENCES subscriptions(id)
    ON DELETE SET NULL;


-- ==================== 3. user_api_keys ====================
DELETE FROM user_api_keys WHERE user_id NOT IN (SELECT user_id FROM users);

ALTER TABLE user_api_keys ADD CONSTRAINT fk_uak_user_id
    FOREIGN KEY (user_id) REFERENCES users(user_id)
    ON DELETE CASCADE;


-- ==================== 4. user_discord_webhooks ====================
DELETE FROM user_discord_webhooks WHERE user_id NOT IN (SELECT user_id FROM users);

ALTER TABLE user_discord_webhooks ADD CONSTRAINT fk_udw_user_id
    FOREIGN KEY (user_id) REFERENCES users(user_id)
    ON DELETE CASCADE;


-- ==================== 5. trade_events ====================
DELETE FROM trade_events WHERE trade_id IS NOT NULL
    AND trade_id NOT IN (SELECT trade_id FROM trades);

ALTER TABLE trade_events ADD CONSTRAINT fk_te_trade_id
    FOREIGN KEY (trade_id) REFERENCES trades(trade_id)
    ON DELETE CASCADE;
