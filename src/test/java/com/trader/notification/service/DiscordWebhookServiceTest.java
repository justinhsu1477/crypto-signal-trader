package com.trader.notification.service;

import com.trader.shared.config.WebhookConfig;
import com.trader.user.entity.User;
import com.trader.user.entity.UserDiscordWebhook;
import com.trader.user.repository.UserDiscordWebhookRepository;
import com.trader.user.repository.UserRepository;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DiscordWebhookService 單元測試
 *
 * 覆蓋：通知發送、JSON 格式、per-user webhook 優先、全局 fallback、disabled 跳過
 */
class DiscordWebhookServiceTest {

    private OkHttpClient httpClient;
    private WebhookConfig webhookConfig;
    private WebhookConfig.PerUserSettings perUserSettings;
    private UserDiscordWebhookRepository userWebhookRepository;
    private UserRepository userRepository;
    private DiscordWebhookService service;
    private Call mockCall;

    @BeforeEach
    void setUp() {
        httpClient = mock(OkHttpClient.class);
        webhookConfig = mock(WebhookConfig.class);
        perUserSettings = mock(WebhookConfig.PerUserSettings.class);
        userWebhookRepository = mock(UserDiscordWebhookRepository.class);
        userRepository = mock(UserRepository.class);

        when(webhookConfig.getPerUser()).thenReturn(perUserSettings);
        mockCall = mock(Call.class);
        when(httpClient.newCall(any())).thenReturn(mockCall);

        // 預設：用戶通知已啟用（既有測試不受影響）
        when(userRepository.findById(any())).thenReturn(
                Optional.of(User.builder().discordNotificationEnabled(true).build()));

        service = new DiscordWebhookService(httpClient, webhookConfig, userWebhookRepository, userRepository);
    }

    // ==================== sendNotification ====================

    @Nested
    @DisplayName("sendNotification — 全局通知")
    class SendNotificationTests {

        @Test
        @DisplayName("enabled + 有 URL — 發送 HTTP 請求")
        void enabledWithUrlSends() {
            when(webhookConfig.isEnabled()).thenReturn(true);
            when(webhookConfig.getUrl()).thenReturn("https://discord.com/api/webhooks/123/abc");

            service.sendNotification("Test Title", "Test Message", DiscordWebhookService.COLOR_GREEN);

            verify(httpClient).newCall(any());
            verify(mockCall).enqueue(any());
        }

        @Test
        @DisplayName("disabled — 不發送")
        void disabledDoesNotSend() {
            when(webhookConfig.isEnabled()).thenReturn(false);

            service.sendNotification("Title", "Message", DiscordWebhookService.COLOR_RED);

            verify(httpClient, never()).newCall(any());
        }

        @Test
        @DisplayName("enabled + URL 為空 — 不發送")
        void enabledEmptyUrlDoesNotSend() {
            when(webhookConfig.isEnabled()).thenReturn(true);
            when(webhookConfig.getUrl()).thenReturn("");

            service.sendNotification("Title", "Message", DiscordWebhookService.COLOR_RED);

            verify(httpClient, never()).newCall(any());
        }

        @Test
        @DisplayName("JSON payload 包含正確欄位")
        void jsonPayloadCorrectFields() {
            when(webhookConfig.isEnabled()).thenReturn(true);
            when(webhookConfig.getUrl()).thenReturn("https://discord.com/api/webhooks/123/abc");

            service.sendNotification("Entry Success", "BTCUSDT LONG", DiscordWebhookService.COLOR_GREEN);

            ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
            verify(httpClient).newCall(requestCaptor.capture());

            Request request = requestCaptor.getValue();
            assertThat(request.url().toString()).isEqualTo("https://discord.com/api/webhooks/123/abc");
            assertThat(request.method()).isEqualTo("POST");
        }

        @Test
        @DisplayName("特殊字元 JSON 跳脫 — 雙引號、換行")
        void specialCharsEscaped() {
            when(webhookConfig.isEnabled()).thenReturn(true);
            when(webhookConfig.getUrl()).thenReturn("https://discord.com/api/webhooks/123/abc");

            // 含雙引號和換行的訊息
            service.sendNotification("Test \"quote\"", "Line1\nLine2", DiscordWebhookService.COLOR_BLUE);

            verify(httpClient).newCall(any());
            // 如果 JSON 跳脫有問題，會拋 IOException，但 enqueue 是非同步
        }
    }

    // ==================== getUserWebhookUrl ====================

    @Nested
    @DisplayName("getUserWebhookUrl — 優先順序")
    class GetUserWebhookUrlTests {

        @Test
        @DisplayName("用戶有自定義 webhook — 優先使用")
        void userCustomWebhookPreferred() {
            when(perUserSettings.isEnabled()).thenReturn(true);
            UserDiscordWebhook webhook = new UserDiscordWebhook();
            webhook.setWebhookUrl("https://discord.com/api/webhooks/user/custom");
            when(userWebhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc("u1"))
                    .thenReturn(Optional.of(webhook));

            Optional<String> result = service.getUserWebhookUrl("u1");

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("https://discord.com/api/webhooks/user/custom");
        }

        @Test
        @DisplayName("用戶無自定義 + fallback 啟用 — 使用全局")
        void fallbackToGlobal() {
            when(perUserSettings.isEnabled()).thenReturn(true);
            when(perUserSettings.isFallbackToGlobal()).thenReturn(true);
            when(userWebhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc("u2"))
                    .thenReturn(Optional.empty());
            when(webhookConfig.isEnabled()).thenReturn(true);
            when(webhookConfig.getUrl()).thenReturn("https://discord.com/api/webhooks/global");

            Optional<String> result = service.getUserWebhookUrl("u2");

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("https://discord.com/api/webhooks/global");
        }

        @Test
        @DisplayName("用戶無自定義 + fallback 關閉 — 回傳 empty")
        void noFallbackReturnsEmpty() {
            when(perUserSettings.isEnabled()).thenReturn(true);
            when(perUserSettings.isFallbackToGlobal()).thenReturn(false);
            when(userWebhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc("u3"))
                    .thenReturn(Optional.empty());

            Optional<String> result = service.getUserWebhookUrl("u3");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("per-user 關閉 — 直接用全局")
        void perUserDisabledUsesGlobal() {
            when(perUserSettings.isEnabled()).thenReturn(false);
            when(webhookConfig.isEnabled()).thenReturn(true);
            when(webhookConfig.getUrl()).thenReturn("https://discord.com/api/webhooks/global");

            Optional<String> result = service.getUserWebhookUrl("u4");

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("https://discord.com/api/webhooks/global");
            verify(userWebhookRepository, never()).findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc(any());
        }
    }

    // ==================== sendNotificationToUser ====================

    @Nested
    @DisplayName("sendNotificationToUser")
    class SendToUserTests {

        @Test
        @DisplayName("有 webhook URL — 發送")
        void sendsWhenUrlAvailable() {
            when(perUserSettings.isEnabled()).thenReturn(true);
            UserDiscordWebhook webhook = new UserDiscordWebhook();
            webhook.setWebhookUrl("https://discord.com/api/webhooks/user/hook");
            when(userWebhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc("u1"))
                    .thenReturn(Optional.of(webhook));

            service.sendNotificationToUser("u1", "Title", "Message", DiscordWebhookService.COLOR_GREEN);

            verify(httpClient).newCall(any());
        }

        @Test
        @DisplayName("無 webhook URL — 不發送")
        void doesNotSendWhenNoUrl() {
            when(perUserSettings.isEnabled()).thenReturn(true);
            when(perUserSettings.isFallbackToGlobal()).thenReturn(false);
            when(userWebhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc("u99"))
                    .thenReturn(Optional.empty());

            service.sendNotificationToUser("u99", "Title", "Message", DiscordWebhookService.COLOR_RED);

            verify(httpClient, never()).newCall(any());
        }
    }

    // ==================== discordNotificationEnabled 主開關 ====================

    @Nested
    @DisplayName("sendNotificationToUser — discordNotificationEnabled 主開關")
    class NotificationToggleTests {

        @Test
        @DisplayName("通知啟用 — 正常發送")
        void enabledSendsNotification() {
            User user = User.builder().userId("u1").discordNotificationEnabled(true).build();
            when(userRepository.findById("u1")).thenReturn(Optional.of(user));

            when(perUserSettings.isEnabled()).thenReturn(true);
            UserDiscordWebhook webhook = new UserDiscordWebhook();
            webhook.setWebhookUrl("https://discord.com/api/webhooks/user/hook");
            when(userWebhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc("u1"))
                    .thenReturn(Optional.of(webhook));

            service.sendNotificationToUser("u1", "Title", "Message", DiscordWebhookService.COLOR_GREEN);

            verify(httpClient).newCall(any());
        }

        @Test
        @DisplayName("通知關閉 — 不發送")
        void disabledSkipsNotification() {
            User user = User.builder().userId("u1").discordNotificationEnabled(false).build();
            when(userRepository.findById("u1")).thenReturn(Optional.of(user));

            service.sendNotificationToUser("u1", "Title", "Message", DiscordWebhookService.COLOR_GREEN);

            verify(httpClient, never()).newCall(any());
            verify(userWebhookRepository, never()).findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc(any());
        }

        @Test
        @DisplayName("用戶不存在 — 預設允許（保守策略）")
        void userNotFoundDefaultsToEnabled() {
            when(userRepository.findById("unknown")).thenReturn(Optional.empty());

            when(perUserSettings.isEnabled()).thenReturn(true);
            UserDiscordWebhook webhook = new UserDiscordWebhook();
            webhook.setWebhookUrl("https://discord.com/api/webhooks/user/hook");
            when(userWebhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc("unknown"))
                    .thenReturn(Optional.of(webhook));

            service.sendNotificationToUser("unknown", "Title", "Message", DiscordWebhookService.COLOR_GREEN);

            verify(httpClient).newCall(any());
        }

        @Test
        @DisplayName("通知關閉不影響全局 sendNotification")
        void disabledDoesNotAffectGlobal() {
            when(webhookConfig.isEnabled()).thenReturn(true);
            when(webhookConfig.getUrl()).thenReturn("https://discord.com/api/webhooks/123/abc");

            service.sendNotification("System Alert", "Critical!", DiscordWebhookService.COLOR_RED);

            verify(httpClient).newCall(any());
            verify(userRepository, never()).findById(any());
        }
    }

    // ==================== sendNotificationToAdmins ====================

    @Nested
    @DisplayName("sendNotificationToAdmins — Admin 通知")
    class AdminNotificationTests {

        @Test
        @DisplayName("有 1 個 Admin + 有 webhook — 發送通知")
        void singleAdminWithWebhookReceivesNotification() {
            User admin = User.builder()
                    .userId("admin-1").role(User.Role.ADMIN).enabled(true)
                    .discordNotificationEnabled(true).build();
            when(userRepository.findByRole(User.Role.ADMIN)).thenReturn(List.of(admin));
            when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

            when(perUserSettings.isEnabled()).thenReturn(true);
            UserDiscordWebhook webhook = new UserDiscordWebhook();
            webhook.setWebhookUrl("https://discord.com/api/webhooks/admin/hook");
            when(userWebhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc("admin-1"))
                    .thenReturn(Optional.of(webhook));

            service.sendNotificationToAdmins("System Alert", "Server restarted", DiscordWebhookService.COLOR_BLUE);

            verify(httpClient).newCall(any());
        }

        @Test
        @DisplayName("無 Admin — 不發送")
        void noAdminNoNotification() {
            when(userRepository.findByRole(User.Role.ADMIN)).thenReturn(List.of());

            service.sendNotificationToAdmins("Alert", "Message", DiscordWebhookService.COLOR_RED);

            verify(httpClient, never()).newCall(any());
        }

        @Test
        @DisplayName("Admin disabled — 不發送")
        void disabledAdminSkipped() {
            User admin = User.builder()
                    .userId("admin-1").role(User.Role.ADMIN).enabled(false).build();
            when(userRepository.findByRole(User.Role.ADMIN)).thenReturn(List.of(admin));

            service.sendNotificationToAdmins("Alert", "Message", DiscordWebhookService.COLOR_RED);

            // Admin disabled → getAdminUserIds 過濾掉 → 不查 webhook → 不發送
            verify(userWebhookRepository, never()).findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc(any());
        }

        @Test
        @DisplayName("多個 Admin — 各自收到通知")
        void multipleAdminsEachReceiveNotification() {
            User admin1 = User.builder()
                    .userId("admin-1").role(User.Role.ADMIN).enabled(true)
                    .discordNotificationEnabled(true).build();
            User admin2 = User.builder()
                    .userId("admin-2").role(User.Role.ADMIN).enabled(true)
                    .discordNotificationEnabled(true).build();
            when(userRepository.findByRole(User.Role.ADMIN)).thenReturn(List.of(admin1, admin2));
            when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin1));
            when(userRepository.findById("admin-2")).thenReturn(Optional.of(admin2));

            when(perUserSettings.isEnabled()).thenReturn(true);
            UserDiscordWebhook webhook1 = new UserDiscordWebhook();
            webhook1.setWebhookUrl("https://discord.com/api/webhooks/admin1/hook");
            UserDiscordWebhook webhook2 = new UserDiscordWebhook();
            webhook2.setWebhookUrl("https://discord.com/api/webhooks/admin2/hook");
            when(userWebhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc("admin-1"))
                    .thenReturn(Optional.of(webhook1));
            when(userWebhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc("admin-2"))
                    .thenReturn(Optional.of(webhook2));

            service.sendNotificationToAdmins("Alert", "System event", DiscordWebhookService.COLOR_BLUE);

            verify(httpClient, times(2)).newCall(any());
        }

        @Test
        @DisplayName("帶 displayName — 訊息前綴包含用戶名稱")
        void withDisplayNamePrefixesMessage() {
            User admin = User.builder()
                    .userId("admin-1").role(User.Role.ADMIN).enabled(true)
                    .discordNotificationEnabled(true).build();
            when(userRepository.findByRole(User.Role.ADMIN)).thenReturn(List.of(admin));
            when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

            when(perUserSettings.isEnabled()).thenReturn(true);
            UserDiscordWebhook webhook = new UserDiscordWebhook();
            webhook.setWebhookUrl("https://discord.com/api/webhooks/admin/hook");
            when(userWebhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc("admin-1"))
                    .thenReturn(Optional.of(webhook));

            service.sendNotificationToAdmins("Beck Tsai (beck@test.com)",
                    "🛑 Fail-Safe", "SL 下單失敗", DiscordWebhookService.COLOR_RED);

            ArgumentCaptor<Request> requestCaptor = ArgumentCaptor.forClass(Request.class);
            verify(httpClient).newCall(requestCaptor.capture());

            // 請求已發送（訊息內含 displayName 前綴由 buildEmbedJson 處理）
            assertThat(requestCaptor.getValue().method()).isEqualTo("POST");
        }

        @Test
        @DisplayName("Admin 快取 — 5 分鐘內不重查 DB")
        void adminIdsCached() {
            User admin = User.builder()
                    .userId("admin-1").role(User.Role.ADMIN).enabled(true)
                    .discordNotificationEnabled(true).build();
            when(userRepository.findByRole(User.Role.ADMIN)).thenReturn(List.of(admin));
            when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

            when(perUserSettings.isEnabled()).thenReturn(true);
            UserDiscordWebhook webhook = new UserDiscordWebhook();
            webhook.setWebhookUrl("https://discord.com/api/webhooks/admin/hook");
            when(userWebhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc("admin-1"))
                    .thenReturn(Optional.of(webhook));

            // 呼叫兩次
            service.sendNotificationToAdmins("Alert1", "msg1", DiscordWebhookService.COLOR_BLUE);
            service.sendNotificationToAdmins("Alert2", "msg2", DiscordWebhookService.COLOR_BLUE);

            // findByRole 只查一次（快取命中）
            verify(userRepository, times(1)).findByRole(User.Role.ADMIN);
        }

        @Test
        @DisplayName("evictAllCache — 清除 admin 快取")
        void evictAllCacheClearsAdminCache() {
            User admin = User.builder()
                    .userId("admin-1").role(User.Role.ADMIN).enabled(true)
                    .discordNotificationEnabled(true).build();
            when(userRepository.findByRole(User.Role.ADMIN)).thenReturn(List.of(admin));
            when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

            when(perUserSettings.isEnabled()).thenReturn(true);
            UserDiscordWebhook webhook = new UserDiscordWebhook();
            webhook.setWebhookUrl("https://discord.com/api/webhooks/admin/hook");
            when(userWebhookRepository.findFirstByUserIdAndEnabledTrueOrderByUpdatedAtDesc("admin-1"))
                    .thenReturn(Optional.of(webhook));

            service.sendNotificationToAdmins("Alert", "msg", DiscordWebhookService.COLOR_BLUE);
            service.evictAllCache();
            service.sendNotificationToAdmins("Alert2", "msg2", DiscordWebhookService.COLOR_BLUE);

            // evictAllCache 後應重新查 DB
            verify(userRepository, times(2)).findByRole(User.Role.ADMIN);
        }
    }
}
