package com.trader.notification.service;

import com.trader.notification.model.NotificationCategory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 複合通知服務 — 多頻道派發
 *
 * 同時委派 Discord + LINE 兩個通知頻道。
 * 標記 @Primary，所有注入 NotificationService 的地方都會拿到此 bean。
 * 各頻道服務自行判斷是否 enabled / 用戶是否有綁定。
 *
 * 重要：每個頻道都用 try-catch 隔離，確保：
 * 1. 單一頻道失敗不影響其他頻道
 * 2. 通知失敗不會阻塞交易流程
 */
@Slf4j
@Primary
@Service
@RequiredArgsConstructor
public class CompositeNotificationService implements NotificationService {

    private final DiscordWebhookService discordService;
    private final LineNotificationService lineService;


    @Override
    public void sendNotification(String title, String message, int color) {
        safeSend("Discord", () -> discordService.sendNotification(title, message, color));
        safeSend("LINE", () -> lineService.sendNotification(title, message, color));
    }

    @Override
    public void sendNotificationToUser(String userId, String title, String message, int color) {
        safeSend("Discord", () -> discordService.sendNotificationToUser(userId, title, message, color));
        safeSend("LINE", () -> lineService.sendNotificationToUser(userId, title, message, color));
    }

    @Override
    public void sendNotificationToUser(String userId, NotificationCategory category,
                                       String title, String message, int color) {
        safeSend("Discord", () -> discordService.sendNotificationToUser(userId, category, title, message, color));
        safeSend("LINE", () -> lineService.sendNotificationToUser(userId, category, title, message, color));
    }

    @Override
    public void sendNotificationToAdmins(String title, String message, int color) {
        safeSend("Discord", () -> discordService.sendNotificationToAdmins(title, message, color));
        safeSend("LINE", () -> lineService.sendNotificationToAdmins(title, message, color));
    }

    @Override
    public void sendNotificationToAdmins(String displayName, String title, String message, int color) {
        safeSend("Discord", () -> discordService.sendNotificationToAdmins(displayName, title, message, color));
        safeSend("LINE", () -> lineService.sendNotificationToAdmins(displayName, title, message, color));
    }

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
