package com.trader.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.config.BinanceConfig;
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
    private BinanceUserDataStreamService service;
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() {
        httpClient = mock(OkHttpClient.class);
        binanceConfig = mock(BinanceConfig.class);
        tradeRecordService = mock(TradeRecordService.class);
        discordWebhookService = mock(DiscordWebhookService.class);

        // Mock wsClient builder chain
        OkHttpClient.Builder mockBuilder = mock(OkHttpClient.Builder.class);
        OkHttpClient mockWsClient = mock(OkHttpClient.class);
        when(httpClient.newBuilder()).thenReturn(mockBuilder);
        when(mockBuilder.readTimeout(anyLong(), any())).thenReturn(mockBuilder);
        when(mockBuilder.pingInterval(anyLong(), any())).thenReturn(mockBuilder);
        when(mockBuilder.build()).thenReturn(mockWsClient);

        service = new BinanceUserDataStreamService(
                httpClient, binanceConfig, tradeRecordService, discordWebhookService);
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

    // ==================== 重連機制 ====================

    @Nested
    @DisplayName("重連邏輯")
    class ReconnectLogic {

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
