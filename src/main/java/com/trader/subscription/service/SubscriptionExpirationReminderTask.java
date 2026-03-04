package com.trader.subscription.service;

import com.trader.notification.service.NotificationService;
import com.trader.shared.config.AppConstants;
import com.trader.subscription.entity.Plan;
import com.trader.subscription.entity.Subscription;
import com.trader.subscription.repository.PlanRepository;
import com.trader.subscription.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 訂閱到期提醒排程任務
 *
 * 每天早上 10:00（Asia/Taipei）執行：
 * - 到期前 3 天：黃色警告提醒
 * - 到期前 1 天：紅色緊急提醒
 *
 * 防重複機制：計算精確天數差，只在剛好等於 3 天或 1 天時才發送
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubscriptionExpirationReminderTask {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final NotificationService notificationService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * 每天早上 10:00（Asia/Taipei）檢查即將到期訂閱並發送提醒
     */
    @Scheduled(cron = "0 0 10 * * *", zone = "${app.timezone:Asia/Taipei}")
    public void sendExpirationReminders() {
        sendExpirationReminders(LocalDate.now(AppConstants.ZONE_ID));
    }

    /**
     * 檢查即將到期訂閱並發送提醒（可指定基準日期，方便測試）
     */
    void sendExpirationReminders(LocalDate today) {
        log.info("開始執行訂閱到期提醒排程...");

        // 查詢未來 0~4 天內到期的 ACTIVE 訂閱（涵蓋 1 天和 3 天的範圍）
        LocalDateTime from = today.atStartOfDay();
        LocalDateTime to = today.plusDays(4).atStartOfDay();

        List<Subscription> expiringSubscriptions = subscriptionRepository.findActiveExpiringBetween(from, to);

        if (expiringSubscriptions.isEmpty()) {
            log.info("無即將到期的訂閱");
            return;
        }

        log.info("找到 {} 筆即將到期的訂閱，開始檢查並發送提醒", expiringSubscriptions.size());

        int sentCount = 0;
        for (Subscription subscription : expiringSubscriptions) {
            boolean sent = processSubscription(subscription, today);
            if (sent) {
                sentCount++;
            }
        }

        log.info("訂閱到期提醒排程完成，共發送 {} 則通知", sentCount);
    }

    /**
     * 處理單筆訂閱：根據到期天數決定是否發送提醒
     *
     * @return true 如果有發送通知
     */
    boolean processSubscription(Subscription subscription, LocalDate today) {
        LocalDateTime periodEnd = subscription.getCurrentPeriodEnd();
        if (periodEnd == null) {
            return false;
        }

        LocalDate expirationDate = periodEnd.toLocalDate();
        long daysUntilExpiration = ChronoUnit.DAYS.between(today, expirationDate);

        if (daysUntilExpiration == 3) {
            sendThreeDayReminder(subscription, expirationDate);
            return true;
        } else if (daysUntilExpiration == 1) {
            sendOneDayReminder(subscription);
            return true;
        }

        return false;
    }

    private void sendThreeDayReminder(Subscription subscription, LocalDate expirationDate) {
        String planName = resolvePlanName(subscription.getPlanId());
        String formattedDate = expirationDate.format(DATE_FORMATTER);

        String title = "⏰ 訂閱即將到期提醒";
        String message = String.format(
                "您的 %s 方案將於 %s 到期，請記得續費以繼續使用自動跟單功能。",
                planName, formattedDate);

        try {
            notificationService.sendNotificationToUser(
                    subscription.getUserId(), title, message, NotificationService.COLOR_YELLOW);
            log.info("已發送 3 天到期提醒：userId={}, planId={}, expirationDate={}",
                    subscription.getUserId(), subscription.getPlanId(), formattedDate);
        } catch (Exception e) {
            log.warn("發送 3 天到期提醒失敗：userId={}, error={}",
                    subscription.getUserId(), e.getMessage());
        }
    }

    private void sendOneDayReminder(Subscription subscription) {
        String planName = resolvePlanName(subscription.getPlanId());

        String title = "\uD83D\uDEA8 訂閱明天到期";
        String message = String.format(
                "您的 %s 方案將於明天到期！到期後自動跟單將會停止。",
                planName);

        try {
            notificationService.sendNotificationToUser(
                    subscription.getUserId(), title, message, NotificationService.COLOR_RED);
            log.info("已發送 1 天到期提醒：userId={}, planId={}",
                    subscription.getUserId(), subscription.getPlanId());
        } catch (Exception e) {
            log.warn("發送 1 天到期提醒失敗：userId={}, error={}",
                    subscription.getUserId(), e.getMessage());
        }
    }

    /**
     * 透過 planId 查詢方案名稱，查不到時 fallback 使用 planId
     */
    String resolvePlanName(String planId) {
        return planRepository.findById(planId)
                .map(Plan::getName)
                .orElse(planId);
    }
}
