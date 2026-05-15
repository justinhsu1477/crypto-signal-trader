-- signals 表的 custom_prompt audit chain
-- 修正 V48 註解的錯誤指向 — 該記在 signals（訊號層級），不是 broadcast_logs
--
-- 設計：Python 在 parse 時 snapshot 自己用到的 custom_prompt version + sha256，
-- 跟著 trade payload 送回 Java；Java 寫入時直接 echo 進 signals。
--
-- 這樣即使 admin 在 parse → broadcast 中間又改了 prompt，signals 紀錄的也是
-- Python 「實際用到」的版本，audit chain 不會被 race condition 污染。
--
-- 兩欄都 nullable：
-- - 非 AI 路徑（regex fallback）— 沒有 prompt 概念，留 null
-- - 沒設 custom_prompt 的 source — version=0 / sha=null

ALTER TABLE signals
    ADD COLUMN IF NOT EXISTS custom_prompt_version INT;

ALTER TABLE signals
    ADD COLUMN IF NOT EXISTS custom_prompt_sha256 CHAR(16);

-- 對 (source_channel_id, custom_prompt_version) 的查詢給索引
-- 用途：「v3 那段時間這個來源產出哪些訊號」這類稽核查詢
CREATE INDEX IF NOT EXISTS idx_sig_source_prompt_version
    ON signals(source_channel_id, custom_prompt_version)
    WHERE custom_prompt_version IS NOT NULL;
