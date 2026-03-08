-- V31: 強制一用戶一交易所 — 加入 UNIQUE 約束
-- 設計決策：一個用戶同一時間只能綁定一個交易所的 API Key
-- 切換交易所時，必須先平倉所有持倉，由應用層刪舊 key 再綁新 key

-- 1. 清理可能的重複資料（保留 id 最大的，即最新的一筆）
DELETE FROM user_api_keys a USING user_api_keys b
WHERE a.id < b.id AND a.user_id = b.user_id;

-- 2. 加 UNIQUE 約束：一個 user_id 只能有一筆 API Key
ALTER TABLE user_api_keys ADD CONSTRAINT uk_uak_user_id UNIQUE (user_id);
