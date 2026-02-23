package com.trader.service;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.referral.repository.UserExchangeReferralLinkRepository;
import com.trader.shared.model.TradeRequest;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.BroadcastTradeService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BroadcastTradeService 廣播跟單測試
 *
 * 測試重點：用戶過濾、並行執行、成功/失敗計數、通知發送
 * 用同步 Executor 避免多線程測試不穩定
 */
class BroadcastTradeServiceTest {

    private UserRepository mockUserRepo;
    private BinanceFuturesService mockBinance;
    private DiscordWebhookService mockWebhook;
    private UserApiKeyService mockApiKey;
    private UserExchangeReferralLinkRepository mockReferralRepo;
    private ExecutorService executor;
    private BroadcastTradeService service;

    @BeforeEach
    void setUp() {
        mockUserRepo = mock(UserRepository.class);
        mockBinance = mock(BinanceFuturesService.class);
        mockWebhook = mock(DiscordWebhookService.class);
        mockApiKey = mock(UserApiKeyService.class);
        mockReferralRepo = mock(UserExchangeReferralLinkRepository.class);

        // 預設：所有用戶都已驗證推薦碼（既有測試不受影響）
        when(mockReferralRepo.findVerifiedUserIds("BINANCE"))
                .thenReturn(List.of("u1", "u2", "u3", "u4", "u5"));

        // 預設：所有用戶都有 API Key（既有測試不受影響）
        when(mockApiKey.getUserIdsWithApiKey("BINANCE"))
                .thenReturn(Set.of("u1", "u2", "u3", "u4", "u5"));

        // 用 2 線程的 pool — 小到可預測，又能測並行
        executor = Executors.newFixedThreadPool(2);

        service = new BroadcastTradeService(
                mockUserRepo, mockBinance, mockWebhook, mockApiKey, mockReferralRepo, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // ==================== Helper ====================

    private User createUser(String id, boolean autoTrade, boolean enabled) {
        return User.builder()
                .userId(id)
                .email(id + "@test.com")
                .passwordHash("hash")
                .autoTradeEnabled(autoTrade)
                .enabled(enabled)
                .build();
    }

    private TradeRequest createEntryRequest() {
        TradeRequest request = new TradeRequest();
        request.setAction("ENTRY");
        request.setSymbol("BTCUSDT");
        request.setSide("LONG");
        request.setEntryPrice(95000.0);
        request.setStopLoss(93000.0);
        return request;
    }

    // ==================== User Filtering ====================

    @Nested
    @DisplayName("用戶過濾")
    class UserFiltering {

        @Test
        @DisplayName("3 個啟用用戶 — 全部執行")
        void allUsersEnabled() {
            List<User> users = List.of(
                    createUser("u1", true, true),
                    createUser("u2", true, true),
                    createUser("u3", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);
            // 使用 setUp 預設的 getUserIdsWithApiKey mock（全部有 API Key）

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(3);
        }

        @Test
        @DisplayName("過濾 autoTradeEnabled=false 的用戶")
        void filterDisabledAutoTrade() {
            List<User> users = List.of(
                    createUser("u1", true, true),
                    createUser("u2", false, true),  // 關閉自動跟單
                    createUser("u3", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);
            // 使用 setUp 預設的 getUserIdsWithApiKey mock

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("totalUsers")).isEqualTo(2);
            // u2 不應該被呼叫
            verify(mockBinance, never()).executeSignalForBroadcast(any(), eq("u2"));
        }

        @Test
        @DisplayName("過濾 enabled=false 的用戶")
        void filterDisabledUser() {
            List<User> users = List.of(
                    createUser("u1", true, true),
                    createUser("u2", true, false));  // 帳戶停用
            when(mockUserRepo.findAll()).thenReturn(users);
            // 使用 setUp 預設的 getUserIdsWithApiKey mock

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("totalUsers")).isEqualTo(1);
        }

        @Test
        @DisplayName("過濾沒有 API Key 的用戶 — 計入 skippedNoApiKey")
        void filterUsersWithoutApiKey() {
            List<User> users = List.of(
                    createUser("u1", true, true),
                    createUser("u2", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);
            // 只有 u1 有 API Key，u2 沒有
            when(mockApiKey.getUserIdsWithApiKey("BINANCE"))
                    .thenReturn(Set.of("u1"));

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("totalUsers")).isEqualTo(1);
            assertThat(result.get("skippedNoApiKey")).isEqualTo(1);
        }

        @Test
        @DisplayName("全部用戶都沒有 API Key → 返回 message")
        void allUsersNoApiKey() {
            List<User> users = List.of(
                    createUser("u1", true, true),
                    createUser("u2", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);
            // 沒有任何用戶有 API Key
            when(mockApiKey.getUserIdsWithApiKey("BINANCE"))
                    .thenReturn(Set.of());

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(0);
            assertThat(result.get("skippedNoApiKey")).isEqualTo(2);
            assertThat(result.get("message")).isNotNull();
        }

        @Test
        @DisplayName("無啟用用戶 → 返回空結果")
        void noEnabledUsers() {
            when(mockUserRepo.findAll()).thenReturn(List.of());

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(0);
        }
    }

    // ==================== Execution ====================

    @Nested
    @DisplayName("執行邏輯")
    class Execution {

        @Test
        @DisplayName("每個用戶獨立執行 — verify per-user 呼叫")
        void eachUserGetsOwnExecution() {
            List<User> users = List.of(
                    createUser("u1", true, true),
                    createUser("u2", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);
            // 使用 setUp 預設的 getUserIdsWithApiKey mock

            TradeRequest request = createEntryRequest();
            service.broadcastTrade(request);

            verify(mockBinance, timeout(5000)).executeSignalForBroadcast(eq(request), eq("u1"));
            verify(mockBinance, timeout(5000)).executeSignalForBroadcast(eq(request), eq("u2"));
        }

        @Test
        @DisplayName("單一用戶失敗不影響其他人")
        void failureDoesNotAffectOthers() {
            List<User> users = List.of(
                    createUser("u1", true, true),
                    createUser("u2", true, true),
                    createUser("u3", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);
            // 使用 setUp 預設的 getUserIdsWithApiKey mock

            // u2 會拋異常
            doNothing().when(mockBinance).executeSignalForBroadcast(any(), eq("u1"));
            doThrow(new RuntimeException("API key invalid")).when(mockBinance)
                    .executeSignalForBroadcast(any(), eq("u2"));
            doNothing().when(mockBinance).executeSignalForBroadcast(any(), eq("u3"));

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat((int) result.get("successCount")).isEqualTo(2);
            assertThat((int) result.get("failCount")).isEqualTo(1);
        }
    }

    // ==================== Notifications ====================

    @Nested
    @DisplayName("通知發送")
    class Notifications {

        @Test
        @DisplayName("成功 → 發送綠色通知給用戶")
        void successNotification() {
            List<User> users = List.of(createUser("u1", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);
            // 使用 setUp 預設的 getUserIdsWithApiKey mock

            service.broadcastTrade(createEntryRequest());

            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("u1"),
                    contains("已執行"),
                    anyString(),
                    eq(DiscordWebhookService.COLOR_GREEN));
        }

        @Test
        @DisplayName("失敗 → 發送紅色通知給用戶")
        void failureNotification() {
            List<User> users = List.of(createUser("u1", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);
            // 使用 setUp 預設的 getUserIdsWithApiKey mock

            doThrow(new RuntimeException("Insufficient margin")).when(mockBinance)
                    .executeSignalForBroadcast(any(), eq("u1"));

            service.broadcastTrade(createEntryRequest());

            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("u1"),
                    contains("失敗"),
                    contains("Insufficient margin"),
                    eq(DiscordWebhookService.COLOR_RED));
        }
    }
}
