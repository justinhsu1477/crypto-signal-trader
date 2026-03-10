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
 * 2. SYSTEM 訊息 → 路由到 sendNotificationToAdmins()（多用戶模式：Admin per-user）
 * 3. ADMIN 訊息（有 displayName）→ 呼叫 sendNotificationToAdmins(displayName, ...)
 * 4. ADMIN 訊息（無 displayName）→ 只呼叫 sendNotificationToAdmins()（admin per-user only）
 * 5. 頻道隔離 → 單頻道失敗不影響另一頻道，全部失敗才進 DLQ
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

    // ===== USER Queue — 頻道隔離測試 =====

    @Test
    @DisplayName("Discord 失敗 + LINE 成功 → 不拋例外（LINE 已送達，不重試避免 LINE 重複）")
    void consumeUser_discordFails_lineSucceeds_noRetry() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.USER)
                .userId("user-1")
                .title("標題").message("內容")
                .color(NotificationService.COLOR_GREEN)
                .build();

        doThrow(new RuntimeException("Discord HTTP 500"))
                .when(discordService).sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());

        // 不拋例外 → auto ACK → 不重試
        consumer.consumeUserNotification(msg);

        verify(lineService).sendNotificationToUser("user-1", "標題", "內容", NotificationService.COLOR_GREEN);
    }

    @Test
    @DisplayName("Discord 成功 + LINE 失敗 → 不拋例外（Discord 已送達，不重試避免 Discord 重複）")
    void consumeUser_discordSucceeds_lineFails_noRetry() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.USER)
                .userId("user-1")
                .title("標題").message("內容")
                .color(NotificationService.COLOR_GREEN)
                .build();

        doThrow(new RuntimeException("LINE API timeout"))
                .when(lineService).sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());

        // 不拋例外 → auto ACK
        consumer.consumeUserNotification(msg);

        verify(discordService).sendNotificationToUser("user-1", "標題", "內容", NotificationService.COLOR_GREEN);
    }

    @Test
    @DisplayName("Discord + LINE 全部失敗 → 拋例外（Spring retry → DLQ）")
    void consumeUser_allFail_throwsForRetry() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.USER)
                .userId("user-1")
                .title("標題").message("內容")
                .color(NotificationService.COLOR_RED)
                .build();

        doThrow(new RuntimeException("Discord HTTP 500"))
                .when(discordService).sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());
        doThrow(new RuntimeException("LINE API timeout"))
                .when(lineService).sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> consumer.consumeUserNotification(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("所有通知頻道發送失敗");
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
        verify(discordService, never()).sendNotification(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("SYSTEM 訊息 → 路由到 sendNotificationToAdmins()（多用戶模式：Admin per-user）")
    void consumeAdminNotification_systemType_routesToAdmin() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.SYSTEM)
                .title("📊 每日彙總報告")
                .message("今日損益: +$500")
                .color(NotificationService.COLOR_BLUE)
                .build();

        consumer.consumeAdminNotification(msg);

        verify(discordService).sendNotificationToAdmins("📊 每日彙總報告", "今日損益: +$500", NotificationService.COLOR_BLUE);
        verify(lineService).sendNotificationToAdmins("📊 每日彙總報告", "今日損益: +$500", NotificationService.COLOR_BLUE);
        verify(discordService, never()).sendNotification(anyString(), anyString(), anyInt());
        verify(lineService, never()).sendNotification(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("ADMIN 訊息（無 displayName）→ 只呼叫 sendNotificationToAdmins()（admin per-user only）")
    void consumeAdminNotification_withoutDisplayName_onlyAdminPerUser() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.ADMIN)
                .title("🔄 啟動對帳完成")
                .message("PENDING_CLOSE 修復: 2 筆")
                .color(NotificationService.COLOR_BLUE)
                .build();

        consumer.consumeAdminNotification(msg);

        verify(discordService).sendNotificationToAdmins("🔄 啟動對帳完成", "PENDING_CLOSE 修復: 2 筆", NotificationService.COLOR_BLUE);
        verify(lineService).sendNotificationToAdmins("🔄 啟動對帳完成", "PENDING_CLOSE 修復: 2 筆", NotificationService.COLOR_BLUE);
        verify(discordService, never()).sendNotification(anyString(), anyString(), anyInt());
        verify(lineService, never()).sendNotification(anyString(), anyString(), anyInt());
    }

    // ===== ADMIN Queue — 頻道隔離測試 =====

    @Test
    @DisplayName("ADMIN — Discord 失敗 + LINE 成功 → 不拋例外")
    void consumeAdmin_discordFails_lineSucceeds_noRetry() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.ADMIN)
                .title("標題").message("內容")
                .color(NotificationService.COLOR_RED)
                .build();

        doThrow(new RuntimeException("Discord HTTP 500"))
                .when(discordService).sendNotificationToAdmins(anyString(), anyString(), anyInt());

        consumer.consumeAdminNotification(msg);

        verify(lineService).sendNotificationToAdmins("標題", "內容", NotificationService.COLOR_RED);
    }

    @Test
    @DisplayName("ADMIN — 全部失敗 → 拋例外（Spring retry → DLQ）")
    void consumeAdmin_allFail_throwsForRetry() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.ADMIN)
                .title("標題").message("內容")
                .color(NotificationService.COLOR_RED)
                .build();

        doThrow(new RuntimeException("Discord HTTP 500"))
                .when(discordService).sendNotificationToAdmins(anyString(), anyString(), anyInt());
        doThrow(new RuntimeException("LINE API timeout"))
                .when(lineService).sendNotificationToAdmins(anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> consumer.consumeAdminNotification(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("所有通知頻道發送失敗");
    }

    @Test
    @DisplayName("SYSTEM — 全部失敗 → 拋例外（Spring retry → DLQ）")
    void consumeAdmin_systemAllFail_throwsForRetry() {
        NotificationMessage msg = NotificationMessage.builder()
                .type(NotificationType.SYSTEM)
                .title("標題").message("內容")
                .color(NotificationService.COLOR_RED)
                .build();

        doThrow(new RuntimeException("Discord HTTP 500"))
                .when(discordService).sendNotificationToAdmins(anyString(), anyString(), anyInt());
        doThrow(new RuntimeException("LINE API timeout"))
                .when(lineService).sendNotificationToAdmins(anyString(), anyString(), anyInt());

        assertThatThrownBy(() -> consumer.consumeAdminNotification(msg))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("所有通知頻道發送失敗");
    }
}
