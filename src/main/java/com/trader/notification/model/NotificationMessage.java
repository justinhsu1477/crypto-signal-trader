package com.trader.notification.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * RabbitMQ 通知訊息 DTO
 *
 * <pre>
 * 面試重點：
 *   - 訊息 DTO 必須包含足夠資訊讓 Consumer 獨立處理（不需回查 Producer 上下文）
 *   - 用 JSON 序列化（Jackson2JsonMessageConverter），跨語言相容
 *   - @Builder 讓 Producer 端組裝清楚
 *
 * 路由規則：
 *   USER   → notification.user  queue → sendNotificationToUser()      (per-user webhook)
 *   ADMIN  → notification.admin queue → sendNotificationToAdmins()    (Admin per-user only)
 *   SYSTEM → notification.admin queue → sendNotification()            (全局 webhook only)
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage implements Serializable {

    /** 通知類型（決定路由到哪個 queue） */
    private NotificationType type;

    /** 目標用戶 ID（USER 類型必填，ADMIN 類型可選） */
    private String userId;

    /** 用戶顯示名稱（ADMIN 帶前綴用，例如 "用戶: Justin"） */
    private String displayName;

    /** 通知分類（供未來 per-category 篩選） */
    private NotificationCategory category;

    /** 通知標題 */
    private String title;

    /** 通知內容 */
    private String message;

    /** Discord embed 顏色 */
    private int color;

    /** 訊息產生時間 */
    private LocalDateTime timestamp;

    /**
     * 通知類型（決定 Consumer 路由行為）
     */
    public enum NotificationType {
        /** 用戶個人通知 → per-user webhook */
        USER,
        /** 管理員通知 → Admin per-user webhook only */
        ADMIN,
        /** 系統/全局通知 → 全局 webhook only */
        SYSTEM
    }
}
