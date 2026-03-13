package com.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.dto.SignalScore;
import com.trader.advisor.service.SignalScoringService;
import com.trader.notification.service.NotificationService;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeRequest;
import com.trader.trading.service.SignalSourceService;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.BroadcastTradeService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.papertrade.service.BinancePriceClient;
import com.trader.papertrade.service.PaperTradeService;
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
    private NotificationService mockWebhook;
    private UserApiKeyService mockApiKey;
    private SubscriptionRepository mockSubscriptionRepo;
    private SignalScoringService mockScoring;
    private SignalSourceService mockSignalSource;
    private TradeRepository mockTradeRepo;
    private BroadcastLogRepository mockBroadcastLogRepo;
    private ExecutorService executor;
    private BroadcastTradeService service;

    @BeforeEach
    void setUp() {
        mockUserRepo = mock(UserRepository.class);
        mockBinance = mock(BinanceFuturesService.class);
        mockWebhook = mock(NotificationService.class);
        mockApiKey = mock(UserApiKeyService.class);
        mockSubscriptionRepo = mock(SubscriptionRepository.class);
        mockScoring = mock(SignalScoringService.class);
        mockSignalSource = mock(SignalSourceService.class);
        mockTradeRepo = mock(TradeRepository.class);
        mockBroadcastLogRepo = mock(BroadcastLogRepository.class);

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
                mockScoring, mockSignalSource, mockTradeRepo, mockBroadcastLogRepo, new ObjectMapper(), executor,
                mock(PaperTradeService.class), mock(BinancePriceClient.class),
                15, 0L);
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
                    eq(NotificationService.COLOR_GREEN));
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
                    eq(NotificationService.COLOR_RED));
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
                    eq(NotificationService.COLOR_GREEN));

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
                    eq(NotificationService.COLOR_GREEN));

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
                    eq("✅ 全部平倉已執行"),
                    bodyCaptor.capture(),
                    eq(NotificationService.COLOR_GREEN));

            String body = bodyCaptor.getValue();
            assertThat(body).contains("類型: 全部平倉");
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
                    eq(NotificationService.COLOR_GREEN));

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
                    eq("✅ 全部平倉已執行"),
                    bodyCaptor.capture(),
                    eq(NotificationService.COLOR_GREEN));
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

    // ==================== Multi-User Mode: Admin Role Handling ====================

    @Nested
    @DisplayName("多用戶模式 — Admin 角色處理")
    class MultiUserAdminHandling {

        private User createAdmin(String id) {
            return User.builder()
                    .userId(id)
                    .email(id + "@test.com")
                    .passwordHash("hash")
                    .autoTradeEnabled(true)
                    .enabled(true)
                    .role(User.Role.ADMIN)
                    .build();
        }

        @Test
        @DisplayName("Admin 不下單 — 只收通知")
        void adminDoesNotTrade() {
            User admin = createAdmin("admin1");
            User user = createUser("u1", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin, user));

            service.broadcastTrade(createEntryRequest());

            // Admin 不應被呼叫 executeSignalForBroadcast
            verify(mockBinance, never()).executeSignalForBroadcast(any(), eq("admin1"));
            // 普通用戶正常下單
            verify(mockBinance, timeout(5000)).executeSignalForBroadcast(any(), eq("u1"));
        }

        @Test
        @DisplayName("多位 Admin 都收到廣播前通知")
        void multipleAdminsReceivePreBroadcastNotification() {
            User admin1 = createAdmin("admin1");
            User admin2 = createAdmin("admin2");
            User user = createUser("u1", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin1, admin2, user));

            service.broadcastTrade(createEntryRequest());

            // 兩位 Admin 都收到廣播前通知
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("admin1"), eq("📡 廣播訊號已發送"), anyString(), eq(NotificationService.COLOR_BLUE));
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("admin2"), eq("📡 廣播訊號已發送"), anyString(), eq(NotificationService.COLOR_BLUE));
        }

        @Test
        @DisplayName("多位 Admin 都收到廣播完成彙總報告")
        void multipleAdminsReceiveSummaryReport() {
            User admin1 = createAdmin("admin1");
            User admin2 = createAdmin("admin2");
            User user = createUser("u1", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin1, admin2, user));

            service.broadcastTrade(createEntryRequest());

            // 兩位 Admin 都收到彙總報告
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("admin1"), eq("📊 廣播跟單報告"), anyString(), anyInt());
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("admin2"), eq("📊 廣播跟單報告"), anyString(), anyInt());
        }

        @Test
        @DisplayName("Admin 被排除在 activeUsers 之外 — 不計入 totalUsers")
        void adminExcludedFromTotalUsers() {
            User admin = createAdmin("admin1");
            User user1 = createUser("u1", true, true);
            User user2 = createUser("u2", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin, user1, user2));

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("totalUsers")).isEqualTo(2);
        }

        @Test
        @DisplayName("停用的 Admin 不收到通知")
        void disabledAdminNoNotification() {
            User disabledAdmin = User.builder()
                    .userId("admin-disabled").email("disabled@test.com").passwordHash("h")
                    .autoTradeEnabled(true).enabled(false).role(User.Role.ADMIN).build();
            User user = createUser("u1", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(disabledAdmin, user));

            service.broadcastTrade(createEntryRequest());

            // 停用的 Admin 不收通知
            verify(mockWebhook, never()).sendNotificationToUser(
                    eq("admin-disabled"), anyString(), anyString(), anyInt());
        }
    }

    // ==================== Multi-User Mode: Various Action Types ====================

    @Nested
    @DisplayName("多用戶模式 — 各種操作類型")
    class MultiUserActionTypes {

        @BeforeEach
        void setUpUsers() {
            List<User> users = List.of(
                    createUser("u1", true, true),
                    createUser("u2", true, true));
            when(mockUserRepo.findAll()).thenReturn(users);
        }

        @Test
        @DisplayName("CLOSE 全部平倉 — 所有用戶執行")
        void closeFullPosition() {
            TradeRequest req = new TradeRequest();
            req.setAction("CLOSE");
            req.setSymbol("BTCUSDT");

            Map<String, Object> result = service.broadcastTrade(req);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(2);
            verify(mockBinance, timeout(5000)).executeSignalForBroadcast(eq(req), eq("u1"));
            verify(mockBinance, timeout(5000)).executeSignalForBroadcast(eq(req), eq("u2"));
        }

        @Test
        @DisplayName("CLOSE 部分平倉 — 通知含比例")
        void closePartialPosition() {
            TradeRequest req = new TradeRequest();
            req.setAction("CLOSE");
            req.setSymbol("BTCUSDT");
            req.setCloseRatio(0.5);

            when(mockBinance.executeSignalForBroadcast(any(), eq("u1")))
                    .thenReturn(List.of(OrderResult.builder()
                            .success(true).orderId("1").symbol("BTCUSDT")
                            .price(96000.0).netProfit(50.0).build()));

            service.broadcastTrade(req);

            // 驗證用戶通知標題為部分平倉
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("u1"),
                    contains("部分平倉"),
                    contains("50%"),
                    eq(NotificationService.COLOR_GREEN));
        }

        @Test
        @DisplayName("MOVE_SL — 所有用戶執行")
        void moveStopLoss() {
            TradeRequest req = new TradeRequest();
            req.setAction("MOVE_SL");
            req.setSymbol("BTCUSDT");
            req.setNewStopLoss(94500.0);
            req.setNewTakeProfit(98000.0);

            Map<String, Object> result = service.broadcastTrade(req);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(2);
            verify(mockBinance, timeout(5000)).executeSignalForBroadcast(eq(req), eq("u1"));
            verify(mockBinance, timeout(5000)).executeSignalForBroadcast(eq(req), eq("u2"));
        }

        @Test
        @DisplayName("MOVE_SL — 用戶通知標題為「移動止損已執行」且含新止損和止盈")
        void moveSLNotificationContent() {
            // 只用一個用戶簡化驗證
            when(mockUserRepo.findAll()).thenReturn(List.of(createUser("u1", true, true)));

            TradeRequest req = new TradeRequest();
            req.setAction("MOVE_SL");
            req.setSymbol("BTCUSDT");
            req.setNewStopLoss(94500.0);
            req.setNewTakeProfit(98000.0);

            service.broadcastTrade(req);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("u1"),
                    eq("✅ 移動止損已執行"),
                    bodyCaptor.capture(),
                    eq(NotificationService.COLOR_GREEN));

            String body = bodyCaptor.getValue();
            assertThat(body).contains("動作: 移動止損");
            assertThat(body).contains("新止損: 94500.0");
            assertThat(body).contains("新止盈: 98000.0");
        }

        @Test
        @DisplayName("CANCEL — 所有用戶執行")
        void cancelOrders() {
            TradeRequest req = new TradeRequest();
            req.setAction("CANCEL");
            req.setSymbol("BTCUSDT");

            Map<String, Object> result = service.broadcastTrade(req);

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(2);
            verify(mockBinance, timeout(5000)).executeSignalForBroadcast(eq(req), eq("u1"));
            verify(mockBinance, timeout(5000)).executeSignalForBroadcast(eq(req), eq("u2"));
        }

        @Test
        @DisplayName("CANCEL — 用戶通知標題為「取消掛單已執行」")
        void cancelNotificationTitle() {
            when(mockUserRepo.findAll()).thenReturn(List.of(createUser("u1", true, true)));

            TradeRequest req = new TradeRequest();
            req.setAction("CANCEL");
            req.setSymbol("BTCUSDT");

            service.broadcastTrade(req);

            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("u1"),
                    eq("✅ 取消掛單已執行"),
                    anyString(),
                    eq(NotificationService.COLOR_GREEN));
        }

        @Test
        @DisplayName("ENTRY DCA 補倉 — Admin 通知含 DCA 標記")
        void entryDcaSignal() {
            User admin = User.builder()
                    .userId("admin1").email("admin@test.com").passwordHash("h")
                    .enabled(true).role(User.Role.ADMIN).build();
            when(mockUserRepo.findAll()).thenReturn(List.of(
                    admin, createUser("u1", true, true)));

            TradeRequest req = createEntryRequest();
            req.setIsDca(true);

            service.broadcastTrade(req);

            // Admin 收到的廣播前通知含 DCA
            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("admin1"), eq("📡 廣播訊號已發送"),
                    bodyCaptor.capture(), anyInt());

            assertThat(bodyCaptor.getValue()).contains("DCA");
        }
    }

    // ==================== Multi-User Mode: Summary Report Color ====================

    @Nested
    @DisplayName("多用戶模式 — 彙總報告顏色邏輯")
    class SummaryReportColor {

        private User admin;

        @BeforeEach
        void setUp() {
            admin = User.builder().userId("admin1").email("admin@test.com").passwordHash("h")
                    .enabled(true).role(User.Role.ADMIN).build();
        }

        @Test
        @DisplayName("全部成功 → 綠色報告")
        void allSuccess_greenReport() {
            User user1 = createUser("u1", true, true);
            User user2 = createUser("u2", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin, user1, user2));

            service.broadcastTrade(createEntryRequest());

            // 報告應為綠色（全部成功）
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("admin1"),
                    eq("📊 廣播跟單報告"),
                    anyString(),
                    eq(NotificationService.COLOR_GREEN));
        }

        @Test
        @DisplayName("有失敗 → 黃色報告")
        void hasFailure_yellowReport() {
            User user1 = createUser("u1", true, true);
            User user2 = createUser("u2", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin, user1, user2));

            doReturn(List.of()).when(mockBinance).executeSignalForBroadcast(any(), eq("u1"));
            doThrow(new RuntimeException("error")).when(mockBinance)
                    .executeSignalForBroadcast(any(), eq("u2"));

            service.broadcastTrade(createEntryRequest());

            // 報告應為黃色（有失敗）
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("admin1"),
                    eq("📊 廣播跟單報告"),
                    anyString(),
                    eq(NotificationService.COLOR_YELLOW));
        }
    }

    // ==================== Multi-User Mode: Admin Pre-Broadcast Notification ====================

    @Nested
    @DisplayName("多用戶模式 — Admin 廣播前通知詳情")
    class AdminPreBroadcastNotification {

        private User admin;

        @BeforeEach
        void setUp() {
            admin = User.builder().userId("admin1").email("admin@test.com").passwordHash("h")
                    .enabled(true).role(User.Role.ADMIN).build();
        }

        @Test
        @DisplayName("ENTRY 訊號 → Admin 通知含入場價、止損、目標人數")
        void entryNotificationContainsDetails() {
            User user = createUser("u1", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin, user));

            TradeRequest req = createEntryRequest();
            req.setTakeProfit(97000.0);

            service.broadcastTrade(req);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("admin1"), eq("📡 廣播訊號已發送"),
                    bodyCaptor.capture(), eq(NotificationService.COLOR_BLUE));

            String body = bodyCaptor.getValue();
            assertThat(body).contains("BTCUSDT");
            assertThat(body).contains("95000.0");
            assertThat(body).contains("93000.0");
            assertThat(body).contains("97000.0");
            assertThat(body).contains("目標用戶: 1 人");
        }

        @Test
        @DisplayName("有跳過的用戶 → Admin 通知含跳過統計")
        void notificationContainsSkippedCounts() {
            User user1 = createUser("u1", true, true);
            User user2 = createUser("u2", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin, user1, user2));

            // u2 沒有訂閱
            when(mockSubscriptionRepo.findUserIdsWithActiveSubscription())
                    .thenReturn(List.of("u1"));

            service.broadcastTrade(createEntryRequest());

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("admin1"), eq("📡 廣播訊號已發送"),
                    bodyCaptor.capture(), anyInt());

            assertThat(bodyCaptor.getValue()).contains("跳過 (無訂閱): 1 人");
        }

        @Test
        @DisplayName("CLOSE 訊號 → Admin 通知含平倉比例")
        void closeNotificationContainsRatio() {
            User user = createUser("u1", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin, user));

            TradeRequest req = new TradeRequest();
            req.setAction("CLOSE");
            req.setSymbol("BTCUSDT");
            req.setCloseRatio(0.5);

            service.broadcastTrade(req);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("admin1"), eq("📡 廣播訊號已發送"),
                    bodyCaptor.capture(), anyInt());

            assertThat(bodyCaptor.getValue()).contains("50%");
        }

        @Test
        @DisplayName("訊號含 Source → Admin 通知含來源資訊")
        void notificationContainsSourceInfo() {
            User user = createUser("u1", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(admin, user));

            TradeRequest req = createEntryRequest();
            com.trader.shared.model.SignalSource source = new com.trader.shared.model.SignalSource();
            source.setPlatform("ADMIN_DASHBOARD");
            source.setAuthorName("admin@test.com");
            req.setSource(source);

            service.broadcastTrade(req);

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("admin1"), eq("📡 廣播訊號已發送"),
                    bodyCaptor.capture(), anyInt());

            assertThat(bodyCaptor.getValue()).contains("ADMIN_DASHBOARD");
        }
    }

    // ==================== Multi-User Mode: Edge Cases ====================

    @Nested
    @DisplayName("多用戶模式 — 邊界情況")
    class MultiUserEdgeCases {

        @Test
        @DisplayName("只有 Admin 無用戶 → 返回空但 Admin 仍收通知")
        void onlyAdminsNoUsers() {
            User admin = User.builder()
                    .userId("admin1").email("admin@test.com").passwordHash("h")
                    .enabled(true).role(User.Role.ADMIN).build();
            when(mockUserRepo.findAll()).thenReturn(List.of(admin));

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(0);

            // Admin 仍收到廣播前通知
            verify(mockWebhook).sendNotificationToUser(
                    eq("admin1"), eq("📡 廣播訊號已發送"), anyString(), anyInt());
        }

        @Test
        @DisplayName("用戶同時缺訂閱和 API Key — 只計入 skippedNoSubscription")
        void userMissingBothSubscriptionAndApiKey() {
            User user1 = createUser("u1", true, true);
            when(mockUserRepo.findAll()).thenReturn(List.of(user1));

            // u1 無訂閱
            when(mockSubscriptionRepo.findUserIdsWithActiveSubscription())
                    .thenReturn(List.of());
            // u1 也無 API Key（但因訂閱先過濾，不會計入 skippedNoApiKey）
            when(mockApiKey.getUserIdsWithApiKey("BINANCE"))
                    .thenReturn(Set.of());

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("skippedNoSubscription")).isEqualTo(1);
            assertThat(result.get("skippedNoApiKey")).isEqualTo(0);
        }

        @Test
        @DisplayName("大量用戶 — 並行執行不阻塞")
        void manyUsersConcurrentExecution() {
            List<User> manyUsers = new ArrayList<>();
            Set<String> manyUserIds = new HashSet<>();
            for (int i = 0; i < 20; i++) {
                String id = "u" + i;
                manyUsers.add(createUser(id, true, true));
                manyUserIds.add(id);
            }
            when(mockUserRepo.findAll()).thenReturn(manyUsers);
            when(mockApiKey.getUserIdsWithApiKey("BINANCE")).thenReturn(manyUserIds);
            when(mockSubscriptionRepo.findUserIdsWithActiveSubscription())
                    .thenReturn(new ArrayList<>(manyUserIds));

            Map<String, Object> result = service.broadcastTrade(createEntryRequest());

            assertThat(result.get("status")).isEqualTo("COMPLETED");
            assertThat(result.get("totalUsers")).isEqualTo(20);
            assertThat((int) result.get("successCount")).isEqualTo(20);
        }

        @Test
        @DisplayName("用戶 name 為空 → 顯示名用 email fallback")
        void userWithoutName_usesEmailFallback() {
            User user = User.builder()
                    .userId("u1").email("test@test.com").passwordHash("hash")
                    .autoTradeEnabled(true).enabled(true).build();
            // name 未設定 = null
            when(mockUserRepo.findAll()).thenReturn(List.of(user));

            service.broadcastTrade(createEntryRequest());

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("u1"), anyString(), bodyCaptor.capture(), anyInt());

            // fallback 使用 email
            assertThat(bodyCaptor.getValue()).contains("test@test.com");
        }

        @Test
        @DisplayName("用戶有 name → 顯示 name (email)")
        void userWithName_displaysNameAndEmail() {
            User user = User.builder()
                    .userId("u1").email("test@test.com").name("Alice").passwordHash("hash")
                    .autoTradeEnabled(true).enabled(true).build();
            when(mockUserRepo.findAll()).thenReturn(List.of(user));

            service.broadcastTrade(createEntryRequest());

            ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
            verify(mockWebhook, timeout(5000)).sendNotificationToUser(
                    eq("u1"), anyString(), bodyCaptor.capture(), anyInt());

            assertThat(bodyCaptor.getValue()).contains("Alice (test@test.com)");
        }
    }
}
