package com.trader.notification.service;

import com.trader.notification.model.NotificationCategory;

/**
 * 通知服務介面 — 抽象化通知頻道
 *
 * 實作：
 * - {@link DiscordWebhookService}（Discord Webhook）
 * - {@link LineNotificationService}（LINE Push API）
 * - {@link CompositeNotificationService}（@Primary，多頻道派發）
 *
 * int color 參數源自 Discord Embed 顏色，其他頻道實作可忽略或映射成嚴重度。
 */
public interface NotificationService {

    // 顏色常量（Discord Embed 顏色，其他頻道可忽略）
    int COLOR_GREEN  = 0x00FF00;  // 成功
    int COLOR_RED    = 0xFF0000;  // 失敗
    int COLOR_YELLOW = 0xFFFF00;  // 警告/跳過
    int COLOR_BLUE   = 0x3498DB;  // 資訊

    /** 發送全局通知（系統級 webhook） */
    void sendNotification(String title, String message, int color);

    /** 發送通知到指定用戶的 per-user webhook */
    void sendNotificationToUser(String userId, String title, String message, int color);

    /** 發送通知到指定用戶（帶分類，供未來 per-category 篩選） */
    void sendNotificationToUser(String userId, NotificationCategory category,
                                 String title, String message, int color);

    /** 發送到所有 ADMIN 的 per-user webhook（系統級告警） */
    void sendNotificationToAdmins(String title, String message, int color);

    /** 發送到所有 ADMIN（帶用戶 displayName 前綴，用於風控告警識別） */
    void sendNotificationToAdmins(String displayName, String title, String message, int color);

    /** 清除指定用戶的通知快取 */
    void evictUserCache(String userId);

    /** 清除所有通知快取 */
    void evictAllCache();
}
