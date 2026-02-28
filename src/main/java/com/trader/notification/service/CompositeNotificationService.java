package com.trader.notification.service;

import com.trader.notification.model.NotificationCategory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * 複合通知服務 — 多頻道派發
 *
 * 同時委派 Discord + LINE 兩個通知頻道。
 * 標記 @Primary，所有注入 NotificationService 的地方都會拿到此 bean。
 * 各頻道服務自行判斷是否 enabled / 用戶是否有綁定。
 */
@Slf4j
@Primary
@Service
public class CompositeNotificationService implements NotificationService {

    private final DiscordWebhookService discordService;
    private final LineNotificationService lineService;

    public CompositeNotificationService(DiscordWebhookService discordService,
                                        LineNotificationService lineService) {
        this.discordService = discordService;
        this.lineService = lineService;
    }

    @Override
    public void sendNotification(String title, String message, int color) {
        discordService.sendNotification(title, message, color);
        lineService.sendNotification(title, message, color);
    }

    @Override
    public void sendNotificationToUser(String userId, String title, String message, int color) {
        discordService.sendNotificationToUser(userId, title, message, color);
        lineService.sendNotificationToUser(userId, title, message, color);
    }

    @Override
    public void sendNotificationToUser(String userId, NotificationCategory category,
                                       String title, String message, int color) {
        discordService.sendNotificationToUser(userId, category, title, message, color);
        lineService.sendNotificationToUser(userId, category, title, message, color);
    }

    @Override
    public void sendNotificationToAdmins(String title, String message, int color) {
        discordService.sendNotificationToAdmins(title, message, color);
        lineService.sendNotificationToAdmins(title, message, color);
    }

    @Override
    public void sendNotificationToAdmins(String displayName, String title, String message, int color) {
        discordService.sendNotificationToAdmins(displayName, title, message, color);
        lineService.sendNotificationToAdmins(displayName, title, message, color);
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
}
