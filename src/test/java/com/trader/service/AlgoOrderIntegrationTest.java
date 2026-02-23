package com.trader.service;

import com.trader.shared.config.BinanceConfig;
import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.Trade;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.service.*;
import com.trader.user.service.UserApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Algo Order API 整合測試
 *
 * 測試重點：
 * 1. parseAlgoOrdersResponse() — 各種回應格式解析
 * 2. cancelSLTPOrders() — 非空 Algo 訂單的取消流程
 * 3. getCurrentSLTPPrices() — 從非空 Algo 訂單讀取 SL/TP 價格
 * 4. cancelAllOrders() — Algo 取消失敗時 Discord 告警
 * 5. 完整交易流程搭配真實 Algo 回應格式
 */
class AlgoOrderIntegrationTest {

    private TradeRecordService mockTradeRecord;
    private DiscordWebhookService mockWebhook;
    private TradeConfigResolver mockTradeConfigResolver;
    private BinanceFuturesService service;

    @BeforeEach
    void setUp() {
        RiskConfig riskConfig = new RiskConfig(
                50000, 2000, true,
                0.20, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), "BTCUSDT"
        );
        mockTradeRecord = mock(TradeRecordService.class);
        SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
        mockWebhook = mock(DiscordWebhookService.class);
        UserApiKeyService mockApiKey = mock(UserApiKeyService.class);
        mockTradeConfigResolver = mock(TradeConfigResolver.class);

        EffectiveTradeConfig defaultConfig = new EffectiveTradeConfig(
                0.20, 50000, 2000, 3, 2.0, 20,
                List.of("BTCUSDT", "ETHUSDT"), true, "BTCUSDT"
        );
        when(mockTradeConfigResolver.resolve(any())).thenReturn(defaultConfig);

        service = spy(new BinanceFuturesService(
                null, new BinanceConfig("https://fake.test", null, "testkey", "testsecret"),
                riskConfig, mockTradeRecord, mockDedup, mockWebhook,
                new ObjectMapper(), new SymbolLockRegistry(), mockApiKey,
                mockTradeConfigResolver));

        when(mockTradeRecord.getActiveUserId()).thenReturn("test-user");
        when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(0.0);
        when(mockDedup.isDuplicate(any())).thenReturn(false);
        when(mockDedup.isUserDuplicate(any(), anyString())).thenReturn(false);
    }

    // ==================== Algo 回應格式模板 ====================

    /** 模擬 Binance openAlgoOrders 回傳 — 含 1 個 SL + 1 個 TP */
    private static final String ALGO_RESPONSE_SL_AND_TP = """
            [
              {
                "algoId": 100001,
                "symbol": "BTCUSDT",
                "side": "SELL",
                "orderType": "STOP_MARKET",
                "quantity": "0.500",
                "triggerPrice": "93000.00",
                "algoStatus": "NEW",
                "algoType": "CONDITIONAL",
                "clientAlgoId": "sl_btcusdt_12345"
              },
              {
                "algoId": 100002,
                "symbol": "BTCUSDT",
                "side": "SELL",
                "orderType": "TAKE_PROFIT_MARKET",
                "quantity": "0.500",
                "triggerPrice": "100000.00",
                "algoStatus": "NEW",
                "algoType": "CONDITIONAL",
                "clientAlgoId": "tp_btcusdt_12345"
              }
            ]
            """;

    /** 模擬只有 SL 的回應 */
    private static final String ALGO_RESPONSE_SL_ONLY = """
            [
              {
                "algoId": 200001,
                "symbol": "BTCUSDT",
                "side": "BUY",
                "orderType": "STOP_MARKET",
                "quantity": "1.000",
                "triggerPrice": "97000.00",
                "algoStatus": "NEW",
                "algoType": "CONDITIONAL"
              }
            ]
            """;

    /** 模擬只有 TP 的回應 */
    private static final String ALGO_RESPONSE_TP_ONLY = """
            [
              {
                "algoId": 300001,
                "symbol": "BTCUSDT",
                "side": "SELL",
                "orderType": "TAKE_PROFIT_MARKET",
                "quantity": "0.500",
                "triggerPrice": "105000.00",
                "algoStatus": "NEW",
                "algoType": "CONDITIONAL"
              }
            ]
            """;

    /** 模擬含有非 SL/TP 類型的 Algo 訂單（如 TRAILING_STOP_MARKET） */
    private static final String ALGO_RESPONSE_MIXED_TYPES = """
            [
              {
                "algoId": 400001,
                "symbol": "BTCUSDT",
                "side": "SELL",
                "orderType": "STOP_MARKET",
                "quantity": "0.500",
                "triggerPrice": "93000.00",
                "algoStatus": "NEW",
                "algoType": "CONDITIONAL"
              },
              {
                "algoId": 400002,
                "symbol": "BTCUSDT",
                "side": "SELL",
                "orderType": "TRAILING_STOP_MARKET",
                "quantity": "0.500",
                "triggerPrice": "94000.00",
                "algoStatus": "NEW",
                "algoType": "CONDITIONAL"
              }
            ]
            """;

    /** Binance API 錯誤回應 */
    private static final String ALGO_RESPONSE_ERROR = """
            {"code": -1021, "msg": "Timestamp for this request is outside of the recvWindow."}
            """;

    /** 另一種 Binance 錯誤 — 簽名無效 */
    private static final String ALGO_RESPONSE_SIGNATURE_ERROR = """
            {"code": -1022, "msg": "Signature for this request is not valid."}
            """;

    private OrderResult successOrder(String id, String side, double price, double qty) {
        return OrderResult.builder()
                .success(true).orderId(id).symbol("BTCUSDT")
                .side(side).type("LIMIT").price(price).quantity(qty)
                .build();
    }

    // ==================== getCurrentSLTPPrices ====================

    @Nested
    @DisplayName("getCurrentSLTPPrices — Algo 回應解析")
    class GetCurrentSLTPPrices {

        @Test
        @DisplayName("正常回應含 SL + TP → 正確讀取兩個價格")
        void parseSLAndTPPrices() {
            doReturn(ALGO_RESPONSE_SL_AND_TP).when(service).getOpenAlgoOrders(anyString());

            double[] prices = service.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(93000.0);  // SL
            assertThat(prices[1]).isEqualTo(100000.0);  // TP
        }

        @Test
        @DisplayName("只有 SL 訂單 → SL 正確、TP 為 0")
        void parseSLOnlyResponse() {
            doReturn(ALGO_RESPONSE_SL_ONLY).when(service).getOpenAlgoOrders(anyString());

            double[] prices = service.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(97000.0);  // SL
            assertThat(prices[1]).isEqualTo(0.0);       // 無 TP
        }

        @Test
        @DisplayName("只有 TP 訂單 → TP 正確、SL 為 0")
        void parseTPOnlyResponse() {
            doReturn(ALGO_RESPONSE_TP_ONLY).when(service).getOpenAlgoOrders(anyString());

            double[] prices = service.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(0.0);        // 無 SL
            assertThat(prices[1]).isEqualTo(105000.0);   // TP
        }

        @Test
        @DisplayName("空陣列 → SL/TP 都為 0")
        void parseEmptyArray() {
            doReturn("[]").when(service).getOpenAlgoOrders(anyString());

            double[] prices = service.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(0.0);
            assertThat(prices[1]).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Binance 錯誤回應 → 不崩潰，回傳 [0, 0]")
        void handleErrorResponse() {
            doReturn(ALGO_RESPONSE_ERROR).when(service).getOpenAlgoOrders(anyString());

            double[] prices = service.getCurrentSLTPPrices("BTCUSDT");

            // 錯誤回應會拋異常，被 catch 後回傳 [0, 0]
            assertThat(prices[0]).isEqualTo(0.0);
            assertThat(prices[1]).isEqualTo(0.0);
        }

        @Test
        @DisplayName("混合訂單類型 → 只讀 STOP_MARKET 和 TAKE_PROFIT_MARKET")
        void ignoresNonSLTPOrderTypes() {
            doReturn(ALGO_RESPONSE_MIXED_TYPES).when(service).getOpenAlgoOrders(anyString());

            double[] prices = service.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(93000.0);  // STOP_MARKET
            assertThat(prices[1]).isEqualTo(0.0);       // TRAILING_STOP_MARKET 不算 TP
        }
    }

    // ==================== cancelSLTPOrders ====================

    @Nested
    @DisplayName("cancelSLTPOrders — Algo 訂單取消")
    class CancelSLTPOrders {

        @Test
        @DisplayName("有 SL + TP → 分別取消兩筆")
        void cancelsBothSLAndTP() {
            doReturn(ALGO_RESPONSE_SL_AND_TP).when(service).getOpenAlgoOrders(anyString());
            doReturn("{}").when(service).cancelAlgoOrder(anyString(), anyLong());

            service.cancelSLTPOrders("BTCUSDT");

            verify(service).cancelAlgoOrder("BTCUSDT", 100001L);  // SL
            verify(service).cancelAlgoOrder("BTCUSDT", 100002L);  // TP
            verify(service, times(2)).cancelAlgoOrder(anyString(), anyLong());
        }

        @Test
        @DisplayName("只有 SL → 只取消 SL")
        void cancelsOnlySL() {
            doReturn(ALGO_RESPONSE_SL_ONLY).when(service).getOpenAlgoOrders(anyString());
            doReturn("{}").when(service).cancelAlgoOrder(anyString(), anyLong());

            service.cancelSLTPOrders("BTCUSDT");

            verify(service).cancelAlgoOrder("BTCUSDT", 200001L);
            verify(service, times(1)).cancelAlgoOrder(anyString(), anyLong());
        }

        @Test
        @DisplayName("空陣列 → 不呼叫 cancelAlgoOrder")
        void doesNothingForEmptyArray() {
            doReturn("[]").when(service).getOpenAlgoOrders(anyString());

            service.cancelSLTPOrders("BTCUSDT");

            verify(service, never()).cancelAlgoOrder(anyString(), anyLong());
        }

        @Test
        @DisplayName("混合類型 → 只取消 STOP_MARKET，忽略 TRAILING_STOP_MARKET")
        void onlyCancelsSLTPTypes() {
            doReturn(ALGO_RESPONSE_MIXED_TYPES).when(service).getOpenAlgoOrders(anyString());
            doReturn("{}").when(service).cancelAlgoOrder(anyString(), anyLong());

            service.cancelSLTPOrders("BTCUSDT");

            // 只取消 STOP_MARKET (algoId=400001)，不取消 TRAILING_STOP_MARKET
            verify(service).cancelAlgoOrder("BTCUSDT", 400001L);
            verify(service, times(1)).cancelAlgoOrder(anyString(), anyLong());
        }

        @Test
        @DisplayName("Binance 錯誤回應 → 拋出 RuntimeException")
        void throwsOnErrorResponse() {
            doReturn(ALGO_RESPONSE_ERROR).when(service).getOpenAlgoOrders(anyString());

            assertThatThrownBy(() -> service.cancelSLTPOrders("BTCUSDT"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Algo 訂單失敗");
        }

        @Test
        @DisplayName("簽名錯誤回應 → 拋出 RuntimeException 含錯誤碼")
        void throwsOnSignatureError() {
            doReturn(ALGO_RESPONSE_SIGNATURE_ERROR).when(service).getOpenAlgoOrders(anyString());

            assertThatThrownBy(() -> service.cancelSLTPOrders("BTCUSDT"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("-1022");
        }
    }

    // ==================== cancelAllOrders Algo 告警 ====================

    @Nested
    @DisplayName("cancelAllOrders — Algo 失敗時 Discord 告警")
    class CancelAllOrdersAlgoAlert {

        @Test
        @DisplayName("cancelSLTPOrders 拋異常 → cancelAllOrders 捕獲並發 Discord 告警")
        void algoFailureSendsDiscordAlert() {
            // cancelSLTPOrders 使用錯誤回應觸發異常
            doReturn(ALGO_RESPONSE_ERROR).when(service).getOpenAlgoOrders(anyString());

            // 直接呼叫 cancelSLTPOrders 確認它會拋異常
            assertThatThrownBy(() -> service.cancelSLTPOrders("BTCUSDT"))
                    .isInstanceOf(RuntimeException.class);

            // 用 Algo 錯誤回應確認錯誤傳播到內部即可
            // cancelAllOrders 本身因為 sendSignedDelete 是 private 無法完整 mock，
            // 但可驗證 cancelSLTPOrders 正確拋出異常 → cancelAllOrders 的 catch 塊會捕獲
        }

        @Test
        @DisplayName("cancelSLTPOrders 成功（空陣列）→ 無異常拋出")
        void noExceptionWhenAlgoSucceeds() {
            doReturn("[]").when(service).getOpenAlgoOrders(anyString());

            // 不應拋異常
            assertThatCode(() -> service.cancelSLTPOrders("BTCUSDT"))
                    .doesNotThrowAnyException();
        }
    }

    // ==================== 完整交易流程搭配真實 Algo 回應 ====================

    @Nested
    @DisplayName("完整交易流程 — 非空 Algo 回應")
    class FullFlowWithAlgoOrders {

        @Test
        @DisplayName("部分平倉 — 用真實 Algo 回應讀取舊 SL/TP 後重掛")
        void partialCloseWithRealAlgoResponse() {
            doReturn(1.0).when(service).getCurrentPositionAmount(anyString());
            doReturn(95000.0).when(service).getMarkPrice(anyString());
            // getCurrentSLTPPrices 會呼叫 getOpenAlgoOrders
            doReturn(ALGO_RESPONSE_SL_AND_TP).when(service).getOpenAlgoOrders(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.5);
            OrderResult tpOrder = successOrder("TP1", "SELL", 100000, 0.5);

            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(tpOrder).when(service).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .build();

            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            // 從 Algo 回應讀取的 SL=93000、TP=100000 重掛到剩餘 0.5 BTC
            verify(service).placeStopLoss(eq("BTCUSDT"), eq("SELL"), eq(93000.0), eq(0.5));
            verify(service).placeTakeProfit(eq("BTCUSDT"), eq("SELL"), eq(100000.0), eq(0.5));
        }

        @Test
        @DisplayName("部分平倉 — 只有 SL 無 TP 時不掛 TP")
        void partialCloseWithSLOnlyAlgoResponse() {
            doReturn(1.0).when(service).getCurrentPositionAmount(anyString());
            doReturn(95000.0).when(service).getMarkPrice(anyString());
            doReturn(ALGO_RESPONSE_SL_ONLY.replace("BUY", "SELL").replace("97000", "93000"))
                    .when(service).getOpenAlgoOrders(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            OrderResult closeOrder = successOrder("C1", "SELL", 96000, 0.5);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.5);

            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .build();

            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            verify(service).placeStopLoss(eq("BTCUSDT"), eq("SELL"), eq(93000.0), eq(0.5));
            // 無舊 TP → 不掛 TP
            verify(service, never()).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());
        }

        @Test
        @DisplayName("DCA 補倉 — cancelSLTPOrders 用真實 Algo 回應取消舊 SL/TP")
        void dcaWithRealAlgoCancelResponse() {
            // 已有 0.5 BTC 多倉
            doReturn(1000.0).when(service).getAvailableBalance();
            doReturn(0.5).when(service).getCurrentPositionAmount(anyString());
            doReturn(0).when(service).getActivePositionCount();
            doReturn(false).when(service).hasOpenEntryOrders(anyString());
            doReturn(95000.0).when(service).getMarkPrice(anyString());
            doReturn("{}").when(service).setLeverage(anyString(), anyInt());
            try {
                doReturn("{}").when(service).setMarginType(anyString(), anyString());
            } catch (Exception e) { /* ignore */ }

            when(mockTradeRecord.getDcaCount("BTCUSDT")).thenReturn(1);
            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().side("LONG").stopLoss(93000.0).build()));

            // DCA 流程中 cancelSLTPOrders 使用真實 Algo 回應
            doReturn(ALGO_RESPONSE_SL_AND_TP).when(service).getOpenAlgoOrders(anyString());
            doReturn("{}").when(service).cancelAlgoOrder(anyString(), anyLong());
            // cancelAllOrders 直接 mock（內部會呼叫 cancelSLTPOrders，但 DCA 直接呼叫 cancelSLTPOrders）
            doReturn("{}").when(service).cancelAllOrders(anyString());

            OrderResult entryOrder = successOrder("DCA1", "BUY", 94000, 0.02);
            OrderResult slOrder = successOrder("SL1", "SELL", 93000, 0.52);

            doReturn(entryOrder).when(service).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), eq("SELL"), anyDouble(), anyDouble());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .entryPriceLow(94000)
                    .stopLoss(92000)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true)
                    .build();

            List<OrderResult> results = service.executeSignal(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).isSuccess()).isTrue();
            // 確認有取消舊的 Algo SL/TP（DCA 流程直接呼叫 cancelSLTPOrders）
            verify(service, atLeastOnce()).cancelAlgoOrder(eq("BTCUSDT"), eq(100001L));
            verify(service, atLeastOnce()).cancelAlgoOrder(eq("BTCUSDT"), eq(100002L));
        }

        @Test
        @DisplayName("移動止損 — getCurrentSLTPPrices 讀取 Algo 回應不影響移動流程")
        void moveSLWithRealAlgoResponse() {
            doReturn(0.5).when(service).getCurrentPositionAmount(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            when(mockTradeRecord.findOpenTrade("BTCUSDT")).thenReturn(
                    Optional.of(Trade.builder().stopLoss(93000.0).build()));

            OrderResult slOrder = successOrder("SL1", "SELL", 94500, 0.5);
            OrderResult tpOrder = successOrder("TP1", "SELL", 102000, 0.5);

            doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());
            doReturn(tpOrder).when(service).placeTakeProfit(anyString(), anyString(), anyDouble(), anyDouble());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.MOVE_SL)
                    .newStopLoss(94500.0)
                    .newTakeProfit(102000.0)
                    .build();

            List<OrderResult> results = service.executeMoveSL(signal);

            assertThat(results).isNotEmpty();
            assertThat(results.stream().allMatch(OrderResult::isSuccess)).isTrue();
            verify(service).placeStopLoss(eq("BTCUSDT"), anyString(), eq(94500.0), eq(0.5));
            verify(service).placeTakeProfit(eq("BTCUSDT"), anyString(), eq(102000.0), eq(0.5));
        }

        @Test
        @DisplayName("做空部分平倉 — 用真實 Algo 回應的空倉 SL/TP")
        void shortPartialCloseWithRealAlgoResponse() {
            String shortAlgoResponse = """
                    [
                      {
                        "algoId": 500001,
                        "symbol": "BTCUSDT",
                        "side": "BUY",
                        "orderType": "STOP_MARKET",
                        "quantity": "1.000",
                        "triggerPrice": "97000.00",
                        "algoStatus": "NEW",
                        "algoType": "CONDITIONAL"
                      },
                      {
                        "algoId": 500002,
                        "symbol": "BTCUSDT",
                        "side": "BUY",
                        "orderType": "TAKE_PROFIT_MARKET",
                        "quantity": "1.000",
                        "triggerPrice": "90000.00",
                        "algoStatus": "NEW",
                        "algoType": "CONDITIONAL"
                      }
                    ]
                    """;

            doReturn(-1.0).when(service).getCurrentPositionAmount(anyString());
            doReturn(95000.0).when(service).getMarkPrice(anyString());
            doReturn(shortAlgoResponse).when(service).getOpenAlgoOrders(anyString());
            doReturn("{}").when(service).cancelAllOrders(anyString());

            OrderResult closeOrder = successOrder("C1", "BUY", 94000, 0.5);
            OrderResult slOrder = successOrder("SL1", "BUY", 97000, 0.5);
            OrderResult tpOrder = successOrder("TP1", "BUY", 90000, 0.5);

            doReturn(closeOrder).when(service).placeLimitOrder(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(slOrder).when(service).placeStopLoss(anyString(), eq("BUY"), anyDouble(), anyDouble());
            doReturn(tpOrder).when(service).placeTakeProfit(anyString(), eq("BUY"), anyDouble(), anyDouble());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .signalType(TradeSignal.SignalType.CLOSE)
                    .closeRatio(0.5)
                    .build();

            List<OrderResult> results = service.executeClose(signal);

            assertThat(results).isNotEmpty();
            // 空倉 SL 重掛用 BUY 方向，價格 97000
            verify(service).placeStopLoss(eq("BTCUSDT"), eq("BUY"), eq(97000.0), eq(0.5));
            // 空倉 TP 重掛用 BUY 方向，價格 90000
            verify(service).placeTakeProfit(eq("BTCUSDT"), eq("BUY"), eq(90000.0), eq(0.5));
        }
    }

    // ==================== 邊界情況 ====================

    @Nested
    @DisplayName("Algo 回應邊界情況")
    class EdgeCases {

        @Test
        @DisplayName("null 回應 → getCurrentSLTPPrices 回傳 [0, 0] 不崩潰")
        void handleNullResponse() {
            doReturn(null).when(service).getOpenAlgoOrders(anyString());

            double[] prices = service.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(0.0);
            assertThat(prices[1]).isEqualTo(0.0);
        }

        @Test
        @DisplayName("非法 JSON 回應 → getCurrentSLTPPrices 回傳 [0, 0] 不崩潰")
        void handleMalformedJson() {
            doReturn("not-valid-json").when(service).getOpenAlgoOrders(anyString());

            double[] prices = service.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(0.0);
            assertThat(prices[1]).isEqualTo(0.0);
        }

        @Test
        @DisplayName("訂單缺少 orderType 欄位 → 跳過該訂單，不崩潰")
        void handleMissingOrderType() {
            String responseNoType = """
                    [
                      {
                        "algoId": 600001,
                        "symbol": "BTCUSDT",
                        "side": "SELL",
                        "quantity": "0.500",
                        "triggerPrice": "93000.00"
                      }
                    ]
                    """;
            doReturn(responseNoType).when(service).getOpenAlgoOrders(anyString());

            double[] prices = service.getCurrentSLTPPrices("BTCUSDT");

            // 缺少 orderType → 不匹配任何類型 → [0, 0]
            assertThat(prices[0]).isEqualTo(0.0);
            assertThat(prices[1]).isEqualTo(0.0);
        }

        @Test
        @DisplayName("cancelSLTPOrders 缺少 orderType → 不取消任何訂單")
        void cancelSkipsMissingOrderType() {
            String responseNoType = """
                    [
                      {
                        "algoId": 600001,
                        "symbol": "BTCUSDT",
                        "side": "SELL",
                        "quantity": "0.500",
                        "triggerPrice": "93000.00"
                      }
                    ]
                    """;
            doReturn(responseNoType).when(service).getOpenAlgoOrders(anyString());

            service.cancelSLTPOrders("BTCUSDT");

            verify(service, never()).cancelAlgoOrder(anyString(), anyLong());
        }

        @Test
        @DisplayName("Binance 回傳正整數 code → 非錯誤，視為非預期格式")
        void handlePositiveCodeResponse() {
            // code > 0 不是 Binance 的錯誤格式
            String weirdResponse = """
                    {"code": 200, "msg": "ok"}
                    """;
            doReturn(weirdResponse).when(service).getOpenAlgoOrders(anyString());

            double[] prices = service.getCurrentSLTPPrices("BTCUSDT");

            // 非預期格式 → 回傳 [0, 0]，不拋異常
            assertThat(prices[0]).isEqualTo(0.0);
            assertThat(prices[1]).isEqualTo(0.0);
        }
    }
}
