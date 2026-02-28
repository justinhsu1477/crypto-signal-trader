package com.trader.service;

import com.trader.advisor.dto.SignalScore;
import com.trader.advisor.service.SignalScoringService;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeRequest;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.BroadcastTradeService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

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
    private SubscriptionRepository mockSubscriptionRepo;
    private SignalScoringService mockScoring;
    private TradeRepository mockTradeRepo;
    private ExecutorService executor;
    private BroadcastTradeService service;

    @BeforeEach
    void setUp() {
        mockUserRepo = mock(UserRepository.class);
        mockBinance = mock(BinanceFuturesService.class);
        mockWebhook = mock(DiscordWebhookService.class);
        mockApiKey = mock(UserApiKeyService.class);
        mockSubscriptionRepo = mock(SubscriptionRepository.class);
        mockScoring = mock(SignalScoringService.class);
        mockTradeRepo = mock(TradeRepository.class);

        // 預設：executeSignalForBroadcast 回傳空結果（既有測試不受影響）
        when(mockBinance.executeSignalForBroadcast(any(), anyString())).thenReturn(List.of());

        // 預設：所有用戶都有 API Key（既有測試不受影響）
        when(mockApiKey.getUserIdsWithApiKey("BINANCE"))
                .thenReturn(Set.of("u1", "u2", "u3", "u4", "u5"));

        // 預設：所有用戶都有有效訂閱（既有測試不受影響）
        when(mockSubscriptionRepo.findUserIdsWithActiveSubscription())
                .thenReturn(List.of("u1", "u2", "u3", "u4", "u5"));

        // 預設：AI 評分關閉（回傳 null，既有測試不受影響）
        when(mockScoring.scoreAsync(any())).thenReturn(CompletableFuture.completedFuture(null));

        // 用 2 線程的 pool — 小到可預測，又能測並行
        executor = Executors.newFixedThreadPool(2);

        service = new BroadcastTradeService(
                mockUserRepo, mockBinance, mockWebhook, mockApiKey, mockSubscriptionRepo,
                mockScoring, mockTradeRepo, executor);
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
        @DisplayName("過濾沒有訂閱的用戶 — 計入 skippedNoSubscription")
        void filterUsersWithoutSubscription() {
            List<User> users = List.of(
                    createUser("u1", true, true),
                    createUser("u2", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);
            // 只有 u1 有訂閱
            when(mockSubscriptionRepo.findUserIdsWithActiveSubscription())
                    .thenReturn(List.of("u1"));

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("totalUsers")).isEqualTo(1);
            assertThat(result.get("skippedNoSubscription")).isEqualTo(1);
            verify(mockBinance, never()).executeSignalForBroadcast(any(), eq("u2"));
        }

        @Test
        @DisplayName("全部用戶都沒有訂閱 → 返回 message")
        void allUsersNoSubscription() {
            List<User> users = List.of(
                    createUser("u1", true, true),
                    createUser("u2", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);
            when(mockSubscriptionRepo.findUserIdsWithActiveSubscription())
                    .thenReturn(List.of());

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(0);
            assertThat(result.get("skippedNoSubscription")).isEqualTo(2);
            assertThat(result.get("message")).isNotNull();
        }

        @Test
        @DisplayName("訂閱過濾在 API Key 過濾之前 — 無訂閱不計入 skippedNoApiKey")
        void subscriptionFilterBeforeApiKeyFilter() {
            List<User> users = List.of(
                    createUser("u1", true, true),
                    createUser("u2", true, true),
                    createUser("u3", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);
            // u1, u2 有訂閱; u3 無訂閱
            when(mockSubscriptionRepo.findUserIdsWithActiveSubscription())
                    .thenReturn(List.of("u1", "u2"));
            // 只有 u1 有 API Key（u2 無 API Key, u3 已被訂閱過濾）
            when(mockApiKey.getUserIdsWithApiKey("BINANCE"))
                    .thenReturn(Set.of("u1"));

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("totalUsers")).isEqualTo(1);
            assertThat(result.get("skippedNoSubscription")).isEqualTo(1);  // u3
            assertThat(result.get("skippedNoApiKey")).isEqualTo(1);         // u2
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
            doReturn(List.of()).when(mockBinance).executeSignalForBroadcast(any(), eq("u1"));
            doThrow(new RuntimeException("API key invalid")).when(mockBinance)
                    .executeSignalForBroadcast(any(), eq("u2"));
            doReturn(List.of()).when(mockBinance).executeSignalForBroadcast(any(), eq("u3"));

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

    // ==================== Fail Details ====================

    @Nested
    @DisplayName("失敗明細")
    class FailDetailTests {

        @Test
        @DisplayName("失敗時報告包含失敗原因")
        void summaryIncludesFailureDetails() {
            User admin = User.builder().userId("admin1").email("admin@test.com").passwordHash("h")
                    .enabled(true).role(User.Role.ADMIN).build();
            User user1 = createUser("u1", true, true);
            User user2 = createUser("u2", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin, user1, user2));

            doThrow(new RuntimeException("Insufficient margin"))
                    .when(mockBinance).executeSignalForBroadcast(any(), eq("u1"));
            doThrow(new RuntimeException("API key expired"))
                    .when(mockBinance).executeSignalForBroadcast(any(), eq("u2"));

            service.broadcastTrade(createEntryRequest());

            // 驗證 admin 收到的報告包含失敗明細
            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000).atLeastOnce()).sendNotificationToUser(
                    eq("admin1"),
                    eq("📊 廣播跟單報告"),
                    bodyCaptor.capture(),
                    anyInt());

            String summaryBody = bodyCaptor.getValue();
            assertThat(summaryBody).contains("失敗明細");
            assertThat(summaryBody).contains("Insufficient margin");
            assertThat(summaryBody).contains("API key expired");
        }
    }

    // ==================== Enriched Notifications ====================

    @Nested
    @DisplayName("Enriched 用戶通知（含成交明細）")
    class EnrichedNotifications {

        @Test
        @DisplayName("ENTRY 成功 → 用戶通知含成交價和數量")
        void entryNotificationContainsFillDetails() {
            List<User> users = List.of(createUser("u1", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);

            OrderResult entryResult = OrderResult.builder()
                    .success(true).orderId("123456").symbol("BTCUSDT")
                    .side("BUY").type("MARKET")
                    .price(94950.5).quantity(0.01).commission(0.47)
                    .build();
            when(mockBinance.executeSignalForBroadcast(any(), eq("u1")))
                    .thenReturn(List.of(entryResult));

            service.broadcastTrade(createEntryRequest());

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("u1"),
                    eq("✅ 廣播跟單已執行"),
                    bodyCaptor.capture(),
                    eq(DiscordWebhookService.COLOR_GREEN));

            String body = bodyCaptor.getValue();
            assertThat(body).contains("成交: 94950.5");
            assertThat(body).contains("數量: 0.01");
            assertThat(body).contains("手續費:");
        }

        @Test
        @DisplayName("ENTRY 無成交結果 → fallback 顯示請求價")
        void entryFallbackToRequestPrice() {
            List<User> users = List.of(createUser("u1", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);
            // 預設回傳空結果

            service.broadcastTrade(createEntryRequest());

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("u1"),
                    eq("✅ 廣播跟單已執行"),
                    bodyCaptor.capture(),
                    eq(DiscordWebhookService.COLOR_GREEN));

            assertThat(bodyCaptor.getValue()).contains("入場: 95000.0");
        }

        @Test
        @DisplayName("CLOSE 成功 → 用戶通知含 PnL 和手續費")
        void closeNotificationContainsPnl() {
            List<User> users = List.of(createUser("u1", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);

            OrderResult closeResult = OrderResult.builder()
                    .success(true).orderId("789012").symbol("BTCUSDT")
                    .side("SELL").type("MARKET")
                    .price(96500.0).quantity(0.01)
                    .netProfit(150.32).totalCommission(0.94)
                    .build();
            when(mockBinance.executeSignalForBroadcast(any(), eq("u1")))
                    .thenReturn(List.of(closeResult));

            TradeRequest closeRequest = new TradeRequest();
            closeRequest.setAction("CLOSE");
            closeRequest.setSymbol("BTCUSDT");

            service.broadcastTrade(closeRequest);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("u1"),
                    eq("✅ 廣播平倉已執行"),
                    bodyCaptor.capture(),
                    eq(DiscordWebhookService.COLOR_GREEN));

            String body = bodyCaptor.getValue();
            assertThat(body).contains("成交: 96500.0");
            assertThat(body).contains("+150.32");
            assertThat(body).contains("手續費:");
        }
    }

    // ==================== Admin Report ====================

    @Nested
    @DisplayName("Admin 彙總報告")
    class AdminReport {

        private User admin;

        @BeforeEach
        void setUpAdmin() {
            admin = User.builder().userId("admin1").email("admin@test.com").passwordHash("h")
                    .enabled(true).role(User.Role.ADMIN).build();
        }

        @Test
        @DisplayName("ENTRY 報告含成交明細")
        void entryReportContainsFillDetails() {
            User user1 = createUser("u1", true, true);
            User user2 = createUser("u2", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin, user1, user2));

            when(mockBinance.executeSignalForBroadcast(any(), eq("u1")))
                    .thenReturn(List.of(OrderResult.builder()
                            .success(true).orderId("1").symbol("BTCUSDT")
                            .price(94950.5).quantity(0.01).build()));
            when(mockBinance.executeSignalForBroadcast(any(), eq("u2")))
                    .thenReturn(List.of(OrderResult.builder()
                            .success(true).orderId("2").symbol("BTCUSDT")
                            .price(94951.0).quantity(0.02).build()));

            service.broadcastTrade(createEntryRequest());

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000).atLeastOnce()).sendNotificationToUser(
                    eq("admin1"),
                    eq("📊 廣播跟單報告"),
                    bodyCaptor.capture(),
                    anyInt());

            String report = bodyCaptor.getValue();
            assertThat(report).contains("成交明細:");
            assertThat(report).contains("94950.5");
            assertThat(report).contains("94951.0");
        }

        @Test
        @DisplayName("CLOSE 報告含總損益和平均損益")
        void closeReportContainsPnlAggregate() {
            User user1 = createUser("u1", true, true);
            User user2 = createUser("u2", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin, user1, user2));

            when(mockBinance.executeSignalForBroadcast(any(), eq("u1")))
                    .thenReturn(List.of(OrderResult.builder()
                            .success(true).orderId("1").symbol("BTCUSDT")
                            .price(96500.0).quantity(0.01)
                            .netProfit(150.32).totalCommission(0.94).build()));
            when(mockBinance.executeSignalForBroadcast(any(), eq("u2")))
                    .thenReturn(List.of(OrderResult.builder()
                            .success(true).orderId("2").symbol("BTCUSDT")
                            .price(96500.0).quantity(0.02)
                            .netProfit(280.44).totalCommission(1.88).build()));

            TradeRequest closeRequest = new TradeRequest();
            closeRequest.setAction("CLOSE");
            closeRequest.setSymbol("BTCUSDT");

            service.broadcastTrade(closeRequest);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000).atLeastOnce()).sendNotificationToUser(
                    eq("admin1"),
                    eq("📊 廣播平倉報告"),
                    bodyCaptor.capture(),
                    anyInt());

            String report = bodyCaptor.getValue();
            // 總損益 = 150.32 + 280.44 = 430.76
            assertThat(report).contains("總損益:");
            assertThat(report).contains("+430.76");
            // 平均 = 430.76 / 2 = 215.38
            assertThat(report).contains("平均:");
            assertThat(report).contains("+215.38");
            // 平倉明細
            assertThat(report).contains("平倉明細:");
            assertThat(report).contains("+150.32 USDT");
            assertThat(report).contains("+280.44 USDT");
        }

        @Test
        @DisplayName("CLOSE 報告標題為 廣播平倉報告")
        void closeReportTitle() {
            User user1 = createUser("u1", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin, user1));

            when(mockBinance.executeSignalForBroadcast(any(), eq("u1")))
                    .thenReturn(List.of(OrderResult.builder()
                            .success(true).orderId("1").symbol("BTCUSDT")
                            .price(96500.0).netProfit(100.0).build()));

            TradeRequest closeRequest = new TradeRequest();
            closeRequest.setAction("CLOSE");
            closeRequest.setSymbol("BTCUSDT");

            service.broadcastTrade(closeRequest);

            verify(mockWebhook, timeout(5000).atLeastOnce()).sendNotificationToUser(
                    eq("admin1"),
                    eq("📊 廣播平倉報告"),
                    anyString(),
                    anyInt());
        }
    }

    // ==================== AI Signal Scoring ====================

    @Nested
    @DisplayName("AI 信號評分")
    class AiSignalScoring {

        private SignalScore sampleScore() {
            return SignalScore.builder()
                    .confidence(78)
                    .riskLevel("MEDIUM")
                    .reasoning("R:R 1:2.5 合理，但止損幅度偏寬")
                    .latencyMs(2500)
                    .build();
        }

        @Test
        @DisplayName("評分已就緒 → 用戶通知含 AI 分數")
        void scoringEnabled_scoreIncludedInNotification() {
            List<User> users = List.of(createUser("u1", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);

            // 評分立即就緒（completedFuture）
            when(mockScoring.scoreAsync(any()))
                    .thenReturn(CompletableFuture.completedFuture(sampleScore()));

            service.broadcastTrade(createEntryRequest());

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("u1"),
                    eq("✅ 廣播跟單已執行"),
                    bodyCaptor.capture(),
                    eq(DiscordWebhookService.COLOR_GREEN));

            String body = bodyCaptor.getValue();
            assertThat(body).contains("AI: 78/100");
            assertThat(body).contains("中風險");
        }

        @Test
        @DisplayName("Gemini 超時 → 交易不受影響")
        void scoringTimeout_tradeStillExecutes() {
            List<User> users = List.of(createUser("u1", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);

            // 模擬永不完成的 Future（超時場景）
            CompletableFuture<SignalScore> neverComplete = new CompletableFuture<>();
            when(mockScoring.scoreAsync(any())).thenReturn(neverComplete);

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            // 交易照常執行
            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("successCount")).isEqualTo(1);
            verify(mockBinance, timeout(5000)).executeSignalForBroadcast(any(), eq("u1"));

            // DB 不應被更新（分數為 null）
            verify(mockTradeRepo, never()).updateAiScore(any(), any(), any(), any());
        }

        @Test
        @DisplayName("非 ENTRY 信號 → 不評分（scoreAsync 回傳 null）")
        void nonEntrySignal_noScoring() {
            List<User> users = List.of(createUser("u1", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);

            when(mockBinance.executeSignalForBroadcast(any(), eq("u1")))
                    .thenReturn(List.of(OrderResult.builder()
                            .success(true).orderId("1").symbol("BTCUSDT")
                            .price(96500.0).netProfit(100.0).build()));

            TradeRequest closeRequest = new TradeRequest();
            closeRequest.setAction("CLOSE");
            closeRequest.setSymbol("BTCUSDT");

            // 預設 scoreAsync 回傳 null（setUp 已設定）
            service.broadcastTrade(closeRequest);

            // 交易正常完成
            verify(mockBinance, timeout(5000)).executeSignalForBroadcast(any(), eq("u1"));

            // 通知不含 AI 分數
            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("u1"),
                    eq("✅ 廣播平倉已執行"),
                    bodyCaptor.capture(),
                    eq(DiscordWebhookService.COLOR_GREEN));
            assertThat(bodyCaptor.getValue()).doesNotContain("AI:");
        }

        @Test
        @DisplayName("評分已就緒 → Admin 報告含 AI 評分")
        void scoringEnabled_adminSummaryIncludesScore() {
            User admin = User.builder().userId("admin1").email("admin@test.com").passwordHash("h")
                    .enabled(true).role(User.Role.ADMIN).build();
            User user1 = createUser("u1", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin, user1));

            when(mockScoring.scoreAsync(any()))
                    .thenReturn(CompletableFuture.completedFuture(sampleScore()));

            service.broadcastTrade(createEntryRequest());

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000).atLeastOnce()).sendNotificationToUser(
                    eq("admin1"),
                    eq("📊 廣播跟單報告"),
                    bodyCaptor.capture(),
                    anyInt());

            String report = bodyCaptor.getValue();
            assertThat(report).contains("AI 評分: 78/100");
            assertThat(report).contains("中風險");
            assertThat(report).contains("R:R 1:2.5");
        }

        @Test
        @DisplayName("評分已就緒 → Trade DB 記錄被批次更新")
        void scoringEnabled_tradeRecordUpdated() {
            List<User> users = List.of(createUser("u1", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);

            when(mockScoring.scoreAsync(any()))
                    .thenReturn(CompletableFuture.completedFuture(sampleScore()));
            when(mockTradeRepo.updateAiScore(any(), any(), any(), any())).thenReturn(1);

            service.broadcastTrade(createEntryRequest());

            // 驗證 DB 批次更新被呼叫
            verify(mockTradeRepo, timeout(5000)).updateAiScore(
                    eq("BTCUSDT"),
                    eq(78),
                    eq("R:R 1:2.5 合理，但止損幅度偏寬"),
                    any());
        }
    }
}
