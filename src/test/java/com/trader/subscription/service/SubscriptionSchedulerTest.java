package com.trader.subscription.service;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.subscription.entity.Subscription;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * SubscriptionScheduler 單元測試
 *
 * 測試重點：
 * 1. checkExpiredSubscriptions — 到期訂閱通知（per-user）
 * 2. remindExpiringSubscriptions — 即將到期提醒（per-user）
 * 3. 異常處理 — 通知失敗不影響排程
 *
 * 9 tests total
 */
class SubscriptionSchedulerTest {

    private SubscriptionService subscriptionService;
    private DiscordWebhookService discordWebhookService;
    private SubscriptionScheduler scheduler;

    @BeforeEach
    void setUp() {
        subscriptionService = mock(SubscriptionService.class);
        discordWebhookService = mock(DiscordWebhookService.class);
        scheduler = new SubscriptionScheduler(subscriptionService, discordWebhookService);
    }

    // ==================== checkExpiredSubscriptions ====================

    @Nested
    @DisplayName("checkExpiredSubscriptions")
    class CheckExpired {

        @Test
        @DisplayName("has expired subscriptions - sends per-user notification for each")
        void sendsPerUserNotificationForExpired() {
            Subscription sub1 = Subscription.builder()
                    .id(1L)
                    .userId("user-a")
                    .planId("basic")
                    .status(Subscription.Status.CANCELLED)
                    .currentPeriodEnd(LocalDateTime.of(2025, 5, 1, 0, 0))
                    .build();
            Subscription sub2 = Subscription.builder()
                    .id(2L)
                    .userId("user-b")
                    .planId("pro")
                    .status(Subscription.Status.CANCELLED)
                    .currentPeriodEnd(LocalDateTime.of(2025, 5, 2, 0, 0))
                    .build();

            when(subscriptionService.expireOverdueSubscriptions())
                    .thenReturn(List.of(sub1, sub2));

            scheduler.checkExpiredSubscriptions();

            // 每個用戶收到自己的通知
            verify(discordWebhookService).sendNotificationToUser(
                    eq("user-a"), eq("訂閱通知"), anyString(), eq(0xFF9900));
            verify(discordWebhookService).sendNotificationToUser(
                    eq("user-b"), eq("訂閱通知"), anyString(), eq(0xFF9900));
            // 不應使用全局通知
            verify(discordWebhookService, never())
                    .sendNotification(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("no expired subscriptions - does not send notification")
        void noNotificationWhenNoneExpired() {
            when(subscriptionService.expireOverdueSubscriptions())
                    .thenReturn(List.of());

            scheduler.checkExpiredSubscriptions();

            verify(discordWebhookService, never())
                    .sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("notification throws exception - does not crash scheduler")
        void notificationExceptionDoesNotCrash() {
            Subscription sub = Subscription.builder()
                    .id(1L)
                    .userId("user-a")
                    .planId("basic")
                    .status(Subscription.Status.CANCELLED)
                    .currentPeriodEnd(LocalDateTime.of(2025, 5, 1, 0, 0))
                    .build();

            when(subscriptionService.expireOverdueSubscriptions())
                    .thenReturn(List.of(sub));
            doThrow(new RuntimeException("Discord API down"))
                    .when(discordWebhookService)
                    .sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());

            // Should not throw
            Assertions.assertDoesNotThrow(() -> scheduler.checkExpiredSubscriptions());
        }
    }

    // ==================== remindExpiringSubscriptions ====================

    @Nested
    @DisplayName("remindExpiringSubscriptions")
    class RemindExpiring {

        @Test
        @DisplayName("has expiring subscriptions - sends per-user reminder for each")
        void sendsPerUserReminderForExpiring() {
            Subscription sub = Subscription.builder()
                    .id(3L)
                    .userId("user-c")
                    .planId("pro")
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(LocalDateTime.of(2025, 5, 28, 12, 0))
                    .build();

            when(subscriptionService.findExpiringSubscriptions(3))
                    .thenReturn(List.of(sub));

            scheduler.remindExpiringSubscriptions();

            verify(discordWebhookService).sendNotificationToUser(
                    eq("user-c"), eq("訂閱通知"), anyString(), eq(0xFF9900));
            verify(discordWebhookService, never())
                    .sendNotification(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("no expiring subscriptions - does not send reminder")
        void noReminderWhenNoneExpiring() {
            when(subscriptionService.findExpiringSubscriptions(3))
                    .thenReturn(List.of());

            scheduler.remindExpiringSubscriptions();

            verify(discordWebhookService, never())
                    .sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("reminder message contains userId and planId")
        void reminderMessageContainsUserInfo() {
            Subscription sub = Subscription.builder()
                    .id(4L)
                    .userId("user-d")
                    .planId("basic")
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(LocalDateTime.of(2025, 6, 1, 0, 0))
                    .build();

            when(subscriptionService.findExpiringSubscriptions(3))
                    .thenReturn(List.of(sub));

            scheduler.remindExpiringSubscriptions();

            verify(discordWebhookService).sendNotificationToUser(
                    eq("user-d"),
                    eq("訂閱通知"),
                    argThat(message -> message.contains("user-d") && message.contains("basic")),
                    eq(0xFF9900));
        }

        @Test
        @DisplayName("null currentPeriodEnd - message shows N/A")
        void nullPeriodEndShowsNA() {
            Subscription sub = Subscription.builder()
                    .id(5L)
                    .userId("user-e")
                    .planId("pro")
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(null)
                    .build();

            when(subscriptionService.findExpiringSubscriptions(3))
                    .thenReturn(List.of(sub));

            scheduler.remindExpiringSubscriptions();

            verify(discordWebhookService).sendNotificationToUser(
                    eq("user-e"),
                    eq("訂閱通知"),
                    argThat(message -> message.contains("N/A")),
                    eq(0xFF9900));
        }

        @Test
        @DisplayName("multiple expiring - each user gets own notification")
        void multipleExpiringUsersGetOwnNotification() {
            Subscription sub1 = Subscription.builder()
                    .id(6L).userId("user-f").planId("basic")
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(LocalDateTime.of(2025, 6, 1, 0, 0))
                    .build();
            Subscription sub2 = Subscription.builder()
                    .id(7L).userId("user-g").planId("pro")
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(LocalDateTime.of(2025, 6, 2, 0, 0))
                    .build();

            when(subscriptionService.findExpiringSubscriptions(3))
                    .thenReturn(List.of(sub1, sub2));

            scheduler.remindExpiringSubscriptions();

            verify(discordWebhookService).sendNotificationToUser(
                    eq("user-f"), eq("訂閱通知"), anyString(), eq(0xFF9900));
            verify(discordWebhookService).sendNotificationToUser(
                    eq("user-g"), eq("訂閱通知"), anyString(), eq(0xFF9900));
        }
    }
}
