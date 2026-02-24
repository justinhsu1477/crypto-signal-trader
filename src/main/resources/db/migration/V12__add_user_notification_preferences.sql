-- V12: 新增用戶通知偏好表（1:1 對應 users）
-- 結構同 user_trade_settings — userId 為 PK，每人一列
-- 無此列 = 全部預設啟用（由 Service 層 getOrCreate 處理）

CREATE TABLE IF NOT EXISTS user_notification_preferences (
    user_id            VARCHAR(255) PRIMARY KEY REFERENCES users(user_id),
    trade_execution    BOOLEAN NOT NULL DEFAULT TRUE,   -- 廣播跟單成功/失敗
    sl_tp_triggered    BOOLEAN NOT NULL DEFAULT TRUE,   -- SL/TP 自動觸發
    protection_lost    BOOLEAN NOT NULL DEFAULT TRUE,   -- SL/TP 保護消失（代碼層強制啟用）
    daily_report       BOOLEAN NOT NULL DEFAULT TRUE,   -- 每日報表 + 殭屍清理
    stream_status      BOOLEAN NOT NULL DEFAULT TRUE,   -- WebSocket 連線狀態
    system_alert       BOOLEAN NOT NULL DEFAULT TRUE,   -- Fail-Safe / 熔斷（代碼層強制啟用）
    created_at         TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP NOT NULL DEFAULT NOW()
);
