package com.trader.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.shared.config.BinanceConfig;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.service.BinanceUserDataStreamService;
import com.trader.trading.service.MultiUserDataStreamManager;
import com.trader.trading.service.SymbolLockRegistry;
import com.trader.trading.service.TradeRecordService;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.*;
import org.mockito.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BinanceUserDataStreamService 測試
 * 驗證 WebSocket 事件處理、重連邏輯、狀態查詢
 */
class BinanceUserDataStreamServiceTest {

    private OkHttpClient httpClient;
    private BinanceConfig binanceConfig;
    private TradeRecordService tradeRecordService;
    private DiscordWebhookService discordWebhookService;
    private MultiUserConfig multiUserConfig;
    private MultiUserDataStreamManager multiUserManager;
    private BinanceUserDataStreamService service;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        httpClient = mock(OkHttpClient.class);
        binanceConfig = mock(BinanceConfig.class);
        tradeRecordService = mock(TradeRecordService.class);
        discordWebhookService = mock(DiscordWebhookService.class);
        multiUserConfig = mock(MultiUserConfig.class);
        multiUserManager = mock(MultiUserDataStreamManager.class);

        // 預設單用戶模式（所有舊測試不受影響）
        when(multiUserConfig.isEnabled()).thenReturn(false);

        // Mock wsClient builder chain
        OkHttpClient.Builder mockBuilder = mock(OkHttpClient.Builder.class);
        OkHttpClient mockWsClient = mock(OkHttpClient.class);
        when(httpClient.newBuilder()).thenReturn(mockBuilder);
        when(mockBuilder.readTimeout(anyLong(), any())).thenReturn(mockBuilder);
        when(mockBuilder.pingInterval(anyLong(), any())).thenReturn(mockBuilder);
        when(mockBuilder.build()).thenReturn(mockWsClient);

        service = new BinanceUserDataStreamService(
                httpClient, binanceConfig, tradeRecordService, discordWebhookService,
                new SymbolLockRegistry(), multiUserConfig, multiUserManager);
    }

    // ==================== 事件處理 ====================

    @Nested
    @DisplayName("ORDER_TRADE_UPDATE 事件處理")
    class OrderTradeUpdate {

        @Test
        @DisplayName("STOP_MARKET FILLED → 呼叫 recordCloseFromStream('SL_TRIGGERED')")
        void stopMarketFilledTriggersSlClose() {
            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 123456789L, 1700000000000L);

            service.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordCloseFromStream(
                    eq("BTCUSDT"), eq(93000.0), eq(0.5),
                    eq(18.6), eq(-1000.0),
                    eq("123456789"), eq("SL_TRIGGERED"),
                    eq(1700000000000L));

            // 應發 Discord 通知（止損紅色）
            verify(discordWebhookService).sendNotification(
                    contains("止損觸發"),
                    contains("BTCUSDT"),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("TAKE_PROFIT_MARKET FILLED → 呼叫 recordCloseFromStream('TP_TRIGGERED')")
        void takeProfitMarketFilledTriggersTpClose() {
            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "TAKE_PROFIT_MARKET", "FILLED", "SELL",
                    98000.0, 0.5, 19.6, "USDT", 1500.0, 987654321L, 1700000000000L);

            service.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordCloseFromStream(
                    eq("BTCUSDT"), eq(98000.0), eq(0.5),
                    eq(19.6), eq(1500.0),
                    eq("987654321"), eq("TP_TRIGGERED"),
                    eq(1700000000000L));

            // 應發 Discord 通知（止盈綠色）
            verify(discordWebhookService).sendNotification(
                    contains("止盈觸發"),
                    contains("BTCUSDT"),
                    eq(DiscordWebhookService.COLOR_GREEN));
        }

        @Test
        @DisplayName("LIMIT FILLED → 不呼叫 recordCloseFromStream（入場單由 executeSignal 處理）")
        void limitFilledDoesNotTriggerClose() {
            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "LIMIT", "FILLED", "BUY",
                    95000.0, 0.5, 9.5, "USDT", 0.0, 111222333L, 1700000000000L);

            service.handleOrderTradeUpdate(event);

            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("STOP_MARKET NEW → 忽略（非 FILLED 狀態）")
        void stopMarketNewIgnored() {
            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "NEW", "SELL",
                    93000.0, 0.5, 0.0, "USDT", 0.0, 444555666L, 1700000000000L);

            service.handleOrderTradeUpdate(event);

            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }

        @Test
        @DisplayName("非 USDT 手續費 → fallback 估算值 (avgPrice × qty × 0.04%)")
        void nonUsdtCommissionFallback() {
            // 手續費幣種 BNB 而非 USDT
            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 0.01, "BNB", -1000.0, 777888999L, 1700000000000L);

            service.handleOrderTradeUpdate(event);

            // 估算手續費 = 93000 × 0.5 × 0.0004 = 18.6
            double expectedCommission = 93000.0 * 0.5 * 0.0004;
            verify(tradeRecordService).recordCloseFromStream(
                    eq("BTCUSDT"), eq(93000.0), eq(0.5),
                    eq(expectedCommission), eq(-1000.0),
                    eq("777888999"), eq("SL_TRIGGERED"),
                    eq(1700000000000L));
        }

        @Test
        @DisplayName("缺少 'o' 欄位 → 安全忽略，不拋異常")
        void missingOrderFieldIgnored() {
            JsonObject event = new JsonObject();
            event.addProperty("e", "ORDER_TRADE_UPDATE");
            // 沒有 "o" 欄位

            assertThatCode(() -> service.handleOrderTradeUpdate(event))
                    .doesNotThrowAnyException();

            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());
        }
    }

    // ==================== 狀態查詢 ====================

    @Nested
    @DisplayName("狀態查詢")
    class StatusQuery {

        @Test
        @DisplayName("初始狀態: connected=false, listenKeyActive=false")
        void initialStatusDisconnected() {
            var status = service.getStatus();

            assertThat(status.get("connected")).isEqualTo(false);
            assertThat(status.get("listenKeyActive")).isEqualTo(false);
            assertThat(status.get("lastMessageTime")).isEqualTo("never");
            assertThat(status.get("reconnectAttempts")).isEqualTo(0);
        }
    }

    // ==================== SL/TP 保護消失偵測 ====================

    @Nested
    @DisplayName("SL/TP 被取消或過期")
    class ProtectionLost {

        @Test
        @DisplayName("STOP_MARKET CANCELED → 紅色告警 + recordProtectionLost")
        void slCanceledTriggersRedAlert() {
            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "CANCELED", "SELL",
                    0.0, 0.0, 0.0, "USDT", 0.0, 555666777L, 1700000000000L);

            service.handleOrderTradeUpdate(event);

            // 不應觸發平倉記錄
            verify(tradeRecordService, never()).recordCloseFromStream(
                    anyString(), anyDouble(), anyDouble(),
                    anyDouble(), anyDouble(),
                    anyString(), anyString(), anyLong());

            // 應記錄保護消失事件
            verify(tradeRecordService).recordProtectionLost(
                    eq("BTCUSDT"), eq("STOP_MARKET"), eq("555666777"), eq("CANCELED"));

            // SL 被取消 → 紅色告警（高危）
            verify(discordWebhookService).sendNotification(
                    contains("止損單被取消"),
                    contains("持倉已失去止損保護"),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("TAKE_PROFIT_MARKET CANCELED → 黃色告警 + recordProtectionLost")
        void tpCanceledTriggersYellowAlert() {
            JsonObject event = buildOrderTradeUpdate(
                    "ETHUSDT", "TAKE_PROFIT_MARKET", "CANCELED", "BUY",
                    0.0, 0.0, 0.0, "USDT", 0.0, 888999000L, 1700000000000L);

            service.handleOrderTradeUpdate(event);

            // 應記錄保護消失事件
            verify(tradeRecordService).recordProtectionLost(
                    eq("ETHUSDT"), eq("TAKE_PROFIT_MARKET"), eq("888999000"), eq("CANCELED"));

            // TP 被取消 → 黃色告警（較不緊急）
            verify(discordWebhookService).sendNotification(
                    contains("止盈單被取消"),
                    contains("止損仍有效"),
                    eq(DiscordWebhookService.COLOR_YELLOW));
        }

        @Test
        @DisplayName("STOP_MARKET EXPIRED → 紅色告警（與 CANCELED 同等處理）")
        void slExpiredTriggersRedAlert() {
            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "EXPIRED", "SELL",
                    0.0, 0.0, 0.0, "USDT", 0.0, 111222333L, 1700000000000L);

            service.handleOrderTradeUpdate(event);

            verify(tradeRecordService).recordProtectionLost(
                    eq("BTCUSDT"), eq("STOP_MARKET"), eq("111222333"), eq("EXPIRED"));

            verify(discordWebhookService).sendNotification(
                    contains("止損單被取消"),
                    contains("持倉已失去止損保護"),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("LIMIT CANCELED → 不觸發保護消失告警（入場單取消是正常操作）")
        void limitCanceledIgnored() {
            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "LIMIT", "CANCELED", "BUY",
                    0.0, 0.0, 0.0, "USDT", 0.0, 444555666L, 1700000000000L);

            service.handleOrderTradeUpdate(event);

            verify(tradeRecordService, never()).recordProtectionLost(
                    anyString(), anyString(), anyString(), anyString());

            // 不應有任何 Discord 通知
            verify(discordWebhookService, never()).sendNotification(
                    anyString(), anyString(), anyInt());
        }
    }

    // ==================== 錯誤處理 ====================

    @Nested
    @DisplayName("錯誤處理")
    class ErrorHandling {

        @Test
        @DisplayName("recordCloseFromStream 失敗時發送黃色 Discord 告警")
        void streamCloseFailureSendsWarning() {
            doThrow(new RuntimeException("DB error"))
                    .when(tradeRecordService).recordCloseFromStream(
                            anyString(), anyDouble(), anyDouble(),
                            anyDouble(), anyDouble(),
                            anyString(), anyString(), anyLong());

            JsonObject event = buildOrderTradeUpdate(
                    "BTCUSDT", "STOP_MARKET", "FILLED", "SELL",
                    93000.0, 0.5, 18.6, "USDT", -1000.0, 123456789L, 1700000000000L);

            service.handleOrderTradeUpdate(event);

            // 應發黃色告警
            verify(discordWebhookService).sendNotification(
                    contains("平倉記錄失敗"),
                    contains("DB error"),
                    eq(DiscordWebhookService.COLOR_YELLOW));
        }
    }

    // ==================== 多用戶模式委派 ====================

    @Nested
    @DisplayName("多用戶模式委派")
    class MultiUserDelegation {

        @Test
        @DisplayName("init 時 multiUser enabled → 委派給 manager.startAllStreams")
        void initDelegatesToManagerWhenMultiUserEnabled() {
            when(multiUserConfig.isEnabled()).thenReturn(true);
            when(binanceConfig.getWsBaseUrl()).thenReturn("wss://test.com/ws/");

            service.init();

            verify(multiUserManager).startAllStreams();
        }

        @Test
        @DisplayName("init 時 multiUser disabled → 不呼叫 manager")
        void initDoesNotDelegateWhenSingleUser() {
            when(multiUserConfig.isEnabled()).thenReturn(false);
            // wsBaseUrl 為 null → 直接 return，不會嘗試連線也不會呼叫 manager
            when(binanceConfig.getWsBaseUrl()).thenReturn(null);

            service.init();

            verify(multiUserManager, never()).startAllStreams();
        }

        @Test
        @DisplayName("shutdown 時 multiUser enabled → 委派給 manager.stopAllStreams")
        void shutdownDelegatesToManager() {
            when(multiUserConfig.isEnabled()).thenReturn(true);

            service.shutdown();

            verify(multiUserManager).stopAllStreams();
        }

        @Test
        @DisplayName("keepAliveListenKey 時 multiUser enabled → 委派給 manager.keepAliveAll")
        void keepAliveDelegatesToManager() {
            when(multiUserConfig.isEnabled()).thenReturn(true);

            service.keepAliveListenKey();

            verify(multiUserManager).keepAliveAll();
        }

        @Test
        @DisplayName("getStatus 時 multiUser enabled → 委派給 manager.getAllStatus")
        void getStatusDelegatesToManager() {
            when(multiUserConfig.isEnabled()).thenReturn(true);
            when(multiUserManager.getAllStatus()).thenReturn(
                    java.util.Map.of("mode", "multi-user", "totalStreams", 3));

            var status = service.getStatus();

            assertThat(status.get("mode")).isEqualTo("multi-user");
            assertThat(status.get("totalStreams")).isEqualTo(3);
            verify(multiUserManager).getAllStatus();
        }

        @Test
        @DisplayName("getStatus 時 multiUser disabled → 返回單用戶格式")
        void getStatusReturnsSingleUserFormat() {
            when(multiUserConfig.isEnabled()).thenReturn(false);

            var status = service.getStatus();

            assertThat(status.get("mode")).isEqualTo("single-user");
            assertThat(status).containsKey("connected");
            assertThat(status).containsKey("listenKeyActive");
            verify(multiUserManager, never()).getAllStatus();
        }
    }

    // ==================== 輔助方法 ====================

    /**
     * 構建 ORDER_TRADE_UPDATE 事件 JSON
     */
    private JsonObject buildOrderTradeUpdate(String symbol, String orderType, String orderStatus,
                                              String side, double avgPrice, double filledQty,
                                              double commission, String commissionAsset,
                                              double realizedProfit, long orderId,
                                              long transactionTime) {
        JsonObject order = new JsonObject();
        order.addProperty("s", symbol);
        order.addProperty("o", orderType);
        order.addProperty("X", orderStatus);
        order.addProperty("S", side);
        order.addProperty("ap", avgPrice);
        order.addProperty("z", filledQty);
        order.addProperty("n", commission);
        order.addProperty("N", commissionAsset);
        order.addProperty("rp", realizedProfit);
        order.addProperty("i", orderId);
        order.addProperty("T", transactionTime);

        JsonObject event = new JsonObject();
        event.addProperty("e", "ORDER_TRADE_UPDATE");
        event.add("o", order);

        return event;
    }
}
