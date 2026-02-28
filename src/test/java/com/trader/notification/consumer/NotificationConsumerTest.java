package com.trader.notification.consumer;

import com.trader.notification.model.NotificationCategory;
import com.trader.notification.model.NotificationMessage;
import com.trader.notification.model.NotificationMessage.NotificationType;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.LineNotificationService;
import com.trader.notification.service.NotificationService;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * NotificationConsumer 單元測試
 *
 * 驗證：
 * 1. USER 訊息 → 正確呼叫 sendNotificationToUser()
 * 2. ADMIN 訊息（有 displayName）→ 呼叫 sendNotificationToAdmins(displayName, ...)
 * 3. ADMIN 訊息（無 displayName）→ 呼叫 sendNotification() + sendNotificationToAdmins()
 * 4. 失敗 → exception 向上拋（交給 Spring retry → 耗盡 → DLQ）
 */
class NotificationConsumerTest {

    private DiscordWebhookService discordService;
    private LineNotificationService lineService;
    private NotificationConsumer consumer;

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordWebhookService.class);
        lineService = mock(LineNotificationService.class);
        consumer = new NotificationConsumer(discordService, lineService);
    }

    // ===== USER Queue 測試 =====

    @Test
    @DisplayName("USER 訊息 → sendNotificationToUser()")
    void consumeUserNotification_success() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.USER)
                .userId("user-1")
                .title("✅ ENTRY 成功")
                .message("BTCUSDT LONG")
                .color(NotificationService.COLOR_GREEN)
                .build();

        consumer.consumeUserNotification(msg);

        verify(discordService).sendNotificationToUser("user-1", "✅ ENTRY 成功", "BTCUSDT LONG", NotificationService.COLOR_GREEN);
        verify(lineService).sendNotificationToUser("user-1", "✅ ENTRY 成功", "BTCUSDT LONG", NotificationService.COLOR_GREEN);
    }

    @Test
    @DisplayName("USER 訊息（帶分類）→ sendNotificationToUser(category)")
    void consumeUserNotification_withCategory() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.USER)
                .userId("user-1")
                .category(NotificationCategory.TRADE_EXECUTION)
                .title("標題")
                .message("內容")
                .color(NotificationService.COLOR_BLUE)
                .build();

        consumer.consumeUserNotification(msg);

        verify(discordService).sendNotificationToUser("user-1", NotificationCategory.TRADE_EXECUTION,
                "標題", "內容", NotificationService.COLOR_BLUE);
        verify(lineService).sendNotificationToUser("user-1", NotificationCategory.TRADE_EXECUTION,
                "標題", "內容", NotificationService.COLOR_BLUE);
    }

    @Test
    @DisplayName("USER 訊息消費失敗 → exception 向上拋（Spring retry → DLQ）")
    void consumeUserNotification_failureThrowsForRetry() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.USER)
                .userId("user-1")
                .title("標題")
                .message("內容")
                .color(NotificationService.COLOR_RED)
                .build();

        doThrow(new RuntimeException("HTTP 500"))
                .when(discordService).sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());

        // exception 必須向上拋，Spring retry interceptor 才能接住重試
        assertThatThrownBy(() -> consumer.consumeUserNotification(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("HTTP 500");
    }

    // ===== ADMIN Queue 測試 =====

    @Test
    @DisplayName("ADMIN 訊息（有 displayName）→ sendNotificationToAdmins(displayName)")
    void consumeAdminNotification_withDisplayName() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.ADMIN)
                .displayName("Alice")
                .title("⚠️ 風控觸發")
                .message("槓桿超限")
                .color(NotificationService.COLOR_YELLOW)
                .build();

        consumer.consumeAdminNotification(msg);

        verify(discordService).sendNotificationToAdmins("Alice", "⚠️ 風控觸發", "槓桿超限", NotificationService.COLOR_YELLOW);
        verify(lineService).sendNotificationToAdmins("Alice", "⚠️ 風控觸發", "槓桿超限", NotificationService.COLOR_YELLOW);
        // 有 displayName 時不應呼叫 sendNotification()
        verify(discordService, never()).sendNotification(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("ADMIN 訊息（無 displayName）→ sendNotification() + sendNotificationToAdmins()")
    void consumeAdminNotification_withoutDisplayName() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.ADMIN)
                .title("🔄 啟動對帳完成")
                .message("PENDING_CLOSE 修復: 2 筆")
                .color(NotificationService.COLOR_BLUE)
                .build();

        consumer.consumeAdminNotification(msg);

        // 無 displayName = 系統級通知 → 同時呼叫 global + admin
        verify(discordService).sendNotification("🔄 啟動對帳完成", "PENDING_CLOSE 修復: 2 筆", NotificationService.COLOR_BLUE);
        verify(lineService).sendNotification("🔄 啟動對帳完成", "PENDING_CLOSE 修復: 2 筆", NotificationService.COLOR_BLUE);
        verify(discordService).sendNotificationToAdmins("🔄 啟動對帳完成", "PENDING_CLOSE 修復: 2 筆", NotificationService.COLOR_BLUE);
        verify(lineService).sendNotificationToAdmins("🔄 啟動對帳完成", "PENDING_CLOSE 修復: 2 筆", NotificationService.COLOR_BLUE);
    }

    @Test
    @DisplayName("ADMIN 訊息消費失敗 → exception 向上拋（Spring retry → DLQ）")
    void consumeAdminNotification_failureThrowsForRetry() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.ADMIN)
                .title("標題")
                .message("內容")
                .color(NotificationService.COLOR_RED)
                .build();

        doThrow(new RuntimeException("API timeout"))
                .when(discordService).sendNotification(anyString(), anyString(), anyInt());

        // exception 必須向上拋，Spring retry interceptor 才能接住重試
        assertThatThrownBy(() -> consumer.consumeAdminNotification(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("API timeout");
    }
}
