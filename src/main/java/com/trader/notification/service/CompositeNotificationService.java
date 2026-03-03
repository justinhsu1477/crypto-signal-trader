package com.trader.notification.service;

import com.trader.notification.config.RabbitMQConfig;
import com.trader.notification.model.NotificationCategory;
import com.trader.notification.model.NotificationMessage;
import com.trader.notification.model.NotificationMessage.NotificationType;
import com.trader.shared.service.MetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 複合通知服務 — MQ Producer
 *
 * <pre>
 * 改造前：直接呼叫 Discord/LINE Service（同步）
 * 改造後：發訊息到 RabbitMQ（非同步），由 Consumer 消費
 *
 * 面試重點：
 *   - Producer 只管發訊息，不管誰消費、怎麼消費 → 解耦
 *   - RabbitTemplate.convertAndSend() 自動用 Jackson JSON 序列化
 *   - Graceful Degradation：MQ 掛了降級回直接呼叫 → 不影響通知功能
 *
 * 路由規則（全局與 Admin 完全分離，不重複派發）：
 *   sendNotification()         → ADMIN queue（type=SYSTEM → 全局 webhook only）
 *   sendNotificationToUser()   → USER queue（type=USER → per-user webhook）
 *   sendNotificationToAdmins() → ADMIN queue（type=ADMIN → Admin per-user only）
 *
 * 標記 @Primary，所有注入 NotificationService 的地方都會拿到此 bean。
 * </pre>
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class CompositeNotificationService implements NotificationService {

    private final DiscordWebhookService discordService;
    private final LineNotificationService lineService;
    private final RabbitTemplate rabbitTemplate;
    private final MetricsService metricsService;

    // ===== Producer 方法：發訊息到 MQ =====

    @Override
    public void sendNotification(String title, String message, int color) {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.SYSTEM)
                .title(title)
                .message(message)
                .color(color)
                .timestamp(LocalDateTime.now())
                .build();

        publishOrFallback(RabbitMQConfig.ROUTING_KEY_ADMIN, msg, () -> {
            safeSend("Discord", () -> discordService.sendNotification(title, message, color));
            safeSend("LINE", () -> lineService.sendNotification(title, message, color));
        });
    }

    @Override
    public void sendNotificationToUser(String userId, String title, String message, int color) {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.USER)
                .userId(userId)
                .title(title)
                .message(message)
                .color(color)
                .timestamp(LocalDateTime.now())
                .build();

        publishOrFallback(RabbitMQConfig.ROUTING_KEY_USER, msg, () -> {
            safeSend("Discord", () -> discordService.sendNotificationToUser(userId, title, message, color));
            safeSend("LINE", () -> lineService.sendNotificationToUser(userId, title, message, color));
        });
    }

    @Override
    public void sendNotificationToUser(String userId, NotificationCategory category,
                                       String title, String message, int color) {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.USER)
                .userId(userId)
                .category(category)
                .title(title)
                .message(message)
                .color(color)
                .timestamp(LocalDateTime.now())
                .build();

        publishOrFallback(RabbitMQConfig.ROUTING_KEY_USER, msg, () -> {
            safeSend("Discord", () -> discordService.sendNotificationToUser(userId, category, title, message, color));
            safeSend("LINE", () -> lineService.sendNotificationToUser(userId, category, title, message, color));
        });
    }

    @Override
    public void sendNotificationToAdmins(String title, String message, int color) {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.ADMIN)
                .title(title)
                .message(message)
                .color(color)
                .timestamp(LocalDateTime.now())
                .build();

        publishOrFallback(RabbitMQConfig.ROUTING_KEY_ADMIN, msg, () -> {
            safeSend("Discord", () -> discordService.sendNotificationToAdmins(title, message, color));
            safeSend("LINE", () -> lineService.sendNotificationToAdmins(title, message, color));
        });
    }

    @Override
    public void sendNotificationToAdmins(String displayName, String title, String message, int color) {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.ADMIN)
                .displayName(displayName)
                .title(title)
                .message(message)
                .color(color)
                .timestamp(LocalDateTime.now())
                .build();

        publishOrFallback(RabbitMQConfig.ROUTING_KEY_ADMIN, msg, () -> {
            safeSend("Discord", () -> discordService.sendNotificationToAdmins(displayName, title, message, color));
            safeSend("LINE", () -> lineService.sendNotificationToAdmins(displayName, title, message, color));
        });
    }

    // ===== Cache 操作：直接轉發（不經 MQ）=====

    @Override
    public void evictUserCache(String userId) {
        discordService.evictUserCache(userId);
        lineService.evictUserCache(userId);
    }

    @Override
    public void evictAllCache() {
        discordService.evictAllCache();
        lineService.evictAllCache();
    }

    // ===== 內部方法 =====

    /**
     * 發送到 MQ，失敗時降級回直接呼叫
     *
     * <pre>
     * 面試重點：Graceful Degradation（優雅降級）
     *   - 正常：Producer → MQ → Consumer → Discord/LINE
     *   - MQ 掛了：Producer → 直接呼叫 Discord/LINE（跳過 MQ）
     *   - 效果：MQ 掛了不影響通知，只是少了持久化 + 重試的好處
     * </pre>
     */
    private void publishOrFallback(String routingKey, NotificationMessage msg, Runnable fallback) {
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, routingKey, msg);
            // MQ 發送成功 → 記錄指標（實際派送成功/失敗由 Consumer 記錄）
            recordNotificationMetrics(msg, true);
        } catch (Exception e) {
            log.warn("RabbitMQ 發送失敗，降級回直接呼叫: {}", e.getMessage());
            recordNotificationMetrics(msg, false);
            fallback.run();
        }
    }

    /** 記錄通知指標 — 根據 msg 中的 type 推斷頻道 */
    private void recordNotificationMetrics(NotificationMessage msg, boolean success) {
        if (metricsService == null) return;
        // 簡化：system/admin 訊息算 discord+line 各一次，user 訊息算 per-user 一次
        String channel = msg.getType() == NotificationType.USER ? "user" : "system";
        metricsService.recordNotification(channel, success);
    }

    /**
     * 安全發送 — 任何頻道的異常都不會影響其他頻道或交易流程
     */
    private void safeSend(String channel, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.error("{} 通知發送異常（已隔離，不影響交易流程）: {}", channel, e.getMessage(), e);
        }
    }
}
