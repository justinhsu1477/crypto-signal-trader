package com.trader.notification.consumer;

import com.trader.notification.model.AnnouncementMessage;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.LineNotificationService;
import com.trader.user.repository.UserDiscordWebhookRepository;
import com.trader.user.repository.UserLineBindingRepository;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * AnnouncementConsumer 單元測試
 *
 * 覆蓋：channel 過濾邏輯、Discord per-user 推送、LINE 逐用戶推送、錯誤處理
 */
class AnnouncementConsumerTest {

    private DiscordWebhookService discordService;
    private LineNotificationService lineService;
    private UserDiscordWebhookRepository discordWebhookRepository;
    private UserLineBindingRepository lineBindingRepository;
    private AnnouncementConsumer consumer;

    @BeforeEach
    void setUp() {
        discordService = mock(DiscordWebhookService.class);
        lineService = mock(LineNotificationService.class);
        discordWebhookRepository = mock(UserDiscordWebhookRepository.class);
        lineBindingRepository = mock(UserLineBindingRepository.class);

        consumer = new AnnouncementConsumer(discordService, lineService, discordWebhookRepository, lineBindingRepository);
    }

    private AnnouncementMessage buildMessage(String channels, String category, String priority) {
        return AnnouncementMessage.builder()
                .announcementId(1L)
                .title("測試公告")
                .content("測試內容")
                .category(category)
                .priority(priority)
                .channels(channels)
                .publishedAt(LocalDateTime.now())
                .createdBy("admin-1")
                .build();
    }

    private AnnouncementMessage buildMessageWithImage(String channels, String category, String priority, String imageUrl) {
        return AnnouncementMessage.builder()
                .announcementId(1L)
                .title("測試公告")
                .content("測試內容")
                .category(category)
                .priority(priority)
                .channels(channels)
                .imageUrl(imageUrl)
                .publishedAt(LocalDateTime.now())
                .createdBy("admin-1")
                .build();
    }

    // ==================== shouldSendTo ====================

    @Nested
    @DisplayName("shouldSendTo — channel 過濾邏輯")
    class ShouldSendToTests {

        @Test
        @DisplayName("ALL — 發送到所有頻道")
        void allChannelsSendsToAll() {
            assertThat(consumer.shouldSendTo("ALL", "DISCORD")).isTrue();
            assertThat(consumer.shouldSendTo("ALL", "LINE")).isTrue();
        }

        @Test
        @DisplayName("指定 DISCORD — 只發 DISCORD")
        void specificDiscordOnly() {
            assertThat(consumer.shouldSendTo("DISCORD", "DISCORD")).isTrue();
            assertThat(consumer.shouldSendTo("DISCORD", "LINE")).isFalse();
        }

        @Test
        @DisplayName("DISCORD,LINE — 發 DISCORD 和 LINE")
        void multipleChannels() {
            assertThat(consumer.shouldSendTo("DISCORD,LINE", "DISCORD")).isTrue();
            assertThat(consumer.shouldSendTo("DISCORD,LINE", "LINE")).isTrue();
        }

        @Test
        @DisplayName("WEBSOCKET — 不發 DISCORD 和 LINE")
        void websocketOnlySkipsBoth() {
            assertThat(consumer.shouldSendTo("WEBSOCKET", "DISCORD")).isFalse();
            assertThat(consumer.shouldSendTo("WEBSOCKET", "LINE")).isFalse();
        }

        @Test
        @DisplayName("null/空字串 — 預設發送")
        void nullOrEmptyDefaultsToSend() {
            assertThat(consumer.shouldSendTo(null, "DISCORD")).isTrue();
            assertThat(consumer.shouldSendTo("", "LINE")).isTrue();
        }

        @Test
        @DisplayName("大小寫不敏感")
        void caseInsensitive() {
            assertThat(consumer.shouldSendTo("all", "DISCORD")).isTrue();
            assertThat(consumer.shouldSendTo("discord", "DISCORD")).isTrue();
        }
    }

    // ==================== consumeDiscord ====================

    @Nested
    @DisplayName("consumeDiscord — Discord per-user 推送")
    class ConsumeDiscordTests {

        @Test
        @DisplayName("channels=ALL + 2 個 webhook 用戶 — 各自收到通知")
        void sendsToAllWebhookUsers() {
            when(discordWebhookRepository.findUserIdsWithEnabledWebhook())
                    .thenReturn(List.of("user-1", "user-2"));

            AnnouncementMessage msg = buildMessage("ALL", "GENERAL", "NORMAL");

            consumer.consumeDiscord(msg);

            verify(discordService, times(2)).sendAnnouncementToUser(
                    anyString(), contains("測試公告"), eq("測試內容"), anyInt(), isNull());
            verify(discordService).sendAnnouncementToUser(eq("user-1"), anyString(), anyString(), anyInt(), isNull());
            verify(discordService).sendAnnouncementToUser(eq("user-2"), anyString(), anyString(), anyInt(), isNull());
        }

        @Test
        @DisplayName("channels=DISCORD — 發送 Discord 通知（per-user）")
        void sendsWhenDiscordChannel() {
            when(discordWebhookRepository.findUserIdsWithEnabledWebhook())
                    .thenReturn(List.of("user-1"));

            AnnouncementMessage msg = buildMessage("DISCORD", "MAINTENANCE", "HIGH");

            consumer.consumeDiscord(msg);

            verify(discordService).sendAnnouncementToUser(eq("user-1"), contains("系統維護"), eq("測試內容"), anyInt(), isNull());
        }

        @Test
        @DisplayName("channels=LINE — 跳過 Discord")
        void skipsWhenLineOnly() {
            AnnouncementMessage msg = buildMessage("LINE", "GENERAL", "NORMAL");

            consumer.consumeDiscord(msg);

            verify(discordWebhookRepository, never()).findUserIdsWithEnabledWebhook();
            verify(discordService, never()).sendAnnouncementToUser(anyString(), anyString(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("無 webhook 用戶 — 不發送任何 Discord 通知")
        void noWebhookUsersNoSend() {
            when(discordWebhookRepository.findUserIdsWithEnabledWebhook()).thenReturn(List.of());

            AnnouncementMessage msg = buildMessage("ALL", "GENERAL", "NORMAL");

            consumer.consumeDiscord(msg);

            verify(discordService, never()).sendAnnouncementToUser(anyString(), anyString(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("URGENT 分類 — 標題含🚨")
        void urgentCategoryHasEmoji() {
            when(discordWebhookRepository.findUserIdsWithEnabledWebhook())
                    .thenReturn(List.of("user-1"));

            AnnouncementMessage msg = buildMessage("ALL", "URGENT", "CRITICAL");

            consumer.consumeDiscord(msg);

            verify(discordService).sendAnnouncementToUser(eq("user-1"), contains("🚨"), anyString(), anyInt(), isNull());
        }

        @Test
        @DisplayName("含 imageUrl — 傳遞到 sendAnnouncementToUser")
        void passesImageUrlToService() {
            when(discordWebhookRepository.findUserIdsWithEnabledWebhook())
                    .thenReturn(List.of("user-1"));

            AnnouncementMessage msg = buildMessageWithImage("ALL", "GENERAL", "NORMAL", "https://example.com/img.png");

            consumer.consumeDiscord(msg);

            verify(discordService).sendAnnouncementToUser(
                    eq("user-1"), anyString(), anyString(), anyInt(), eq("https://example.com/img.png"));
        }

        @Test
        @DisplayName("單個用戶推送失敗 — 不影響其他用戶")
        void singleUserFailureDoesNotBlockOthers() {
            when(discordWebhookRepository.findUserIdsWithEnabledWebhook())
                    .thenReturn(List.of("user-1", "user-2", "user-3"));
            doThrow(new RuntimeException("Discord API error"))
                    .when(discordService).sendAnnouncementToUser(eq("user-2"), anyString(), anyString(), anyInt(), any());

            AnnouncementMessage msg = buildMessage("ALL", "GENERAL", "NORMAL");

            assertThatCode(() -> consumer.consumeDiscord(msg)).doesNotThrowAnyException();

            verify(discordService).sendAnnouncementToUser(eq("user-1"), anyString(), anyString(), anyInt(), isNull());
            verify(discordService).sendAnnouncementToUser(eq("user-3"), anyString(), anyString(), anyInt(), isNull());
        }
    }

    // ==================== consumeLine ====================

    @Nested
    @DisplayName("consumeLine — LINE 推送")
    class ConsumeLineTests {

        @Test
        @DisplayName("channels=ALL + 2 個綁定用戶 — 各自收到通知")
        void sendsToAllBoundUsers() {
            when(lineBindingRepository.findUserIdsWithEnabledBinding())
                    .thenReturn(List.of("user-1", "user-2"));

            AnnouncementMessage msg = buildMessage("ALL", "GENERAL", "NORMAL");

            consumer.consumeLine(msg);

            verify(lineService, times(2)).sendAnnouncementToUser(
                    anyString(), contains("測試公告"), eq("測試內容"), anyInt(), isNull());
            verify(lineService).sendAnnouncementToUser(eq("user-1"), anyString(), anyString(), anyInt(), isNull());
            verify(lineService).sendAnnouncementToUser(eq("user-2"), anyString(), anyString(), anyInt(), isNull());
        }

        @Test
        @DisplayName("channels=DISCORD — 跳過 LINE")
        void skipsWhenDiscordOnly() {
            AnnouncementMessage msg = buildMessage("DISCORD", "GENERAL", "NORMAL");

            consumer.consumeLine(msg);

            verify(lineBindingRepository, never()).findUserIdsWithEnabledBinding();
            verify(lineService, never()).sendAnnouncementToUser(anyString(), anyString(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("無綁定用戶 — 不發送任何 LINE 通知")
        void noBoundUsersNoSend() {
            when(lineBindingRepository.findUserIdsWithEnabledBinding()).thenReturn(List.of());

            AnnouncementMessage msg = buildMessage("ALL", "GENERAL", "NORMAL");

            consumer.consumeLine(msg);

            verify(lineService, never()).sendAnnouncementToUser(anyString(), anyString(), anyString(), anyInt(), any());
        }

        @Test
        @DisplayName("含 imageUrl — 傳遞到 sendAnnouncementToUser")
        void passesImageUrlToLineService() {
            when(lineBindingRepository.findUserIdsWithEnabledBinding())
                    .thenReturn(List.of("user-1"));

            AnnouncementMessage msg = buildMessageWithImage("ALL", "GENERAL", "NORMAL", "https://example.com/banner.jpg");

            consumer.consumeLine(msg);

            verify(lineService).sendAnnouncementToUser(
                    eq("user-1"), anyString(), anyString(), anyInt(), eq("https://example.com/banner.jpg"));
        }

        @Test
        @DisplayName("單個用戶推送失敗 — 不影響其他用戶")
        void singleUserFailureDoesNotBlockOthers() {
            when(lineBindingRepository.findUserIdsWithEnabledBinding())
                    .thenReturn(List.of("user-1", "user-2", "user-3"));
            doThrow(new RuntimeException("LINE API error"))
                    .when(lineService).sendAnnouncementToUser(eq("user-2"), anyString(), anyString(), anyInt(), any());

            AnnouncementMessage msg = buildMessage("ALL", "GENERAL", "NORMAL");

            // 不應拋異常
            assertThatCode(() -> consumer.consumeLine(msg)).doesNotThrowAnyException();

            // user-1 和 user-3 仍收到通知
            verify(lineService).sendAnnouncementToUser(eq("user-1"), anyString(), anyString(), anyInt(), isNull());
            verify(lineService).sendAnnouncementToUser(eq("user-3"), anyString(), anyString(), anyInt(), isNull());
        }
    }
}
