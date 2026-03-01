package com.trader.trading.service;

import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.TradeRepository;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.NotificationService;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StartupReconciliationService 單元測試
 *
 * 測試重點：
 * 1. 殭屍 OPEN 清理 — 掛單/持倉/失敗場景
 * 2. PENDING_CLOSE 修復 — 持倉歸零場景
 * 3. 多用戶模式 — 按 userId 分組 + per-user API Key
 */
class StartupReconciliationServiceTest {

    private TradeRepository tradeRepository;
    private BinanceFuturesService binanceFuturesService;
    private NotificationService discordWebhookService;
    private MultiUserConfig multiUserConfig;
    private UserApiKeyService userApiKeyService;
    private StartupReconciliationService service;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        binanceFuturesService = mock(BinanceFuturesService.class);
        discordWebhookService = mock(NotificationService.class);
        multiUserConfig = new MultiUserConfig(); // 預設 enabled=false
        userApiKeyService = mock(UserApiKeyService.class);
        service = new StartupReconciliationService(
                tradeRepository, binanceFuturesService, discordWebhookService,
                multiUserConfig, userApiKeyService);
    }

    @AfterEach
    void tearDown() {
        BinanceFuturesService.clearCurrentUserKeys();
    }

    // ==================== reconcileZombieOpenTrades ====================

    @Nested
    @DisplayName("殭屍 OPEN 交易清理")
    class ZombieCleanupTests {

        @Test
        @DisplayName("無 OPEN 交易 → 直接回傳 0")
        void noOpenTrades_returnsZero() {
            when(tradeRepository.findByStatus("OPEN")).thenReturn(Collections.emptyList());

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isZero();
            assertThat(report).isEmpty();
            verify(binanceFuturesService, never()).getCurrentPositionAmount(any());
        }

        @Test
        @DisplayName("Binance 有持倉 → 保留 OPEN，不做任何處理")
        void hasPosition_keepOpen() {
            Trade trade = createOpenTrade("trade-1", "BTCUSDT", "LONG");
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(binanceFuturesService.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.143);

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isZero();
            assertThat(report).isEmpty();
            assertThat(trade.getStatus()).isEqualTo("OPEN");
            verify(tradeRepository, never()).save(any());
            verify(binanceFuturesService, never()).hasOpenEntryOrders(any());
        }

        @Test
        @DisplayName("Binance 無持倉 + 無掛單 → 標為 CANCELLED")
        void noPositionNoOrders_markCancelled() {
            Trade trade = createOpenTrade("trade-2", "ETHUSDT", "SHORT");
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(binanceFuturesService.getCurrentPositionAmount("ETHUSDT")).thenReturn(0.0);
            when(binanceFuturesService.hasOpenEntryOrders("ETHUSDT")).thenReturn(false);

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isEqualTo(1);
            assertThat(trade.getStatus()).isEqualTo("CANCELLED");
            assertThat(trade.getExitReason()).isEqualTo("STALE_CLEANUP_STARTUP");
            assertThat(trade.getExitTime()).isNotNull();
            verify(tradeRepository).save(trade);
            assertThat(report).hasSize(1);
            assertThat(report.get(0)).contains("CANCELLED").contains("無持倉且無掛單");
        }

        @Test
        @DisplayName("Binance 無持倉 + 有未成交掛單 → 保留 OPEN（不清理）")
        void noPositionButHasOrders_keepOpen() {
            Trade trade = createOpenTrade("trade-3", "BTCUSDT", "LONG");
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(binanceFuturesService.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.0);
            when(binanceFuturesService.hasOpenEntryOrders("BTCUSDT")).thenReturn(true);

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isZero();
            assertThat(trade.getStatus()).isEqualTo("OPEN");
            verify(tradeRepository, never()).save(any());
            assertThat(report).hasSize(1);
            assertThat(report.get(0)).contains("未成交掛單").contains("保留 OPEN");
        }

        @Test
        @DisplayName("Binance 無持倉 + 查詢掛單失敗 → 保守跳過（不清理）")
        void noPositionOrderQueryFails_skipConservatively() {
            Trade trade = createOpenTrade("trade-4", "SOLUSDT", "LONG");
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(binanceFuturesService.getCurrentPositionAmount("SOLUSDT")).thenReturn(0.0);
            when(binanceFuturesService.hasOpenEntryOrders("SOLUSDT"))
                    .thenThrow(new RuntimeException("API error"));

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isZero();
            assertThat(trade.getStatus()).isEqualTo("OPEN");
            verify(tradeRepository, never()).save(any());
            assertThat(report).hasSize(1);
            assertThat(report.get(0)).contains("查詢掛單失敗").contains("保守跳過");
        }

        @Test
        @DisplayName("查詢 Binance 持倉失敗 → 跳過不處理")
        void positionQueryFails_skip() {
            Trade trade = createOpenTrade("trade-5", "XRPUSDT", "SHORT");
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(binanceFuturesService.getCurrentPositionAmount("XRPUSDT"))
                    .thenThrow(new RuntimeException("Network error"));

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isZero();
            assertThat(trade.getStatus()).isEqualTo("OPEN");
            verify(tradeRepository, never()).save(any());
            assertThat(report).hasSize(1);
            assertThat(report.get(0)).contains("查詢失敗").contains("跳過");
        }

        @Test
        @DisplayName("多筆 OPEN 交易 — 混合場景：一筆清理一筆保留")
        void multipleOpenTrades_mixedScenarios() {
            Trade zombieTrade = createOpenTrade("trade-6", "ETHUSDT", "LONG");
            Trade liveTrade = createOpenTrade("trade-7", "BTCUSDT", "SHORT");
            Trade pendingTrade = createOpenTrade("trade-8", "SOLUSDT", "LONG");

            when(tradeRepository.findByStatus("OPEN"))
                    .thenReturn(List.of(zombieTrade, liveTrade, pendingTrade));

            // ETHUSDT: 無持倉+無掛單 → 清理
            when(binanceFuturesService.getCurrentPositionAmount("ETHUSDT")).thenReturn(0.0);
            when(binanceFuturesService.hasOpenEntryOrders("ETHUSDT")).thenReturn(false);

            // BTCUSDT: 有持倉 → 保留
            when(binanceFuturesService.getCurrentPositionAmount("BTCUSDT")).thenReturn(-0.5);

            // SOLUSDT: 無持倉+有掛單 → 保留
            when(binanceFuturesService.getCurrentPositionAmount("SOLUSDT")).thenReturn(0.0);
            when(binanceFuturesService.hasOpenEntryOrders("SOLUSDT")).thenReturn(true);

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isEqualTo(1);
            assertThat(zombieTrade.getStatus()).isEqualTo("CANCELLED");
            assertThat(liveTrade.getStatus()).isEqualTo("OPEN");
            assertThat(pendingTrade.getStatus()).isEqualTo("OPEN");
            verify(tradeRepository, times(1)).save(zombieTrade);
        }
    }

    // ==================== 多用戶模式 ====================

    @Nested
    @DisplayName("多用戶模式對帳")
    class MultiUserReconciliationTests {

        @BeforeEach
        void enableMultiUser() {
            multiUserConfig.setEnabled(true);
        }

        @Test
        @DisplayName("按 userId 分組 — 每個用戶用自己的 API Key")
        void groupsByUserIdAndUsesPerUserApiKey() {
            Trade tradeA = createOpenTradeWithUser("trade-a", "BTCUSDT", "LONG", "user-a");
            Trade tradeB = createOpenTradeWithUser("trade-b", "ETHUSDT", "SHORT", "user-b");

            when(tradeRepository.findByStatus("PENDING_CLOSE")).thenReturn(List.of());
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(tradeA, tradeB));

            when(userApiKeyService.getUserBinanceKeys("user-a"))
                    .thenReturn(Optional.of(new BinanceKeys("key-a", "secret-a")));
            when(userApiKeyService.getUserBinanceKeys("user-b"))
                    .thenReturn(Optional.of(new BinanceKeys("key-b", "secret-b")));

            // 兩個用戶都有持倉 → 不清理
            when(binanceFuturesService.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.01);
            when(binanceFuturesService.getCurrentPositionAmount("ETHUSDT")).thenReturn(-0.1);

            service.reconcileOnStartup();

            // 驗證每個用戶的 API Key 都被設定過
            verify(userApiKeyService).getUserBinanceKeys("user-a");
            verify(userApiKeyService).getUserBinanceKeys("user-b");
            // 沒有清理
            verify(tradeRepository, never()).save(any());
        }

        @Test
        @DisplayName("用戶無 API Key — 跳過該用戶")
        void skipsUserWithoutApiKey() {
            Trade trade = createOpenTradeWithUser("trade-1", "BTCUSDT", "LONG", "user-no-key");

            when(tradeRepository.findByStatus("PENDING_CLOSE")).thenReturn(List.of());
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(userApiKeyService.getUserBinanceKeys("user-no-key"))
                    .thenReturn(Optional.empty());

            service.reconcileOnStartup();

            // 未查詢 Binance（因為沒有 API Key）
            verify(binanceFuturesService, never()).getCurrentPositionAmount(any());
        }

        @Test
        @DisplayName("多用戶清理 — 發送 per-user 通知 + admin 摘要（不重複呼叫 sendNotification）")
        void sendsPerUserAndAdminNotification() {
            Trade trade = createOpenTradeWithUser("trade-z", "ETHUSDT", "SHORT", "user-a");

            when(tradeRepository.findByStatus("PENDING_CLOSE")).thenReturn(List.of());
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(userApiKeyService.getUserBinanceKeys("user-a"))
                    .thenReturn(Optional.of(new BinanceKeys("key-a", "secret-a")));
            when(binanceFuturesService.getCurrentPositionAmount("ETHUSDT")).thenReturn(0.0);
            when(binanceFuturesService.hasOpenEntryOrders("ETHUSDT")).thenReturn(false);

            service.reconcileOnStartup();

            // per-user 通知
            verify(discordWebhookService).sendNotificationToUser(
                    eq("user-a"),
                    eq("🔄 啟動對帳完成"),
                    anyString(),
                    eq(DiscordWebhookService.COLOR_BLUE));
            // Admin 摘要（透過 MQ Consumer 派發到 admin per-user）
            verify(discordWebhookService).sendNotificationToAdmins(
                    eq("🔄 啟動對帳完成"),
                    anyString(),
                    eq(DiscordWebhookService.COLOR_BLUE));
            // 不再額外呼叫 sendNotification（避免 MQ Consumer 重複派發）
            verify(discordWebhookService, never()).sendNotification(
                    eq("🔄 啟動對帳完成"), anyString(), anyInt());
        }

        @Test
        @DisplayName("一個用戶失敗 — 不影響其他用戶")
        void oneUserFailureDoesNotAffectOthers() {
            Trade tradeA = createOpenTradeWithUser("trade-a", "BTCUSDT", "LONG", "user-a");
            Trade tradeB = createOpenTradeWithUser("trade-b", "ETHUSDT", "SHORT", "user-b");

            when(tradeRepository.findByStatus("PENDING_CLOSE")).thenReturn(List.of());
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(tradeA, tradeB));

            when(userApiKeyService.getUserBinanceKeys("user-a"))
                    .thenReturn(Optional.of(new BinanceKeys("key-a", "secret-a")));
            when(userApiKeyService.getUserBinanceKeys("user-b"))
                    .thenReturn(Optional.of(new BinanceKeys("key-b", "secret-b")));

            // user-a 查詢失敗
            when(binanceFuturesService.getCurrentPositionAmount("BTCUSDT"))
                    .thenThrow(new RuntimeException("API timeout"));
            // user-b 正常（有持倉 → 不清理）
            when(binanceFuturesService.getCurrentPositionAmount("ETHUSDT")).thenReturn(-0.1);

            // 不應拋出例外
            assertThatCode(() -> service.reconcileOnStartup()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("無待對帳交易 — 直接跳過不查用戶 API Key")
        void noTradesSkipsApiKeyLookup() {
            when(tradeRepository.findByStatus("PENDING_CLOSE")).thenReturn(List.of());
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of());

            service.reconcileOnStartup();

            verify(userApiKeyService, never()).getUserBinanceKeys(any());
            verify(binanceFuturesService, never()).getCurrentPositionAmount(any());
        }

        @Test
        @DisplayName("userId 為 null 的 Trade — 跳過不處理")
        void nullUserIdTradesSkipped() {
            Trade trade = createOpenTrade("trade-null", "BTCUSDT", "LONG");
            // userId 為 null

            when(tradeRepository.findByStatus("PENDING_CLOSE")).thenReturn(List.of());
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));

            service.reconcileOnStartup();

            // 沒有 userId → 不會去查 API Key
            verify(userApiKeyService, never()).getUserBinanceKeys(any());
        }
    }

    // ==================== 帶 Trade list 參數的重載方法 ====================

    @Nested
    @DisplayName("reconcileZombieOpenTrades(report, trades) — 接收外部 Trade list")
    class OverloadedMethodTests {

        @Test
        @DisplayName("傳入空 list → 直接回傳 0")
        void emptyListReturnsZero() {
            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report, List.of());

            assertThat(result).isZero();
            verify(binanceFuturesService, never()).getCurrentPositionAmount(any());
        }

        @Test
        @DisplayName("傳入指定 Trade list — 只處理該 list")
        void processesOnlyProvidedTrades() {
            Trade trade = createOpenTrade("trade-x", "SOLUSDT", "LONG");
            when(binanceFuturesService.getCurrentPositionAmount("SOLUSDT")).thenReturn(0.0);
            when(binanceFuturesService.hasOpenEntryOrders("SOLUSDT")).thenReturn(false);

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report, List.of(trade));

            assertThat(result).isEqualTo(1);
            assertThat(trade.getStatus()).isEqualTo("CANCELLED");
            // 不應查詢 tradeRepository（直接用傳入的 list）
            verify(tradeRepository, never()).findByStatus(any());
        }
    }

    // ==================== Admin 通知 ====================

    @Nested
    @DisplayName("Admin 通知 — 啟動對帳失敗/完成")
    class AdminNotificationTests {

        @Test
        @DisplayName("對帳失敗 — 發送 admin 告警（不重複呼叫 sendNotification）")
        void failureSendsAdminNotification() {
            // 讓 findByStatus 拋異常 → 觸發 catch block
            when(tradeRepository.findByStatus("PENDING_CLOSE"))
                    .thenThrow(new RuntimeException("DB connection refused"));

            service.reconcileOnStartup();

            // 只呼叫 sendNotificationToAdmins（MQ Consumer 派發到 admin per-user）
            verify(discordWebhookService).sendNotificationToAdmins(
                    eq("⚠️ 啟動對帳失敗"),
                    contains("DB connection refused"),
                    eq(DiscordWebhookService.COLOR_YELLOW));
            // 不再額外呼叫 sendNotification
            verify(discordWebhookService, never()).sendNotification(
                    eq("⚠️ 啟動對帳失敗"), anyString(), anyInt());
        }

        @Test
        @DisplayName("單人模式對帳有修復 — 發送全局通知（admin 不額外通知）")
        void singleUserReconcileOnlySendsGlobal() {
            // 單人模式: multiUserConfig.enabled = false
            Trade trade = createOpenTrade("trade-x", "BTCUSDT", "LONG");
            when(tradeRepository.findByStatus("PENDING_CLOSE")).thenReturn(List.of());
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(binanceFuturesService.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.0);
            when(binanceFuturesService.hasOpenEntryOrders("BTCUSDT")).thenReturn(false);

            service.reconcileOnStartup();

            // 單人模式只有全局通知，不額外呼叫 sendNotificationToAdmins
            verify(discordWebhookService).sendNotification(
                    eq("🔄 啟動對帳完成"),
                    anyString(),
                    eq(DiscordWebhookService.COLOR_BLUE));
            verify(discordWebhookService, never()).sendNotificationToAdmins(
                    eq("🔄 啟動對帳完成"), anyString(), anyInt());
        }
    }

    // ==================== Helper ====================

    private Trade createOpenTrade(String tradeId, String symbol, String side) {
        Trade trade = new Trade();
        trade.setTradeId(tradeId);
        trade.setSymbol(symbol);
        trade.setSide(side);
        trade.setStatus("OPEN");
        return trade;
    }

    private Trade createOpenTradeWithUser(String tradeId, String symbol, String side, String userId) {
        Trade trade = createOpenTrade(tradeId, symbol, side);
        trade.setUserId(userId);
        return trade;
    }
}
