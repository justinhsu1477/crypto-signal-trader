package com.trader.notification.service;

import com.trader.shared.config.LineConfig;
import com.trader.user.entity.User;
import com.trader.user.entity.UserLineBinding;
import com.trader.user.repository.UserLineBindingRepository;
import com.trader.user.repository.UserRepository;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * LineNotificationService 單元測試
 *
 * 覆蓋：LINE 推播、enabled/disabled、無綁定跳過、快取管理、Admin 通知
 */
class LineNotificationServiceTest {

    private OkHttpClient httpClient;
    private LineConfig lineConfig;
    private UserLineBindingRepository lineBindingRepository;
    private UserRepository userRepository;
    private LineNotificationService service;
    private Call mockCall;

    private static final String USER_ID = "test-user-123";
    private static final String LINE_USER_ID = "U1234567890abcdef";

    @BeforeEach
    void setUp() {
        httpClient = mock(OkHttpClient.class);
        lineConfig = mock(LineConfig.class);
        lineBindingRepository = mock(UserLineBindingRepository.class);
        userRepository = mock(UserRepository.class);

        mockCall = mock(Call.class);
        when(httpClient.newCall(any())).thenReturn(mockCall);

        when(lineConfig.isEnabled()).thenReturn(true);
        when(lineConfig.getChannelAccessToken()).thenReturn("test-access-token");

        // 預設：用戶 LINE 通知已啟用
        when(userRepository.findById(USER_ID)).thenReturn(
                Optional.of(User.builder().lineNotificationEnabled(true).build()));

        service = new LineNotificationService(httpClient, lineConfig, lineBindingRepository, userRepository);
    }

    // ==================== sendNotificationToUser ====================

    @Nested
    @DisplayName("sendNotificationToUser — 用戶推播")
    class SendToUserTests {

        @Test
        @DisplayName("有綁定 + 啟用 → 發送 LINE Push")
        void enabledWithBindingSends() {
            when(lineBindingRepository.findByUserIdAndEnabledTrue(USER_ID))
                    .thenReturn(Optional.of(UserLineBinding.builder()
                            .userId(USER_ID).lineUserId(LINE_USER_ID).enabled(true).build()));

            service.sendNotificationToUser(USER_ID, "標題", "內容", NotificationService.COLOR_GREEN);

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(httpClient).newCall(captor.capture());
            verify(mockCall).enqueue(any());

            Request request = captor.getValue();
            assertThat(request.url().toString()).isEqualTo("https://api.line.me/v2/bot/message/push");
            assertThat(request.header("Authorization")).isEqualTo("Bearer test-access-token");
        }

        @Test
        @DisplayName("無綁定 → 不發送")
        void noBindingDoesNotSend() {
            when(lineBindingRepository.findByUserIdAndEnabledTrue(USER_ID))
                    .thenReturn(Optional.empty());

            service.sendNotificationToUser(USER_ID, "標題", "內容", NotificationService.COLOR_RED);

            verify(httpClient, never()).newCall(any());
        }

        @Test
        @DisplayName("用戶關閉 LINE 通知 → 不發送")
        void disabledUserDoesNotSend() {
            when(userRepository.findById(USER_ID)).thenReturn(
                    Optional.of(User.builder().lineNotificationEnabled(false).build()));
            // 需要建新 service 來清除快取
            service = new LineNotificationService(httpClient, lineConfig, lineBindingRepository, userRepository);

            service.sendNotificationToUser(USER_ID, "標題", "內容", NotificationService.COLOR_GREEN);

            verify(httpClient, never()).newCall(any());
        }

        @Test
        @DisplayName("LINE 功能關閉（enabled=false）→ 不發送")
        void lineDisabledDoesNotSend() {
            when(lineConfig.isEnabled()).thenReturn(false);

            service.sendNotificationToUser(USER_ID, "標題", "內容", NotificationService.COLOR_GREEN);

            verify(httpClient, never()).newCall(any());
        }
    }

    // ==================== sendNotification ====================

    @Nested
    @DisplayName("sendNotification — 全局通知（路由到 Admin）")
    class SendGlobalTests {

        @Test
        @DisplayName("LINE 啟用 + Admin 有綁定 → 發送到 Admin")
        void sendsToAdmins() {
            User admin = User.builder().userId("admin-1").role(User.Role.ADMIN)
                    .enabled(true).lineNotificationEnabled(true).build();
            when(userRepository.findByRole(User.Role.ADMIN)).thenReturn(List.of(admin));
            when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));
            when(lineBindingRepository.findByUserIdAndEnabledTrue("admin-1"))
                    .thenReturn(Optional.of(UserLineBinding.builder()
                            .userId("admin-1").lineUserId("Uadmin").enabled(true).build()));

            service.sendNotification("系統通知", "測試", NotificationService.COLOR_BLUE);

            verify(httpClient).newCall(any());
            verify(mockCall).enqueue(any());
        }

        @Test
        @DisplayName("LINE 關閉 → 不發送")
        void lineDisabledDoesNotSend() {
            when(lineConfig.isEnabled()).thenReturn(false);

            service.sendNotification("系統通知", "測試", NotificationService.COLOR_BLUE);

            verify(httpClient, never()).newCall(any());
        }
    }

    // ==================== 快取管理 ====================

    @Nested
    @DisplayName("快取管理")
    class CacheTests {

        @Test
        @DisplayName("evictUserCache 清除後重新查詢 DB")
        void evictUserCacheTriggersNewDbQuery() {
            when(lineBindingRepository.findByUserIdAndEnabledTrue(USER_ID))
                    .thenReturn(Optional.of(UserLineBinding.builder()
                            .userId(USER_ID).lineUserId(LINE_USER_ID).enabled(true).build()));

            // 第一次呼叫 → 查 DB
            service.sendNotificationToUser(USER_ID, "T1", "M1", NotificationService.COLOR_GREEN);
            // 第二次呼叫 → 用快取
            service.sendNotificationToUser(USER_ID, "T2", "M2", NotificationService.COLOR_GREEN);

            // bindingRepository 只被查了 1 次（第二次走快取）
            verify(lineBindingRepository, times(1)).findByUserIdAndEnabledTrue(USER_ID);

            // evict 後再呼叫 → 重查 DB
            service.evictUserCache(USER_ID);
            service.sendNotificationToUser(USER_ID, "T3", "M3", NotificationService.COLOR_GREEN);

            verify(lineBindingRepository, times(2)).findByUserIdAndEnabledTrue(USER_ID);
        }
    }
}
