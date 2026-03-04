package com.trader.subscription.service;

import com.trader.notification.service.NotificationService;
import com.trader.subscription.entity.Plan;
import com.trader.subscription.entity.Subscription;
import com.trader.subscription.repository.PlanRepository;
import com.trader.subscription.repository.SubscriptionRepository;
import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;

/**
 * SubscriptionExpirationReminderTask 單元測試
 *
 * 測試場景：
 * 1. 3 天前提醒 — 黃色警告
 * 2. 1 天前提醒 — 紅色緊急
 * 3. 2 天前 — 不發送（防重複）
 * 4. 已過期 — 不發送
 * 5. LIFETIME 不提醒（currentPeriodEnd = null）
 * 6. 無到期者不發送
 * 7. 方案名稱查詢 — 正常解析 / fallback
 * 8. 通知失敗不影響排程
 *
 * 10 tests total
 */
class SubscriptionExpirationReminderTaskTest {

    private SubscriptionRepository subscriptionRepository;
    private PlanRepository planRepository;
    private NotificationService notificationService;
    private SubscriptionExpirationReminderTask task;

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);

    @BeforeEach
    void setUp() {
        subscriptionRepository = mock(SubscriptionRepository.class);
        planRepository = mock(PlanRepository.class);
        notificationService = mock(NotificationService.class);
        task = new SubscriptionExpirationReminderTask(
                subscriptionRepository, planRepository, notificationService);

        // 預設 Plan 查詢
        when(planRepository.findById("basic")).thenReturn(
                Optional.of(Plan.builder().planId("basic").name("基礎方案").build()));
        when(planRepository.findById("pro")).thenReturn(
                Optional.of(Plan.builder().planId("pro").name("專業方案").build()));
    }

    // ==================== 3 天前提醒 ====================

    @Nested
    @DisplayName("3 天前到期提醒")
    class ThreeDayReminder {

        @Test
        @DisplayName("到期日剛好 3 天後 — 發送黃色提醒")
        void sendsYellowReminderThreeDaysBefore() {
            // 今天 6/15，到期日 6/18 → 差 3 天
            Subscription sub = buildSubscription(1L, "user-a", "basic",
                    LocalDateTime.of(2025, 6, 18, 23, 59));

            when(subscriptionRepository.findActiveExpiringBetween(any(), any()))
                    .thenReturn(List.of(sub));

            task.processSubscription(sub, TODAY);

            verify(notificationService).sendNotificationToUser(
                    eq("user-a"),
                    eq("⏰ 訂閱即將到期提醒"),
                    argThat(msg -> msg.contains("基礎方案") && msg.contains("2025/06/18")),
                    eq(NotificationService.COLOR_YELLOW));
        }

        @Test
        @DisplayName("3 天提醒訊息包含方案名稱和到期日")
        void threeDayMessageContainsPlanNameAndDate() {
            Subscription sub = buildSubscription(2L, "user-b", "pro",
                    LocalDateTime.of(2025, 6, 18, 0, 0));

            task.processSubscription(sub, TODAY);

            verify(notificationService).sendNotificationToUser(
                    eq("user-b"),
                    eq("⏰ 訂閱即將到期提醒"),
                    argThat(msg -> msg.contains("專業方案")
                            && msg.contains("2025/06/18")
                            && msg.contains("續費")),
                    eq(NotificationService.COLOR_YELLOW));
        }
    }

    // ==================== 1 天前提醒 ====================

    @Nested
    @DisplayName("1 天前到期提醒")
    class OneDayReminder {

        @Test
        @DisplayName("到期日剛好 1 天後 — 發送紅色緊急提醒")
        void sendsRedReminderOneDayBefore() {
            // 今天 6/15，到期日 6/16 → 差 1 天
            Subscription sub = buildSubscription(3L, "user-c", "pro",
                    LocalDateTime.of(2025, 6, 16, 12, 0));

            task.processSubscription(sub, TODAY);

            verify(notificationService).sendNotificationToUser(
                    eq("user-c"),
                    eq("\uD83D\uDEA8 訂閱明天到期"),
                    argThat(msg -> msg.contains("專業方案") && msg.contains("明天到期")),
                    eq(NotificationService.COLOR_RED));
        }
    }

    // ==================== 防重複：2 天不發送 ====================

    @Nested
    @DisplayName("防重複機制")
    class AntiDuplicate {

        @Test
        @DisplayName("到期日 2 天後 — 不發送通知")
        void noReminderForTwoDays() {
            // 今天 6/15，到期日 6/17 → 差 2 天，不該發送
            Subscription sub = buildSubscription(4L, "user-d", "basic",
                    LocalDateTime.of(2025, 6, 17, 0, 0));

            task.processSubscription(sub, TODAY);

            verify(notificationService, never())
                    .sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("到期日 0 天（今天到期） — 不發送通知")
        void noReminderForToday() {
            // 今天 6/15，到期日 6/15 → 差 0 天
            Subscription sub = buildSubscription(5L, "user-e", "basic",
                    LocalDateTime.of(2025, 6, 15, 23, 59));

            task.processSubscription(sub, TODAY);

            verify(notificationService, never())
                    .sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());
        }
    }

    // ==================== 已過期不提醒 ====================

    @Test
    @DisplayName("已過期（到期日在過去） — 不發送通知")
    void noReminderForExpired() {
        // 今天 6/15，到期日 6/14 → 已過期
        Subscription sub = buildSubscription(6L, "user-f", "basic",
                LocalDateTime.of(2025, 6, 14, 0, 0));

        task.processSubscription(sub, TODAY);

        verify(notificationService, never())
                .sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());
    }

    // ==================== LIFETIME 不提醒 ====================

    @Test
    @DisplayName("LIFETIME 訂閱（currentPeriodEnd = null） — 不發送通知")
    void noReminderForLifetime() {
        Subscription sub = Subscription.builder()
                .id(7L)
                .userId("user-g")
                .planId("pro")
                .status(Subscription.Status.LIFETIME)
                .currentPeriodEnd(null)
                .build();

        task.processSubscription(sub, TODAY);

        verify(notificationService, never())
                .sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());
    }

    // ==================== 無到期者不發送 ====================

    @Test
    @DisplayName("無即將到期訂閱 — 不發送任何通知")
    void noNotificationWhenNoExpiringSubscriptions() {
        when(subscriptionRepository.findActiveExpiringBetween(any(), any()))
                .thenReturn(List.of());

        task.sendExpirationReminders(TODAY);

        verify(notificationService, never())
                .sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());
    }

    // ==================== 方案名稱 fallback ====================

    @Test
    @DisplayName("Plan 查不到時 fallback 使用 planId")
    void fallbackToPlanIdWhenPlanNotFound() {
        when(planRepository.findById("unknown")).thenReturn(Optional.empty());

        String result = task.resolvePlanName("unknown");

        Assertions.assertEquals("unknown", result);
    }

    // ==================== 通知失敗不影響排程 ====================

    @Test
    @DisplayName("通知發送失敗 — 不中斷排程處理其他訂閱")
    void notificationFailureDoesNotCrash() {
        Subscription sub1 = buildSubscription(8L, "user-h", "basic",
                LocalDateTime.of(2025, 6, 18, 0, 0)); // 3 天
        Subscription sub2 = buildSubscription(9L, "user-i", "pro",
                LocalDateTime.of(2025, 6, 16, 0, 0)); // 1 天

        when(subscriptionRepository.findActiveExpiringBetween(any(), any()))
                .thenReturn(List.of(sub1, sub2));

        // 第一個用戶通知失敗
        doThrow(new RuntimeException("Discord API down"))
                .when(notificationService)
                .sendNotificationToUser(eq("user-h"), anyString(), anyString(), anyInt());

        // 排程不應拋出例外
        Assertions.assertDoesNotThrow(() -> task.sendExpirationReminders(TODAY));

        // 第二個用戶仍應收到通知
        verify(notificationService).sendNotificationToUser(
                eq("user-i"),
                eq("\uD83D\uDEA8 訂閱明天到期"),
                anyString(),
                eq(NotificationService.COLOR_RED));
    }

    // ==================== Helper ====================

    private Subscription buildSubscription(Long id, String userId, String planId,
                                            LocalDateTime periodEnd) {
        return Subscription.builder()
                .id(id)
                .userId(userId)
                .planId(planId)
                .status(Subscription.Status.ACTIVE)
                .currentPeriodEnd(periodEnd)
                .build();
    }
}
