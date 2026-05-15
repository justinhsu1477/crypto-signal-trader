-- signal_sources.custom_prompt 治理欄位
-- 對應 discord-monitor/docs/PROMPT_ARCHITECTURE.md Safety Constraints 章節。
--
-- 設計重點：
-- - custom_prompt_version：單調遞增。寫入時 +1，BroadcastLog/Trade 可寫進這個值做稽核鏈
-- - custom_prompt_sha256：寫入後算 SHA-256 前 16 hex，供稽核對齊
-- - custom_prompt_updated_at / by：搭配 admin_audit_log 提供查詢入口
-- - CHECK 長度 ≤ 1500：DB 層硬底線（應用層先 reject，DB 是最後一道）

ALTER TABLE signal_sources
    ADD COLUMN IF NOT EXISTS custom_prompt_version INT NOT NULL DEFAULT 0;

ALTER TABLE signal_sources
    ADD COLUMN IF NOT EXISTS custom_prompt_sha256 CHAR(16);

ALTER TABLE signal_sources
    ADD COLUMN IF NOT EXISTS custom_prompt_updated_at TIMESTAMP;

ALTER TABLE signal_sources
    ADD COLUMN IF NOT EXISTS custom_prompt_updated_by VARCHAR(64);

-- DB 層 hard cap：應用層先擋（給好錯誤訊息），這條是萬一被繞過的最後防線。
-- length 算字元數，PostgreSQL 對 TEXT 同樣有效。
ALTER TABLE signal_sources
    DROP CONSTRAINT IF EXISTS chk_signal_source_custom_prompt_len;

ALTER TABLE signal_sources
    ADD CONSTRAINT chk_signal_source_custom_prompt_len
        CHECK (custom_prompt IS NULL OR length(custom_prompt) <= 1500);
