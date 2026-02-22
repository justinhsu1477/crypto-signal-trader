package com.trader.trading.service;

import com.trader.shared.config.RiskConfig;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.Trade;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DailyReportService 單元測試
 *
 * 驗證 multiUserConfig.isEnabled() 分支：
 * - false（單人模式）：全局查詢 + 全局 webhook
 * - true（多用戶模式）：per-user 查詢 + per-user webhook + per-user API Key
 */
class DailyReportServiceTest {

    private TradeRecordService tradeRecordService;
    private DiscordWebhookService webhookService;
    private BinanceFuturesService binanceFuturesService;
    private BinanceUserDataStreamService userDataStreamService;
    private MonitorHeartbeatService monitorHeartbeatService;
    private RiskConfig riskConfig;
    private MultiUserConfig multiUserConfig;
    private UserRepository userRepository;
    private UserApiKeyService userApiKeyService;
    private TradeConfigResolver tradeConfigResolver;

    private DailyReportService service;

    /** 共用的空統計 Map */
    private Map<String, Object> emptyStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("trades", 0L);
        stats.put("wins", 0L);
        stats.put("losses", 0L);
        stats.put("netProfit", 0.0);
        stats.put("commission", 0.0);
        stats.put("openTrades", List.of());
        return stats;
    }

    /** 共用的空摘要 Map */
    private Map<String, Object> emptySummary() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("closedTrades", 0L);
        stats.put("winningTrades", 0L);
        stats.put("winRate", "0.0%");
        stats.put("totalNetProfit", 0.0);
        stats.put("grossWins", 0.0);
        stats.put("grossLosses", 0.0);
        stats.put("profitFactor", 0.0);
        stats.put("avgProfitPerTrade", 0.0);
        stats.put("totalCommission", 0.0);
        stats.put("openPositions", 0L);
        return stats;
    }

    /** 建立 mock 用戶 */
    private User mockUser(String userId, boolean enabled) {
        return User.builder()
                .userId(userId)
                .email(userId + "@test.com")
                .passwordHash("hash")
                .name(userId)
                .role(User.Role.USER)
                .enabled(enabled)
                .autoTradeEnabled(true)
                .build();
    }

    /** 設定 MonitorHeartbeatService 的標準 mock（避免 NPE） */
    private void setupMonitorMocks() {
        Map<String, Object> monitorStatus = Map.of(
                "online", true,
                "monitorStatus", "running",
                "aiStatus", "active"
        );
        when(monitorHeartbeatService.getStatus()).thenReturn(monitorStatus);

        Map<String, Object> wsStatus = Map.of("connected", true);
        when(userDataStreamService.getStatus()).thenReturn(wsStatus);

        Map<String, Long> tokenStats = Map.of(
                "callCount", 0L,
                "promptTokens", 0L,
                "responseTokens", 0L
        );
        when(monitorHeartbeatService.getDailyTokenStats()).thenReturn(tokenStats);
    }

    // ==================== 單人模式測試 ====================

    @Nested
    @DisplayName("單人模式 — MULTI_USER_ENABLED=false")
    class SingleModeTests {

        @BeforeEach
        void setUp() {
            tradeRecordService = mock(TradeRecordService.class);
            webhookService = mock(DiscordWebhookService.class);
            binanceFuturesService = mock(BinanceFuturesService.class);
            userDataStreamService = mock(BinanceUserDataStreamService.class);
            monitorHeartbeatService = mock(MonitorHeartbeatService.class);
            riskConfig = mock(RiskConfig.class);
            multiUserConfig = new MultiUserConfig(); // enabled=false（預設）
            userRepository = mock(UserRepository.class);
            userApiKeyService = mock(UserApiKeyService.class);
            tradeConfigResolver = mock(TradeConfigResolver.class);

            service = new DailyReportService(
                    tradeRecordService, webhookService, binanceFuturesService,
                    userDataStreamService, monitorHeartbeatService, riskConfig,
                    multiUserConfig, userRepository, userApiKeyService, tradeConfigResolver);

            setupMonitorMocks();
        }

        @Test
        @DisplayName("sendDailyReport — 使用全局 webhook（不呼叫 sendNotificationToUser）")
        void sendDailyReport_usesGlobalWebhook() {
            when(tradeRecordService.getStatsForDateRange(any(), any())).thenReturn(emptyStats());
            when(tradeRecordService.getClosedTradesForRange(any(), any())).thenReturn(List.of());
            when(tradeRecordService.getStatsSummary()).thenReturn(emptySummary());
            when(binanceFuturesService.getAvailableBalance()).thenReturn(10000.0);
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(0.0);
            when(riskConfig.getMaxDailyLossUsdt()).thenReturn(2000.0);

            service.sendDailyReport();

            // 驗證呼叫全局 webhook
            verify(webhookService).sendNotification(
                    contains("每日交易摘要"), anyString(), eq(DiscordWebhookService.COLOR_BLUE));

            // 驗證沒有呼叫 per-user webhook
            verify(webhookService, never()).sendNotificationToUser(anyString(), anyString(), anyString(), anyInt());

            // 驗證沒有查用戶列表
            verify(userRepository, never()).findAll();
        }

        @Test
        @DisplayName("sendDailyReport — 使用無參數版本的查詢方法（不帶 userId）")
        void sendDailyReport_usesGlobalQueryMethods() {
            when(tradeRecordService.getStatsForDateRange(any(), any())).thenReturn(emptyStats());
            when(tradeRecordService.getClosedTradesForRange(any(), any())).thenReturn(List.of());
            when(tradeRecordService.getStatsSummary()).thenReturn(emptySummary());
            when(binanceFuturesService.getAvailableBalance()).thenReturn(10000.0);
            when(tradeRecordService.getTodayRealizedLoss()).thenReturn(0.0);
            when(riskConfig.getMaxDailyLossUsdt()).thenReturn(2000.0);

            service.sendDailyReport();

            // 驗證呼叫的是無 userId 的版本
            verify(tradeRecordService).getStatsForDateRange(any(), any());
            verify(tradeRecordService).getClosedTradesForRange(any(), any());
            verify(tradeRecordService).getStatsSummary();
            verify(tradeRecordService).getTodayRealizedLoss();

            // 驗證沒有呼叫 explicit-userId 版本
            verify(tradeRecordService, never()).getStatsForDateRange(any(), any(), anyString());
            verify(tradeRecordService, never()).getClosedTradesForRange(any(), any(), anyString());
            verify(tradeRecordService, never()).getStatsSummary(anyString());
            verify(tradeRecordService, never()).getTodayRealizedLoss(anyString());
        }

        @Test
        @DisplayName("scheduledCleanup — 使用全局清理（不遍歷用戶）")
        void scheduledCleanup_globalCleanup() {
            Map<String, Object> cleanupResult = Map.of("cleaned", 1, "skipped", 0);
            when(tradeRecordService.cleanupStaleTrades(any())).thenReturn(cleanupResult);

            service.scheduledCleanup();

            // 驗證呼叫全局清理
            verify(tradeRecordService).cleanupStaleTrades(any());

            // 驗證全局通知
            verify(webhookService).sendNotification(
                    contains("殭屍 Trade"), anyString(), eq(DiscordWebhookService.COLOR_BLUE));

            // 驗證沒有查用戶列表
            verify(userRepository, never()).findAll();
        }
    }

    // ==================== 多用戶模式測試 ====================

    @Nested
    @DisplayName("多用戶模式 — MULTI_USER_ENABLED=true")
    class MultiModeTests {

        @BeforeEach
        void setUp() {
            tradeRecordService = mock(TradeRecordService.class);
            webhookService = mock(DiscordWebhookService.class);
            binanceFuturesService = mock(BinanceFuturesService.class);
            userDataStreamService = mock(BinanceUserDataStreamService.class);
            monitorHeartbeatService = mock(MonitorHeartbeatService.class);
            riskConfig = mock(RiskConfig.class);
            multiUserConfig = new MultiUserConfig();
            multiUserConfig.setEnabled(true);
            userRepository = mock(UserRepository.class);
            userApiKeyService = mock(UserApiKeyService.class);
            tradeConfigResolver = mock(TradeConfigResolver.class);

            service = new DailyReportService(
                    tradeRecordService, webhookService, binanceFuturesService,
                    userDataStreamService, monitorHeartbeatService, riskConfig,
                    multiUserConfig, userRepository, userApiKeyService, tradeConfigResolver);

            setupMonitorMocks();
        }

        @Test
        @DisplayName("sendDailyReport — 遍歷每個 enabled 用戶，發到各自的 webhook")
        void sendDailyReport_sendsToEachUser() {
            User userA = mockUser("user-A", true);
            User userB = mockUser("user-B", true);
            User disabledUser = mockUser("user-C", false);

            when(userRepository.findAll()).thenReturn(List.of(userA, userB, disabledUser));

            // 每個用戶的查詢 mock
            when(tradeRecordService.getStatsForDateRange(any(), any(), anyString())).thenReturn(emptyStats());
            when(tradeRecordService.getClosedTradesForRange(any(), any(), anyString())).thenReturn(List.of());
            when(tradeRecordService.getStatsSummary(anyString())).thenReturn(emptySummary());
            when(tradeRecordService.getTodayRealizedLoss(anyString())).thenReturn(0.0);

            // per-user API Key（用戶 A 有 key，用戶 B 無 key）
            when(userApiKeyService.getUserBinanceKeys("user-A"))
                    .thenReturn(Optional.of(new BinanceKeys("apiA", "secretA")));
            when(userApiKeyService.getUserBinanceKeys("user-B")).thenReturn(Optional.empty());
            when(binanceFuturesService.getAvailableBalance()).thenReturn(5000.0);

            // per-user risk config
            EffectiveTradeConfig config = new EffectiveTradeConfig(
                    0.2, 50000, 2000, 3, 2.0, 20, List.of("BTCUSDT"), true, "BTCUSDT");
            when(tradeConfigResolver.resolve(anyString())).thenReturn(config);

            service.sendDailyReport();

            // 驗證發送了 2 次 per-user webhook（user-A 和 user-B，user-C disabled 被過濾）
            verify(webhookService, times(2)).sendNotificationToUser(
                    anyString(), contains("每日交易摘要"), anyString(), eq(DiscordWebhookService.COLOR_BLUE));

            // 驗證 user-A 收到通知
            verify(webhookService).sendNotificationToUser(
                    eq("user-A"), anyString(), anyString(), anyInt());

            // 驗證 user-B 收到通知
            verify(webhookService).sendNotificationToUser(
                    eq("user-B"), anyString(), anyString(), anyInt());

            // 驗證沒有呼叫全局 webhook
            verify(webhookService, never()).sendNotification(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("sendDailyReport — 使用 explicit-userId 查詢方法")
        void sendDailyReport_usesExplicitUserIdQueries() {
            User user = mockUser("user-A", true);
            when(userRepository.findAll()).thenReturn(List.of(user));

            when(tradeRecordService.getStatsForDateRange(any(), any(), eq("user-A"))).thenReturn(emptyStats());
            when(tradeRecordService.getClosedTradesForRange(any(), any(), eq("user-A"))).thenReturn(List.of());
            when(tradeRecordService.getStatsSummary("user-A")).thenReturn(emptySummary());
            when(tradeRecordService.getTodayRealizedLoss("user-A")).thenReturn(0.0);
            when(userApiKeyService.getUserBinanceKeys("user-A")).thenReturn(Optional.empty());

            EffectiveTradeConfig config = new EffectiveTradeConfig(
                    0.2, 50000, 2000, 3, 2.0, 20, List.of("BTCUSDT"), true, "BTCUSDT");
            when(tradeConfigResolver.resolve("user-A")).thenReturn(config);

            service.sendDailyReport();

            // 驗證呼叫的是 explicit-userId 版本
            verify(tradeRecordService).getStatsForDateRange(any(), any(), eq("user-A"));
            verify(tradeRecordService).getClosedTradesForRange(any(), any(), eq("user-A"));
            verify(tradeRecordService).getStatsSummary("user-A");
            verify(tradeRecordService).getTodayRealizedLoss("user-A");

            // 驗證沒有呼叫無參數版本
            verify(tradeRecordService, never()).getStatsForDateRange(any(), any());
            verify(tradeRecordService, never()).getClosedTradesForRange(any(), any());
            verify(tradeRecordService, never()).getStatsSummary();
            verify(tradeRecordService, never()).getTodayRealizedLoss();
        }

        @Test
        @DisplayName("sendDailyReport — 一個用戶失敗不影響其他用戶")
        void sendDailyReport_oneUserFailsOthersStillSend() {
            User userA = mockUser("user-A", true);
            User userB = mockUser("user-B", true);
            when(userRepository.findAll()).thenReturn(List.of(userA, userB));

            // user-A 的查詢拋異常
            when(tradeRecordService.getStatsForDateRange(any(), any(), eq("user-A")))
                    .thenThrow(new RuntimeException("DB error"));

            // user-B 正常
            when(tradeRecordService.getStatsForDateRange(any(), any(), eq("user-B"))).thenReturn(emptyStats());
            when(tradeRecordService.getClosedTradesForRange(any(), any(), eq("user-B"))).thenReturn(List.of());
            when(tradeRecordService.getStatsSummary("user-B")).thenReturn(emptySummary());
            when(tradeRecordService.getTodayRealizedLoss("user-B")).thenReturn(0.0);
            when(userApiKeyService.getUserBinanceKeys("user-B")).thenReturn(Optional.empty());

            EffectiveTradeConfig config = new EffectiveTradeConfig(
                    0.2, 50000, 2000, 3, 2.0, 20, List.of("BTCUSDT"), true, "BTCUSDT");
            when(tradeConfigResolver.resolve("user-B")).thenReturn(config);

            // 不應拋出異常
            assertThatCode(() -> service.sendDailyReport()).doesNotThrowAnyException();

            // user-B 仍然收到通知
            verify(webhookService).sendNotificationToUser(
                    eq("user-B"), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("sendDailyReport — per-user 帳戶餘額使用用戶的 API Key")
        void sendDailyReport_perUserBalance_usesUserApiKey() {
            User user = mockUser("user-A", true);
            when(userRepository.findAll()).thenReturn(List.of(user));

            when(tradeRecordService.getStatsForDateRange(any(), any(), eq("user-A"))).thenReturn(emptyStats());
            when(tradeRecordService.getClosedTradesForRange(any(), any(), eq("user-A"))).thenReturn(List.of());
            when(tradeRecordService.getStatsSummary("user-A")).thenReturn(emptySummary());
            when(tradeRecordService.getTodayRealizedLoss("user-A")).thenReturn(0.0);

            BinanceKeys keys = new BinanceKeys("user-api-key", "user-secret-key");
            when(userApiKeyService.getUserBinanceKeys("user-A")).thenReturn(Optional.of(keys));
            when(binanceFuturesService.getAvailableBalance()).thenReturn(8888.88);

            EffectiveTradeConfig config = new EffectiveTradeConfig(
                    0.2, 50000, 2000, 3, 2.0, 20, List.of("BTCUSDT"), true, "BTCUSDT");
            when(tradeConfigResolver.resolve("user-A")).thenReturn(config);

            service.sendDailyReport();

            // 驗證有查詢餘額
            verify(binanceFuturesService).getAvailableBalance();

            // 驗證通知內容包含餘額
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(webhookService).sendNotificationToUser(
                    eq("user-A"), anyString(), messageCaptor.capture(), anyInt());
            assertThat(messageCaptor.getValue()).contains("8888.88");
        }

        @Test
        @DisplayName("scheduledCleanup — 遍歷用戶，使用 per-user API Key 查詢持倉")
        void scheduledCleanup_perUserCleanup() {
            User userA = mockUser("user-A", true);
            User userB = mockUser("user-B", true);
            when(userRepository.findAll()).thenReturn(List.of(userA, userB));

            // user-A 有 API Key
            BinanceKeys keysA = new BinanceKeys("apiA", "secretA");
            when(userApiKeyService.getUserBinanceKeys("user-A")).thenReturn(Optional.of(keysA));
            // user-B 沒有 API Key
            when(userApiKeyService.getUserBinanceKeys("user-B")).thenReturn(Optional.empty());

            Map<String, Object> cleanupResult = Map.of("cleaned", 1, "skipped", 0);
            when(tradeRecordService.cleanupStaleTrades(any())).thenReturn(cleanupResult);

            service.scheduledCleanup();

            // user-A 有 key → 執行清理
            verify(tradeRecordService, times(1)).cleanupStaleTrades(any());

            // user-A 清理後有通知
            verify(webhookService).sendNotificationToUser(
                    eq("user-A"), contains("殭屍 Trade"), anyString(), eq(DiscordWebhookService.COLOR_BLUE));

            // user-B 沒有 key → 不執行清理
            verify(webhookService, never()).sendNotificationToUser(
                    eq("user-B"), anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("sendDailyReport — per-user 風控使用 TradeConfigResolver")
        void sendDailyReport_perUserRiskBudget() {
            User user = mockUser("user-X", true);
            when(userRepository.findAll()).thenReturn(List.of(user));

            when(tradeRecordService.getStatsForDateRange(any(), any(), eq("user-X"))).thenReturn(emptyStats());
            when(tradeRecordService.getClosedTradesForRange(any(), any(), eq("user-X"))).thenReturn(List.of());
            when(tradeRecordService.getStatsSummary("user-X")).thenReturn(emptySummary());
            when(tradeRecordService.getTodayRealizedLoss("user-X")).thenReturn(-500.0);
            when(userApiKeyService.getUserBinanceKeys("user-X")).thenReturn(Optional.empty());

            // per-user config: maxDailyLossUsdt = 1000（與全局 2000 不同）
            EffectiveTradeConfig config = new EffectiveTradeConfig(
                    0.2, 50000, 1000, 3, 2.0, 20, List.of("BTCUSDT"), true, "BTCUSDT");
            when(tradeConfigResolver.resolve("user-X")).thenReturn(config);

            service.sendDailyReport();

            // 驗證呼叫 per-user 風控
            verify(tradeRecordService).getTodayRealizedLoss("user-X");
            verify(tradeConfigResolver).resolve("user-X");

            // 驗證通知內容包含 per-user 風控數據（500/1000 = 50%）
            ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
            verify(webhookService).sendNotificationToUser(
                    eq("user-X"), anyString(), messageCaptor.capture(), anyInt());
            assertThat(messageCaptor.getValue()).contains("500.00");
            assertThat(messageCaptor.getValue()).contains("1000");
        }
    }
}
