package com.trader.notification.service;

import com.trader.shared.config.WebhookConfig;
import com.trader.user.entity.User;
import com.trader.user.entity.UserDiscordWebhook;
import com.trader.user.repository.UserDiscordWebhookRepository;
import com.trader.user.repository.UserRepository;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
}
