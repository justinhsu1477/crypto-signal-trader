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
 * 為什麼不用 manual ACK？
 *   manual ACK 的 try-catch 會把 exception 吃掉，Spring retry interceptor 收不到 exception，
 *   導致 retry 設定變成死設定。用 auto ACK 讓 exception 向上拋，retry 才能正常運作。
 *
 * 消費流程：
 *   notification.user  → consumeUserNotification()  → sendNotificationToUser()   (per-user webhook)
 *   notification.admin → consumeAdminNotification() →
 *     SYSTEM type              → sendNotification()            (全局 webhook only)
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
     * 失敗處理：exception 自動向上拋 → Spring retry 重試 → 耗盡 → RepublishMessageRecoverer → DLQ
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_USER)
    public void consumeUserNotification(NotificationMessage msg) {
        log.debug("消費 USER 通知: userId={}, title={}", msg.getUserId(), msg.getTitle());

        if (msg.getCategory() != null) {
            discordService.sendNotificationToUser(
                    msg.getUserId(), msg.getCategory(),
                    msg.getTitle(), msg.getMessage(), msg.getColor());
            lineService.sendNotificationToUser(
                    msg.getUserId(), msg.getCategory(),
                    msg.getTitle(), msg.getMessage(), msg.getColor());
        } else {
            discordService.sendNotificationToUser(
                    msg.getUserId(), msg.getTitle(), msg.getMessage(), msg.getColor());
            lineService.sendNotificationToUser(
                    msg.getUserId(), msg.getTitle(), msg.getMessage(), msg.getColor());
        }
        // 成功 → Spring auto ACK（不需手動呼叫 channel.basicAck）
    }

    /**
     * 消費管理員 / 系統通知（notification.admin queue）
     *
     * 路由邏輯（三種 type 完全分離，不重複派發）：
     * - SYSTEM              → sendNotification()             全局 webhook only
     * - ADMIN + displayName → sendNotificationToAdmins(dn)   Admin per-user（帶用戶前綴）
     * - ADMIN               → sendNotificationToAdmins()     Admin per-user only
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ADMIN)
    public void consumeAdminNotification(NotificationMessage msg) {
        log.debug("消費 ADMIN 通知: type={}, title={}, displayName={}",
                msg.getType(), msg.getTitle(), msg.getDisplayName());

        if (msg.getType() == NotificationMessage.NotificationType.SYSTEM) {
            // 全局通知 → 只發到全局 webhook（不發到 admin per-user）
            discordService.sendNotification(msg.getTitle(), msg.getMessage(), msg.getColor());
            lineService.sendNotification(msg.getTitle(), msg.getMessage(), msg.getColor());
        } else if (msg.getDisplayName() != null && !msg.getDisplayName().isBlank()) {
            // 帶用戶名前綴的 admin 通知（風控告警等）
            discordService.sendNotificationToAdmins(
                    msg.getDisplayName(), msg.getTitle(), msg.getMessage(), msg.getColor());
            lineService.sendNotificationToAdmins(
                    msg.getDisplayName(), msg.getTitle(), msg.getMessage(), msg.getColor());
        } else {
            // Admin 通知 → 只發到 admin per-user webhook（不發到全局）
            discordService.sendNotificationToAdmins(msg.getTitle(), msg.getMessage(), msg.getColor());
            lineService.sendNotificationToAdmins(msg.getTitle(), msg.getMessage(), msg.getColor());
        }
        // 成功 → Spring auto ACK
        // 失敗 → exception 向上拋 → Spring retry (max 3 次, 指數退避) → DLQ
    }
}
