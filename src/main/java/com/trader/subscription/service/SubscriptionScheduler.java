package com.trader.subscription.service;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.subscription.entity.Subscription;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 訂閱排程任務
 *
 * 1. 每天凌晨 1:00 — 檢查並標記到期訂閱
 * 2. 每天上午 10:00 — 提醒即將到期的訂閱（3 天內）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionScheduler {

    private final SubscriptionService subscriptionService;
    private final DiscordWebhookService discordWebhookService;

    /**
     * 每天凌晨 1:00（Asia/Taipei）— 標記到期訂閱
     */
    @Scheduled(cron = "0 0 1 * * *", zone = "Asia/Taipei")
    public void checkExpiredSubscriptions() {
        log.info("開始檢查到期訂閱...");
        List<Subscription> expired = subscriptionService.expireOverdueSubscriptions();

        if (!expired.isEmpty()) {
            log.info("共 {} 筆訂閱已到期", expired.size());
            for (Subscription sub : expired) {
                sendExpirationNotice(sub);
            }
        }
    }

    /**
     * 每天上午 10:00（Asia/Taipei）— 提醒即將到期（3 天內）
     */
    @Scheduled(cron = "0 0 10 * * *", zone = "Asia/Taipei")
    public void remindExpiringSubscriptions() {
        log.info("開始檢查即將到期訂閱...");
        List<Subscription> expiring = subscriptionService.findExpiringSubscriptions(3);

        if (!expiring.isEmpty()) {
            log.info("共 {} 筆訂閱即將到期", expiring.size());
            for (Subscription sub : expiring) {
                sendExpiringReminder(sub);
            }
        }
    }

    private void sendExpirationNotice(Subscription sub) {
        try {
            String message = String.format(
                    "⚠️ **訂閱到期通知**\n用戶: `%s`\n方案: %s\n到期時間: %s\n狀態已自動標記為 CANCELLED",
                    sub.getUserId(), sub.getPlanId(),
                    sub.getCurrentPeriodEnd() != null ? sub.getCurrentPeriodEnd().toLocalDate() : "N/A");
            discordWebhookService.sendNotificationToUser(sub.getUserId(), "訂閱通知", message, 0xFF9900);
        } catch (Exception e) {
            log.warn("發送到期通知失敗: userId={}, error={}", sub.getUserId(), e.getMessage());
        }
    }

    private void sendExpiringReminder(Subscription sub) {
        try {
            String message = String.format(
                    "🔔 **訂閱即將到期提醒**\n用戶: `%s`\n方案: %s\n到期時間: %s\n請記得續費以保持服務！",
                    sub.getUserId(), sub.getPlanId(),
                    sub.getCurrentPeriodEnd() != null ? sub.getCurrentPeriodEnd().toLocalDate() : "N/A");
            discordWebhookService.sendNotificationToUser(sub.getUserId(), "訂閱通知", message, 0xFF9900);
        } catch (Exception e) {
            log.warn("發送續費提醒失敗: userId={}, error={}", sub.getUserId(), e.getMessage());
        }
    }
}
