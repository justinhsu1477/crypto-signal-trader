-- 新增 source_message_id 索引
-- 用途：message_id 永久去重，防止 Queue Replay 超過 5 分鐘 hash 窗口後的重複下單
-- 對應 Entity: Signal.java @Index(name = "idx_sig_source_message_id")
CREATE INDEX IF NOT EXISTS idx_sig_source_message_id ON signals (source_message_id);
