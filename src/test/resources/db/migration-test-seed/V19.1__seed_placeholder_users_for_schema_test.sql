-- Test-only seed migration. **ONLY 在 schema-validation-test profile 跑**（不影響 prod）。
--
-- 背景：V20__set_lifetime_subscriptions.sql 在 INSERT subscriptions 時 FK 到 users.user_id =
--   'beck-tsai' / 'justin-hsu'。Prod 那兩個 user 是 admin 手動建的（沒進 Flyway）。
--   Fresh DB（schema validation test 用 Testcontainers）裡 users 表空 → V20 FK violation 炸。
--
-- 解：版本 V19.1 排在 V19 跟 V20 之間（Flyway 用版本號排序），預先 seed 兩個 placeholder user。
-- 用 ON CONFLICT DO NOTHING 確保 idempotent。
--
-- 只在 application-schema-validation-test.yml 的 flyway.locations 加上這目錄才會跑到。
-- application-integration-test.yml 跟 prod 都不會載到。

INSERT INTO users (user_id, email, password_hash, name, role, enabled, auto_trade_enabled, created_at, updated_at)
VALUES
    ('beck-tsai', 'beck-tsai@schema-test.local',
     '$2a$10$dummyHashForSchemaValidationTestOnly',
     'Beck Tsai (schema test placeholder)', 'USER', false, false, NOW(), NOW()),
    ('justin-hsu', 'justin-hsu@schema-test.local',
     '$2a$10$dummyHashForSchemaValidationTestOnly',
     'Justin Hsu (schema test placeholder)', 'USER', false, false, NOW(), NOW())
ON CONFLICT (user_id) DO NOTHING;
