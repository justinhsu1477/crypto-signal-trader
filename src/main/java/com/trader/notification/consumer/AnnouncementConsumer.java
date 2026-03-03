package com.trader.notification.consumer;

import com.trader.notification.config.RabbitMQConfig;
import com.trader.notification.model.AnnouncementMessage;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.LineNotificationService;
import com.trader.notification.service.NotificationService;
import com.trader.user.repository.UserLineBindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 公告消費者 — 從 RabbitMQ Fanout Exchange 接收公告，推送到 Discord / LINE
 *
 * <pre>
 * 拓撲：
 *   Fanout Exchange (announcement.fanout)
 *       │
 *       ├── announcement.discord → consumeDiscord()  → DiscordWebhookService.sendNotification()
 *       └── announcement.line    → consumeLine()     → LineNotificationService.sendNotificationToUser()
 *
 * 面試重點：
 *   - Fanout 讓每個 consumer 都收到相同訊息，各自獨立消費
 *   - Discord consumer 失敗不影響 LINE consumer（queue 獨立）
 *   - 每個 queue 各自有 DLQ，失敗的訊息不會互相干擾
 *   - shouldSendTo() 讓 Admin 可以選擇性發送（只 Discord、只 LINE、或全部）
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnnouncementConsumer {

    private final DiscordWebhookService discordService;
    private final LineNotificationService lineService;
    private final UserLineBindingRepository lineBindingRepository;

    /**
     * Discord 公告消費者
     *
     * 發送到全域 Discord Webhook（公告頻道，所有人可見）。
     * 使用 Embed 格式，顏色依 priority 映射。
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ANNOUNCEMENT_DISCORD)
    public void consumeDiscord(AnnouncementMessage msg) {
        if (!shouldSendTo(msg.getChannels(), "DISCORD")) {
            log.debug("公告跳過 Discord（channels={}）: id={}", msg.getChannels(), msg.getAnnouncementId());
            return;
        }

        String title = formatTitle(msg);
        int color = priorityToColor(msg.getPriority());

        discordService.sendNotification(title, msg.getContent(), color);
        log.info("公告推送 Discord 完成: id={}, title={}", msg.getAnnouncementId(), msg.getTitle());
    }

    /**
     * LINE 公告消費者
     *
     * 逐用戶推送到已綁定 LINE 的用戶。
     *
     * 面試重點：為什麼不用 LINE Broadcast API？
     * → Broadcast 送給「所有加入 Bot 的人」，包含未註冊的 LINE 用戶。
     *   我們只想推給「已綁定帳號」的用戶，所以用 Push API 逐個發送。
     *   若用戶量大（> 500），可改用 LINE Multicast API（一次最多 500 人）。
     */
    @RabbitListener(queues = RabbitMQConfig.QUEUE_ANNOUNCEMENT_LINE)
    public void consumeLine(AnnouncementMessage msg) {
        if (!shouldSendTo(msg.getChannels(), "LINE")) {
            log.debug("公告跳過 LINE（channels={}）: id={}", msg.getChannels(), msg.getAnnouncementId());
            return;
        }

        String title = formatTitle(msg);
        int color = priorityToColor(msg.getPriority());

        List<String> boundUserIds = lineBindingRepository.findUserIdsWithEnabledBinding();
        log.info("公告推送 LINE: id={}, 綁定用戶數={}", msg.getAnnouncementId(), boundUserIds.size());

        for (String userId : boundUserIds) {
            try {
                lineService.sendNotificationToUser(userId, title, msg.getContent(), color);
            } catch (Exception e) {
                log.warn("LINE 推送失敗（跳過）: userId={}, error={}", userId, e.getMessage());
            }
        }

        log.info("公告推送 LINE 完成: id={}", msg.getAnnouncementId());
    }

    // ===== 工具方法 =====

    /**
     * 檢查此公告是否應發送到指定頻道
     *
     * channels = "ALL" → 所有頻道
     * channels = "DISCORD,LINE" → 只發 Discord 和 LINE
     * channels = "WEBSOCKET" → 只發 WebSocket（不經 RabbitMQ）
     */
    boolean shouldSendTo(String channels, String target) {
        if (channels == null || channels.isBlank()) return true;
        if ("ALL".equalsIgnoreCase(channels.trim())) return true;
        return channels.toUpperCase().contains(target.toUpperCase());
    }

    /** 格式化標題：加上分類前綴 */
    private String formatTitle(AnnouncementMessage msg) {
        String categoryLabel = switch (msg.getCategory()) {
            case "MAINTENANCE" -> "🔧 系統維護";
            case "UPDATE" -> "🚀 功能更新";
            case "URGENT" -> "🚨 緊急通知";
            case "PROMOTION" -> "🎉 活動推廣";
            default -> "📢 公告";
        };
        return categoryLabel + " | " + msg.getTitle();
    }

    /** 優先級映射為 Discord Embed 顏色 */
    private int priorityToColor(String priority) {
        return switch (priority) {
            case "CRITICAL" -> NotificationService.COLOR_RED;
            case "HIGH" -> 0xFF8C00;  // 深橘色
            case "LOW" -> 0x808080;   // 灰色
            default -> NotificationService.COLOR_BLUE;
        };
    }
}
