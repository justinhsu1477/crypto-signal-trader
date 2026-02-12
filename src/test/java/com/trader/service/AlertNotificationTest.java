package com.trader.service;

import com.trader.config.BinanceConfig;
import com.trader.config.RiskConfig;
import com.trader.model.OrderResult;
import com.trader.model.TradeSignal;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 告警通知測試 — 確保 TP 失敗和連線中斷時發送 Discord 通知。
 */
class AlertNotificationTest {

    private RiskConfig riskConfig;

    @BeforeEach
    void setUp() {
        riskConfig = new RiskConfig(
                100, 10, 10, 5, 3.0, 3.0, true,
                500.0, 1, 20, List.of("BTCUSDT", "ETHUSDT")
        );
    }

    @Nested
    @DisplayName("TP 失敗告警")
    class TpFailureAlerts {

        @Test
        @DisplayName("ENTRY 流程 — TP 失敗應發送 Discord 黃色告警")
        void entryTpFailureSendsYellowAlert() {
            TradeRecordService mockTradeRecord = mock(TradeRecordService.class);
            SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
            DiscordWebhookService mockWebhook = mock(DiscordWebhookService.class);

            when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(0.0);
            when(mockDedup.isDuplicate(any())).thenReturn(false);
            when(mockDedup.generateHash(any())).thenReturn("testhash");

            BinanceFuturesService service = spy(new BinanceFuturesService(
                    null, null, riskConfig, mockTradeRecord, mockDedup, mockWebhook));

            // 所有前置檢查通過
            doReturn(0.0).when(service).getCurrentPositionAmount(anyString());
            doReturn(0).when(service).getActivePositionCount();
            doReturn(false).when(service).hasOpenEntryOrders(anyString());
            doReturn(95000.0).when(service).getMarkPrice(anyString());

            // 設定槓桿和保證金成功（不拋異常）
            doReturn("{}").when(service).setLeverage(anyString(), anyInt());
            doReturn("{}").when(service).setMarginType(anyString(), anyString());

            // 入場單和止損單成功
            OrderResult entryOk = OrderResult.builder()
                    .success(true).orderId("123").symbol("BTCUSDT").side("BUY")
                    .type("LIMIT").price(95000).quantity(0.25).build();
            OrderResult slOk = OrderResult.builder()
                    .success(true).orderId("124").symbol("BTCUSDT").side("SELL")
                    .type("STOP_MARKET").price(93000).quantity(0.25).build();
            // TP 失敗
            OrderResult tpFail = OrderResult.fail("TP order rejected by exchange");

            doReturn(entryOk).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(slOk).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(tpFail).when(service).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .entryPriceLow(95000)
                    .stopLoss(93000)
                    .takeProfits(List.of(97000.0))
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            // 入場和止損成功
            assertThat(results).hasSizeGreaterThanOrEqualTo(2);
            assertThat(results.get(0).isSuccess()).isTrue();
            assertThat(results.get(1).isSuccess()).isTrue();

            // 應發送 TP 失敗黃色告警
            verify(mockWebhook).sendNotification(
                    contains("止盈單失敗"),
                    contains("請手動設定 TP"),
                    eq(DiscordWebhookService.COLOR_YELLOW));
        }

        @Test
        @DisplayName("MOVE_SL 流程 — TP 失敗應發送 Discord 黃色告警")
        void moveSLTpFailureSendsYellowAlert() {
            TradeRecordService mockTradeRecord = mock(TradeRecordService.class);
            DiscordWebhookService mockWebhook = mock(DiscordWebhookService.class);

            BinanceFuturesService service = spy(new BinanceFuturesService(
                    null, null, riskConfig, mockTradeRecord, null, mockWebhook));

            // 有持倉
            doReturn(0.25).when(service).getCurrentPositionAmount(anyString());
            // 取消掛單成功
            doReturn("{}").when(service).cancelAllOrders(anyString());
            // 查詢舊 SL
            when(mockTradeRecord.findOpenTrade(anyString())).thenReturn(Optional.empty());

            // 新 SL 成功
            OrderResult slOk = OrderResult.builder()
                    .success(true).orderId("200").symbol("BTCUSDT").side("SELL")
                    .type("STOP_MARKET").price(94000).quantity(0.25).build();
            doReturn(slOk).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());

            // 新 TP 失敗
            OrderResult tpFail = OrderResult.fail("TP error");
            doReturn(tpFail).when(service).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .newStopLoss(94000.0)
                    .takeProfits(List.of(98000.0))
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .build();

            List<OrderResult> results = service.executeMoveSL(signal);

            // SL 成功，TP 失敗
            assertThat(results).hasSize(2);
            assertThat(results.get(0).isSuccess()).isTrue();   // SL
            assertThat(results.get(1).isSuccess()).isFalse();  // TP

            // 應發送 TP 失敗黃色告警
            verify(mockWebhook).sendNotification(
                    contains("止盈單失敗"),
                    contains("請手動設定 TP"),
                    eq(DiscordWebhookService.COLOR_YELLOW));
        }
    }

    @Nested
    @DisplayName("Binance 連線中斷告警")
    class ConnectionFailureAlerts {

        @Test
        @DisplayName("IOException 應觸發 Discord 紅色連線中斷告警")
        void ioExceptionTriggersConnectionAlert() throws Exception {
            DiscordWebhookService mockWebhook = mock(DiscordWebhookService.class);
            OkHttpClient mockHttpClient = mock(OkHttpClient.class);
            Call mockCall = mock(Call.class);

            when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
            when(mockCall.execute()).thenThrow(new IOException("Connection refused"));

            BinanceConfig config = new BinanceConfig("https://fapi.binance.com", "key", "secret");
            BinanceFuturesService service = new BinanceFuturesService(
                    mockHttpClient, config, riskConfig, null, null, mockWebhook);

            // getExchangeInfo 會呼叫 executeRequest → IOException
            assertThatThrownBy(() -> service.getExchangeInfo())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Binance API request failed");

            // 應發送連線中斷紅色告警
            verify(mockWebhook).sendNotification(
                    contains("連線中斷"),
                    contains("API 無法連線"),
                    eq(DiscordWebhookService.COLOR_RED));
        }

        @Test
        @DisplayName("HTTP 非 200 回應不應觸發連線中斷告警（非 IOException）")
        void httpErrorDoesNotTriggerConnectionAlert() throws Exception {
            DiscordWebhookService mockWebhook = mock(DiscordWebhookService.class);
            OkHttpClient mockHttpClient = mock(OkHttpClient.class);
            Call mockCall = mock(Call.class);

            // 模擬 Binance 回 HTTP 400（非連線問題）
            Response mockResponse = new Response.Builder()
                    .request(new Request.Builder().url("https://fapi.binance.com/fapi/v1/exchangeInfo").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .body(ResponseBody.create("{\"code\":-1100,\"msg\":\"Illegal characters\"}", MediaType.get("application/json")))
                    .build();

            when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);
            when(mockCall.execute()).thenReturn(mockResponse);

            BinanceConfig config = new BinanceConfig("https://fapi.binance.com", "key", "secret");
            BinanceFuturesService service = new BinanceFuturesService(
                    mockHttpClient, config, riskConfig, null, null, mockWebhook);

            // 呼叫不應拋異常（HTTP 回應正常接收，只是非 200）
            String result = service.getExchangeInfo();

            // 不應觸發連線中斷告警
            verify(mockWebhook, never()).sendNotification(
                    contains("連線中斷"), anyString(), anyInt());
        }
    }

}
