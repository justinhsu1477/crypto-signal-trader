package com.trader.notification.model;

/**
 * 通知分類 — 用於未來 per-category 篩選
 *
 * 目前僅用於 sendNotificationToUser 的方法簽名。
 * 主開關 (discordNotificationEnabled) 不區分分類。
 * 未來可在 user_notification_preferences 表中按分類設定開關。
 */
public enum NotificationCategory {
    /** 交易執行（入場/平倉/DCA） */
    TRADE_EXECUTION,
    /** SL/TP 自動觸發 */
    SL_TP_TRIGGERED,
    /** 保護消失（SL/TP 取消/過期） — 關鍵警報 */
    PROTECTION_LOST,
    /** 每日報表 */
    DAILY_REPORT,
    /** WebSocket 連線狀態 */
    STREAM_STATUS,
    /** 殭屍 Trade 清理 */
    CLEANUP,
    /** 系統/基礎設施通知 */
    SYSTEM
}
