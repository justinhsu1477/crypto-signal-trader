package com.trader.notification.service;

import com.trader.notification.model.NotificationCategory;
import org.junit.jupiter.api.*;

import static org.mockito.Mockito.*;

/**
 * CompositeNotificationService 單元測試
 *
 * 驗證所有方法都同時委派到 Discord + LINE 兩個服務。
 */
class CompositeNotificationServiceTest {

    private DiscordWebhookService discordService;
    private LineNotificationService lineService;
    private CompositeNotificationService composite;

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordWebhookService.class);
        lineService = mock(LineNotificationService.class);
        composite = new CompositeNotificationService(discordService, lineService);
    }

    @Test
    @DisplayName("sendNotification 委派到兩個服務")
    void sendNotificationDelegatesToBoth() {
        composite.sendNotification("標題", "內容", NotificationService.COLOR_GREEN);

        verify(discordService).sendNotification("標題", "內容", NotificationService.COLOR_GREEN);
        verify(lineService).sendNotification("標題", "內容", NotificationService.COLOR_GREEN);
    }

    @Test
    @DisplayName("sendNotificationToUser 委派到兩個服務")
    void sendNotificationToUserDelegatesToBoth() {
        composite.sendNotificationToUser("user-1", "標題", "內容", NotificationService.COLOR_RED);

        verify(discordService).sendNotificationToUser("user-1", "標題", "內容", NotificationService.COLOR_RED);
        verify(lineService).sendNotificationToUser("user-1", "標題", "內容", NotificationService.COLOR_RED);
    }

    @Test
    @DisplayName("sendNotificationToUser (帶分類) 委派到兩個服務")
    void sendNotificationToUserWithCategoryDelegatesToBoth() {
        composite.sendNotificationToUser("user-1", NotificationCategory.TRADE_EXECUTION,
                "標題", "內容", NotificationService.COLOR_BLUE);

        verify(discordService).sendNotificationToUser("user-1", NotificationCategory.TRADE_EXECUTION,
                "標題", "內容", NotificationService.COLOR_BLUE);
        verify(lineService).sendNotificationToUser("user-1", NotificationCategory.TRADE_EXECUTION,
                "標題", "內容", NotificationService.COLOR_BLUE);
    }

    @Test
    @DisplayName("sendNotificationToAdmins 委派到兩個服務")
    void sendNotificationToAdminsDelegatesToBoth() {
        composite.sendNotificationToAdmins("標題", "內容", NotificationService.COLOR_YELLOW);

        verify(discordService).sendNotificationToAdmins("標題", "內容", NotificationService.COLOR_YELLOW);
        verify(lineService).sendNotificationToAdmins("標題", "內容", NotificationService.COLOR_YELLOW);
    }

    @Test
    @DisplayName("sendNotificationToAdmins (帶 displayName) 委派到兩個服務")
    void sendNotificationToAdminsWithDisplayNameDelegatesToBoth() {
        composite.sendNotificationToAdmins("Alice", "警告", "風控觸發", NotificationService.COLOR_RED);

        verify(discordService).sendNotificationToAdmins("Alice", "警告", "風控觸發", NotificationService.COLOR_RED);
        verify(lineService).sendNotificationToAdmins("Alice", "警告", "風控觸發", NotificationService.COLOR_RED);
    }

    @Test
    @DisplayName("evictUserCache 委派到兩個服務")
    void evictUserCacheDelegatesToBoth() {
        composite.evictUserCache("user-1");

        verify(discordService).evictUserCache("user-1");
        verify(lineService).evictUserCache("user-1");
    }

    @Test
    @DisplayName("evictAllCache 委派到兩個服務")
    void evictAllCacheDelegatesToBoth() {
        composite.evictAllCache();

        verify(discordService).evictAllCache();
        verify(lineService).evictAllCache();
    }
}
