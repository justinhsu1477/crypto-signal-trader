package com.trader.notification.consumer;

import com.trader.notification.config.RabbitMQConfig;
import com.trader.notification.model.NotificationMessage;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.LineNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 通知消費者 — 從 RabbitMQ 收訊息，呼叫 Discord/LINE Service
 *
 * <pre>
 * 面試重點：
 *   - @RabbitListener 底層用 SimpleMessageListenerContainer
 *   - acknowledge-mode: auto（預設）+ retry → Spring 自動管理整個生命週期：
 *     成功 → auto ACK（告訴 RabbitMQ「可以刪了」）
 *     失敗 → Spring retry 指數退避重試（1s → 2s → 4s）
 *     重試耗盡 → RepublishMessageRecoverer → 訊息帶 error headers 發到 DLQ
 *   - 每個 queue 獨立 listener → user 大量通知不會阻塞 admin 緊急告警
 *
 * 頻道隔離策略：
 *   每個頻道（Discord / LINE）獨立 try-catch，一個失敗不影響另一個：
 *   - Discord 成功 + LINE 失敗 → 用戶至少收到 Discord，不會因 retry 重複收
 *   - 全部失敗 → 拋出例外 → Spring retry → DLQ（確保有告警可追查）
 *   - 部分失敗 → log.error 記錄，不觸發 retry（避免已成功的頻道重複發送）
 *
 * 消費流程：
 *   notification.user  → consumeUserNotification()  → sendNotificationToUser()       (per-user webhook)
 *   notification.admin → consumeAdminNotification() →
 *     SYSTEM type              → sendNotificationToAdmins()    (Admin per-user — 多用戶模式)
 *     ADMIN  type + displayName → sendNotificationToAdmins(dn) (Admin per-user + 用戶前綴)
 *     ADMIN  type               → sendNotificationToAdmins()   (Admin per-user only)
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private final DiscordWebhookService discordService;
    private final LineNotificationService lineService;

    /**
     * 消費用戶通知（notification.user queue）
     *
     * 面試：為什麼 user queue 跟 admin queue 分開？
     * → user queue 量大（N 用戶 × 每次廣播），admin 量小但優先級高。
     *   分離後 admin 緊急告警不會被 100 個 user 通知擋在後面。
     *
     * 頻道隔離：Discord / LINE 各自 try-catch
     * → 一個成功即 ACK（用戶至少收到一個頻道）
     * → 全部失敗才拋例外 → Spring retry → DLQ
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_USER)
    public void consumeUserNotification(NotificationMessage msg) {
        log.debug("消費 USER 通知: userId={}, title={}", msg.getUserId(), msg.getTitle());

        boolean discordOk = trySendDiscordUser(msg);
        boolean lineOk = trySendLineUser(msg);

        // 全部失敗 → 拋例外觸發 retry → 最終進 DLQ
        if (!discordOk && !lineOk) {
            throw new RuntimeException(
                    "所有通知頻道發送失敗 userId=" + msg.getUserId() + " title=" + msg.getTitle());
        }
        // 至少一個成功 → auto ACK，不重試（避免已成功頻道重複發送）
    }

    /**
     * 消費管理員 / 系統通知（notification.admin queue）
     *
     * 路由邏輯（三種 type 完全分離，不重複派發）：
     * - SYSTEM              → sendNotificationToAdmins()     Admin per-user（多用戶模式：系統通知也給 Admin 看）
     * - ADMIN + displayName → sendNotificationToAdmins(dn)   Admin per-user（帶用戶前綴）
     * - ADMIN               → sendNotificationToAdmins()     Admin per-user only
     *
     * 切回單用戶模式時，只需把 SYSTEM 分支改回 sendNotification()，Producer 零改動。
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ADMIN)
    public void consumeAdminNotification(NotificationMessage msg) {
        log.debug("消費 ADMIN 通知: type={}, title={}, displayName={}",
                msg.getType(), msg.getTitle(), msg.getDisplayName());

        boolean discordOk = trySendDiscordAdmin(msg);
        boolean lineOk = trySendLineAdmin(msg);

        if (!discordOk && !lineOk) {
            throw new RuntimeException(
                    "所有通知頻道發送失敗 type=" + msg.getType() + " title=" + msg.getTitle());
        }
    }

    // ==================== Discord 發送 ====================

    private boolean trySendDiscordUser(NotificationMessage msg) {
        try {
            if (msg.getCategory() != null) {
                discordService.sendNotificationToUser(
                        msg.getUserId(), msg.getCategory(),
                        msg.getTitle(), msg.getMessage(), msg.getColor());
            } else {
                discordService.sendNotificationToUser(
                        msg.getUserId(), msg.getTitle(), msg.getMessage(), msg.getColor());
            }
            return true;
        } catch (Exception e) {
            log.error("Discord 用戶通知失敗 userId={}: {}", msg.getUserId(), e.getMessage());
            return false;
        }
    }

    private boolean trySendDiscordAdmin(NotificationMessage msg) {
        try {
            if (msg.getType() == NotificationMessage.NotificationType.SYSTEM) {
                discordService.sendNotificationToAdmins(msg.getTitle(), msg.getMessage(), msg.getColor());
            } else if (msg.getDisplayName() != null && !msg.getDisplayName().isBlank()) {
                discordService.sendNotificationToAdmins(
                        msg.getDisplayName(), msg.getTitle(), msg.getMessage(), msg.getColor());
            } else {
                discordService.sendNotificationToAdmins(msg.getTitle(), msg.getMessage(), msg.getColor());
            }
            return true;
        } catch (Exception e) {
            log.error("Discord Admin 通知失敗: {}", e.getMessage());
            return false;
        }
    }

    // ==================== LINE 發送 ====================

    private boolean trySendLineUser(NotificationMessage msg) {
        try {
            if (msg.getCategory() != null) {
                lineService.sendNotificationToUser(
                        msg.getUserId(), msg.getCategory(),
                        msg.getTitle(), msg.getMessage(), msg.getColor());
            } else {
                lineService.sendNotificationToUser(
                        msg.getUserId(), msg.getTitle(), msg.getMessage(), msg.getColor());
            }
            return true;
        } catch (Exception e) {
            log.error("LINE 用戶通知失敗 userId={}: {}", msg.getUserId(), e.getMessage());
            return false;
        }
    }

    private boolean trySendLineAdmin(NotificationMessage msg) {
        try {
            if (msg.getType() == NotificationMessage.NotificationType.SYSTEM) {
                lineService.sendNotificationToAdmins(msg.getTitle(), msg.getMessage(), msg.getColor());
            } else if (msg.getDisplayName() != null && !msg.getDisplayName().isBlank()) {
                lineService.sendNotificationToAdmins(
                        msg.getDisplayName(), msg.getTitle(), msg.getMessage(), msg.getColor());
            } else {
                lineService.sendNotificationToAdmins(msg.getTitle(), msg.getMessage(), msg.getColor());
            }
            return true;
        } catch (Exception e) {
            log.error("LINE Admin 通知失敗: {}", e.getMessage());
            return false;
        }
    }
}
