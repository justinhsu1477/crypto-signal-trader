-- 為每個 signal_source 加上 mirror 設定
-- 用途：把監聽到的訊息（陳哥/三馬哥 等）轉發一份到 admin 自己的 Discord 對應 channel
--
-- mirror_webhook_url：AES-GCM 加密後 base64
--   - 明碼 Discord webhook URL 約 120 字
--   - 加密 + base64 後約 200 字（含 12-byte IV + 16-byte GCM tag）
--   - 512 給足 buffer 跟 future-proof
--
-- mirror_enabled：per-source 開關
--   - 全域開關獨立在 application.yml mirror.enabled
--   - 雙層 kill switch：全域 / per-source 都要 true + URL 不為 null 才實際發送
--
-- 向下相容：兩欄都有預設值（null / false），既有 9 個源不會被啟用任何 mirror

ALTER TABLE signal_sources
    ADD COLUMN IF NOT EXISTS mirror_webhook_url VARCHAR(512),
    ADD COLUMN IF NOT EXISTS mirror_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN signal_sources.mirror_webhook_url IS 'AES-GCM 加密後 base64 — Discord webhook URL，明碼不入庫';
COMMENT ON COLUMN signal_sources.mirror_enabled IS '此源是否啟用 mirror（false=不送，true 且 URL 不為 null 才實際送）';
