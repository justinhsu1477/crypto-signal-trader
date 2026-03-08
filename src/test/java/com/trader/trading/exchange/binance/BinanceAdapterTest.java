package com.trader.trading.exchange.binance;

import com.trader.shared.config.BinanceConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.util.BinanceApiRateLimiter;
import com.trader.trading.exchange.ExchangeCredentials;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BinanceAdapter 單元測試
 *
 * 測試範圍：
 * 1. Algo 訂單 JSON 解析（getCurrentSLTPPrices、cancelSLTPOrders）
 * 2. 訂單回應解析（placeLimitOrder、placeMarketOrder、setStopLoss、setTakeProfit）
 * 3. HTTP 重試行為（sendSignedPostWithRetry — IOException 觸發重試）
 * 4. 連線失敗處理（executeRequest — IOException 包裝為 RuntimeException）
 * 5. 帳戶/持倉查詢解析
 * 6. 格式化方法（formatPrice、formatQuantity）
 * 7. 認證上下文（ExchangeCredentials ThreadLocal 優先級）
 * 8. HTTP 請求格式驗證（簽名、endpoint、method）
 *
 * 測試策略：
 * - Mock OkHttpClient + Call，控制 HTTP 回應
 * - Mock BinanceApiRateLimiter，避免 throttle/reject
 * - 不 mock BinanceConfig，使用真實設定
 *
 * 注意：本測試涵蓋從 AlgoOrderIntegrationTest、SafetyCheckTest、AlertNotificationTest
 * 遷移過來的 Adapter 層級測試（Algo 解析、HTTP 行為、連線失敗告警等）。
 */
class BinanceAdapterTest {

    private OkHttpClient mockHttpClient;
    private Call mockCall;
    private BinanceConfig binanceConfig;
    private BinanceApiRateLimiter mockRateLimiter;
    private BinanceAdapter adapter;

    @BeforeEach
    void setUp() {
        mockHttpClient = mock(OkHttpClient.class);
        mockCall = mock(Call.class);
        when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);

        binanceConfig = new BinanceConfig(
                "https://fapi.binance.com", "wss://fstream.binance.com"
        );
        mockRateLimiter = mock(BinanceApiRateLimiter.class);
        adapter = new BinanceAdapter(mockHttpClient, binanceConfig, mockRateLimiter, null);
        adapter.setCredentials(new ExchangeCredentials("test-api-key", "test-secret-key"));
    }

    @AfterEach
    void tearDown() {
        adapter.clearCredentials();
    }

    // ==================== 測試輔助方法 ====================

    /**
     * 建立 OkHttp Response（每次呼叫都建立新的，避免 body 重複消費問題）
     */
    private Response buildResponse(int code, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://fapi.binance.com/test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code >= 200 && code < 300 ? "OK" : "Error")
                .body(ResponseBody.create(body, MediaType.parse("application/json")))
                .build();
    }

    /**
     * Mock 單一 HTTP 回應（使用 thenAnswer 確保每次回傳新的 Response 物件）
     */
    private void mockHttpResponse(int code, String body) throws IOException {
        when(mockCall.execute()).thenAnswer(inv -> buildResponse(code, body));
    }

    /**
     * Mock 多個依序的 HTTP 回應
     * 支援 String（成功回應）和 IOException（模擬網路失敗）
     */
    private void mockSequentialResponses(Object... responses) throws IOException {
        AtomicInteger idx = new AtomicInteger(0);
        when(mockCall.execute()).thenAnswer(inv -> {
            int i = idx.getAndIncrement();
            if (i < responses.length) {
                Object resp = responses[i];
                if (resp instanceof String body) {
                    return buildResponse(200, body);
                } else if (resp instanceof IOException ex) {
                    throw ex;
                }
            }
            return buildResponse(200, "{}");
        });
    }

    // ==================== Algo 訂單 SL/TP 價格查詢 ====================

    @Nested
    @DisplayName("getCurrentSLTPPrices — Algo 訂單 SL/TP 解析")
    class GetCurrentSLTPPrices {

        @Test
        @DisplayName("SL + TP 都存在 → 回傳正確價格")
        void bothSLAndTP() throws IOException {
            String response = """
                    [
                        {"algoId":123,"orderType":"STOP_MARKET","triggerPrice":"93000.0","symbol":"BTCUSDT"},
                        {"algoId":456,"orderType":"TAKE_PROFIT_MARKET","triggerPrice":"100000.0","symbol":"BTCUSDT"}
                    ]""";
            mockHttpResponse(200, response);

            double[] prices = adapter.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(93000.0);   // SL
            assertThat(prices[1]).isEqualTo(100000.0);   // TP
        }

        @Test
        @DisplayName("只有 SL → TP 為 0")
        void slOnly() throws IOException {
            mockHttpResponse(200, """
                    [{"algoId":123,"orderType":"STOP_MARKET","triggerPrice":"93000.0"}]""");

            double[] prices = adapter.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(93000.0);
            assertThat(prices[1]).isEqualTo(0.0);
        }

        @Test
        @DisplayName("只有 TP → SL 為 0")
        void tpOnly() throws IOException {
            mockHttpResponse(200, """
                    [{"algoId":456,"orderType":"TAKE_PROFIT_MARKET","triggerPrice":"100000.0"}]""");

            double[] prices = adapter.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(0.0);
            assertThat(prices[1]).isEqualTo(100000.0);
        }

        @Test
        @DisplayName("空陣列 → SL/TP 都為 0")
        void emptyArray() throws IOException {
            mockHttpResponse(200, "[]");

            double[] prices = adapter.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(0.0);
            assertThat(prices[1]).isEqualTo(0.0);
        }

        @Test
        @DisplayName("API 回傳錯誤 code → SL/TP 都為 0（不拋異常）")
        void apiErrorReturnZeros() throws IOException {
            mockHttpResponse(200, """
                    {"code":-2021,"msg":"Order would immediately trigger."}""");

            double[] prices = adapter.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(0.0);
            assertThat(prices[1]).isEqualTo(0.0);
        }

        @Test
        @DisplayName("缺少 triggerPrice 欄位 → 該訂單被忽略")
        void missingTriggerPrice() throws IOException {
            String response = """
                    [
                        {"algoId":123,"orderType":"STOP_MARKET"},
                        {"algoId":456,"orderType":"TAKE_PROFIT_MARKET","triggerPrice":"100000.0"}
                    ]""";
            mockHttpResponse(200, response);

            double[] prices = adapter.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(0.0);       // SL 無 triggerPrice
            assertThat(prices[1]).isEqualTo(100000.0);   // TP 正常
        }

        @Test
        @DisplayName("真實 Binance 格式含額外欄位 → 正確解析")
        void realBinanceFormat() throws IOException {
            String response = """
                    [
                        {
                            "algoId":2146760,
                            "symbol":"BTCUSDT",
                            "orderType":"STOP_MARKET",
                            "algoType":"CONDITIONAL",
                            "triggerPrice":"93500.0",
                            "side":"SELL",
                            "quantity":"0.250",
                            "algoStatus":"WORKING",
                            "clientAlgoId":"SL-1234567890-abc"
                        },
                        {
                            "algoId":2146761,
                            "symbol":"BTCUSDT",
                            "orderType":"TAKE_PROFIT_MARKET",
                            "algoType":"CONDITIONAL",
                            "triggerPrice":"98000.0",
                            "side":"SELL",
                            "quantity":"0.250",
                            "algoStatus":"WORKING",
                            "clientAlgoId":"TP-1234567891-def"
                        }
                    ]""";
            mockHttpResponse(200, response);

            double[] prices = adapter.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(93500.0);
            assertThat(prices[1]).isEqualTo(98000.0);
        }

        @Test
        @DisplayName("連線失敗 → SL/TP 都為 0（不拋異常）")
        void connectionFailureReturnZeros() throws IOException {
            when(mockCall.execute()).thenThrow(new IOException("Connection refused"));

            double[] prices = adapter.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(0.0);
            assertThat(prices[1]).isEqualTo(0.0);
        }
    }

    // ==================== 取消 SL/TP Algo 訂單 ====================

    @Nested
    @DisplayName("cancelSLTPOrders — 取消 SL/TP Algo 訂單")
    class CancelSLTPOrders {

        @Test
        @DisplayName("SL + TP 都存在 → 各取消一次（共 3 次 HTTP 呼叫）")
        void cancelBothSLAndTP() throws IOException {
            String algoResponse = """
                    [
                        {"algoId":123,"orderType":"STOP_MARKET"},
                        {"algoId":456,"orderType":"TAKE_PROFIT_MARKET"}
                    ]""";
            // 3 calls: GET openAlgoOrders + DELETE SL + DELETE TP
            mockSequentialResponses(algoResponse, "{}", "{}");

            adapter.cancelSLTPOrders("BTCUSDT");

            verify(mockHttpClient, times(3)).newCall(any(Request.class));
        }

        @Test
        @DisplayName("空陣列 → 不發 DELETE 請求")
        void emptyListNoDelete() throws IOException {
            mockHttpResponse(200, "[]");

            adapter.cancelSLTPOrders("BTCUSDT");

            // Only 1 call: GET openAlgoOrders
            verify(mockHttpClient, times(1)).newCall(any(Request.class));
        }

        @Test
        @DisplayName("LIMIT 訂單 → 不取消（只取消 STOP_MARKET / TAKE_PROFIT_MARKET）")
        void skipLimitOrders() throws IOException {
            String algoResponse = """
                    [
                        {"algoId":789,"orderType":"LIMIT"},
                        {"algoId":123,"orderType":"STOP_MARKET"}
                    ]""";
            // 2 calls: GET + DELETE SL only
            mockSequentialResponses(algoResponse, "{}");

            adapter.cancelSLTPOrders("BTCUSDT");

            verify(mockHttpClient, times(2)).newCall(any(Request.class));
        }

        @Test
        @DisplayName("缺少 algoId → 跳過該訂單，不拋異常")
        void missingAlgoIdSkipped() throws IOException {
            String algoResponse = """
                    [
                        {"orderType":"STOP_MARKET"},
                        {"algoId":456,"orderType":"TAKE_PROFIT_MARKET"}
                    ]""";
            // 2 calls: GET + DELETE TP only (SL skipped due to missing algoId)
            mockSequentialResponses(algoResponse, "{}");

            adapter.cancelSLTPOrders("BTCUSDT");

            verify(mockHttpClient, times(2)).newCall(any(Request.class));
        }

        @Test
        @DisplayName("API 回傳錯誤 code → 拋出 RuntimeException")
        void apiErrorThrows() throws IOException {
            mockHttpResponse(200, """
                    {"code":-1021,"msg":"Timestamp for this request is outside of the recvWindow."}""");

            assertThatThrownBy(() -> adapter.cancelSLTPOrders("BTCUSDT"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("取消 SL/TP Algo 訂單失敗");
        }
    }

    // ==================== 下單回應解析 ====================

    @Nested
    @DisplayName("下單回應解析")
    class OrderResponseParsing {

        @Test
        @DisplayName("LIMIT 訂單成功 → 正確解析所有欄位")
        void limitOrderSuccess() throws IOException {
            String response = """
                    {
                        "orderId":12345,"symbol":"BTCUSDT","side":"BUY",
                        "type":"LIMIT","price":"95000.0","origQty":"0.250",
                        "status":"NEW"
                    }""";
            mockHttpResponse(200, response);

            OrderResult result = adapter.placeLimitOrder("BTCUSDT", "BUY", 95000, 0.25);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOrderId()).isEqualTo("12345");
            assertThat(result.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(result.getSide()).isEqualTo("BUY");
            assertThat(result.getType()).isEqualTo("LIMIT");
            assertThat(result.getPrice()).isEqualTo(95000.0);
            assertThat(result.getQuantity()).isEqualTo(0.25);
        }

        @Test
        @DisplayName("MARKET 訂單 — price=0 時用 avgPrice")
        void marketOrderUsesAvgPrice() throws IOException {
            String response = """
                    {
                        "orderId":12346,"symbol":"BTCUSDT","side":"BUY",
                        "type":"MARKET","price":"0","avgPrice":"95123.5","origQty":"0.250"
                    }""";
            mockHttpResponse(200, response);

            OrderResult result = adapter.placeMarketOrder("BTCUSDT", "BUY", 0.25);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getPrice()).isEqualTo(95123.5);
        }

        @Test
        @DisplayName("MARKET 訂單 — avgPrice=0 時用 fills 加權均價")
        void marketOrderUsesFillsWeightedAvg() throws IOException {
            String response = """
                    {
                        "orderId":12347,"symbol":"BTCUSDT","side":"BUY",
                        "type":"MARKET","price":"0","avgPrice":"0","origQty":"0.500",
                        "fills":[
                            {"price":"95000.0","qty":"0.300"},
                            {"price":"95100.0","qty":"0.200"}
                        ]
                    }""";
            mockHttpResponse(200, response);

            OrderResult result = adapter.placeMarketOrder("BTCUSDT", "BUY", 0.5);

            assertThat(result.isSuccess()).isTrue();
            // 加權均價: (95000*0.3 + 95100*0.2) / 0.5 = 47520 / 0.5 = 95040
            assertThat(result.getPrice()).isCloseTo(95040.0, within(0.1));
        }

        @Test
        @DisplayName("訂單被拒絕 — code != 200")
        void orderRejected() throws IOException {
            mockHttpResponse(200, """
                    {"code":-2021,"msg":"Order would immediately trigger."}""");

            OrderResult result = adapter.placeLimitOrder("BTCUSDT", "BUY", 95000, 0.25);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("Order would immediately trigger");
        }

        @Test
        @DisplayName("回應 JSON 無法解析 → 失敗結果")
        void malformedResponseBody() throws IOException {
            mockHttpResponse(200, "not valid json!!!");

            OrderResult result = adapter.placeLimitOrder("BTCUSDT", "BUY", 95000, 0.25);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("Failed to parse response");
        }

        @Test
        @DisplayName("SL Algo 訂單成功")
        void stopLossAlgoSuccess() throws IOException {
            mockHttpResponse(200, """
                    {"algoId":2146760,"clientAlgoId":"SL-123","algoType":"CONDITIONAL"}""");

            OrderResult result = adapter.setStopLoss("BTCUSDT", "SELL", 93000, 0.25);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOrderId()).isEqualTo("2146760");
            assertThat(result.getType()).isEqualTo("STOP_MARKET");
            assertThat(result.getPrice()).isEqualTo(93000.0);
            assertThat(result.getQuantity()).isEqualTo(0.25);
        }

        @Test
        @DisplayName("TP Algo 訂單成功")
        void takeProfitAlgoSuccess() throws IOException {
            mockHttpResponse(200, """
                    {"algoId":2146761,"clientAlgoId":"TP-123","algoType":"CONDITIONAL"}""");

            OrderResult result = adapter.setTakeProfit("BTCUSDT", "SELL", 100000, 0.25);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOrderId()).isEqualTo("2146761");
            assertThat(result.getType()).isEqualTo("TAKE_PROFIT_MARKET");
        }

        @Test
        @DisplayName("Algo 訂單失敗 — 負 code")
        void algoOrderFailed() throws IOException {
            mockHttpResponse(200, """
                    {"code":-2021,"msg":"Order would immediately trigger."}""");

            OrderResult result = adapter.setStopLoss("BTCUSDT", "SELL", 93000, 0.25);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("-2021").contains("Order would immediately trigger");
        }

        @Test
        @DisplayName("Algo 回應缺少 algoId → 失敗")
        void algoOrderMissingAlgoId() throws IOException {
            mockHttpResponse(200, """
                    {"clientAlgoId":"SL-123","algoType":"CONDITIONAL"}""");

            OrderResult result = adapter.setStopLoss("BTCUSDT", "SELL", 93000, 0.25);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("missing algoId");
        }
    }

    // ==================== 帳戶與持倉查詢 ====================

    @Nested
    @DisplayName("帳戶與持倉查詢")
    class AccountAndPosition {

        @Test
        @DisplayName("getAvailableBalance — 正確解析 USDT 餘額")
        void getAvailableBalance() throws IOException {
            mockHttpResponse(200, """
                    [
                        {"asset":"BNB","balance":"1.0","availableBalance":"1.0","crossUnPnl":"0.0"},
                        {"asset":"USDT","balance":"10000.0","availableBalance":"8500.5","crossUnPnl":"150.0"}
                    ]""");

            double balance = adapter.getAvailableBalance();

            assertThat(balance).isEqualTo(8500.5);
        }

        @Test
        @DisplayName("getAvailableBalance — 無 USDT → 拋異常")
        void getAvailableBalanceNoUSDT() throws IOException {
            mockHttpResponse(200, """
                    [{"asset":"BNB","balance":"1.0","availableBalance":"1.0","crossUnPnl":"0.0"}]""");

            assertThatThrownBy(() -> adapter.getAvailableBalance())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("找不到 USDT 餘額");
        }

        @Test
        @DisplayName("getCurrentPositionAmount — 有多倉")
        void hasLongPosition() throws IOException {
            mockHttpResponse(200, """
                    [
                        {"symbol":"BTCUSDT","positionAmt":"0.250"},
                        {"symbol":"ETHUSDT","positionAmt":"0.000"}
                    ]""");

            double amt = adapter.getCurrentPositionAmount("BTCUSDT");

            assertThat(amt).isEqualTo(0.25);
        }

        @Test
        @DisplayName("getCurrentPositionAmount — 有空倉（負數）")
        void hasShortPosition() throws IOException {
            mockHttpResponse(200, """
                    [{"symbol":"BTCUSDT","positionAmt":"-0.500"}]""");

            double amt = adapter.getCurrentPositionAmount("BTCUSDT");

            assertThat(amt).isEqualTo(-0.5);
        }

        @Test
        @DisplayName("getCurrentPositionAmount — 無持倉 → 0")
        void noPosition() throws IOException {
            mockHttpResponse(200, """
                    [{"symbol":"BTCUSDT","positionAmt":"0.000"}]""");

            double amt = adapter.getCurrentPositionAmount("BTCUSDT");

            assertThat(amt).isEqualTo(0.0);
        }

        @Test
        @DisplayName("getCurrentPositionAmount — JSON 解析失敗 → 拋異常")
        void positionParseFailure() throws IOException {
            mockHttpResponse(200, "invalid json!!!");

            assertThatThrownBy(() -> adapter.getCurrentPositionAmount("BTCUSDT"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("查詢持倉失敗");
        }

        @Test
        @DisplayName("getActivePositionCount — 多個持倉（過濾 0 持倉）")
        void multiplePositions() throws IOException {
            mockHttpResponse(200, """
                    [
                        {"symbol":"BTCUSDT","positionAmt":"0.250"},
                        {"symbol":"ETHUSDT","positionAmt":"-1.500"},
                        {"symbol":"SOLUSDT","positionAmt":"0.000"}
                    ]""");

            int count = adapter.getActivePositionCount();

            assertThat(count).isEqualTo(2);  // BTC + ETH, not SOL
        }

        @Test
        @DisplayName("getAllPositionAmounts — 過濾 0 持倉")
        void getAllPositionAmounts() throws IOException {
            mockHttpResponse(200, """
                    [
                        {"symbol":"BTCUSDT","positionAmt":"0.250"},
                        {"symbol":"ETHUSDT","positionAmt":"0.000"},
                        {"symbol":"SOLUSDT","positionAmt":"-10.0"}
                    ]""");

            Map<String, Double> positions = adapter.getAllPositionAmounts();

            assertThat(positions).hasSize(2);
            assertThat(positions.get("BTCUSDT")).isEqualTo(0.25);
            assertThat(positions.get("SOLUSDT")).isEqualTo(-10.0);
            assertThat(positions).doesNotContainKey("ETHUSDT");
        }

        @Test
        @DisplayName("getMarkPrice — 正確解析")
        void getMarkPrice() throws IOException {
            mockHttpResponse(200, """
                    {"symbol":"BTCUSDT","price":"95500.50"}""");

            double price = adapter.getMarkPrice("BTCUSDT");

            assertThat(price).isEqualTo(95500.50);
        }

        @Test
        @DisplayName("getMarkPrice — 解析失敗 → 拋異常")
        void getMarkPriceFailure() throws IOException {
            mockHttpResponse(200, "not valid json");

            assertThatThrownBy(() -> adapter.getMarkPrice("BTCUSDT"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("取得市價失敗");
        }

        @Test
        @DisplayName("hasOpenEntryOrders — 有 LIMIT 單 → true")
        void hasLimitOrder() throws IOException {
            mockHttpResponse(200, """
                    [
                        {"orderId":"123","type":"LIMIT","side":"BUY"},
                        {"orderId":"456","type":"STOP_MARKET","side":"SELL"}
                    ]""");

            assertThat(adapter.hasOpenEntryOrders("BTCUSDT")).isTrue();
        }

        @Test
        @DisplayName("hasOpenEntryOrders — 無 LIMIT 單 → false")
        void noLimitOrder() throws IOException {
            mockHttpResponse(200, """
                    [{"orderId":"456","type":"STOP_MARKET","side":"SELL"}]""");

            assertThat(adapter.hasOpenEntryOrders("BTCUSDT")).isFalse();
        }
    }

    // ==================== HTTP 重試行為 ====================

    @Nested
    @DisplayName("HTTP 重試行為（sendSignedPostWithRetry）")
    class RetryBehavior {

        @Test
        @DisplayName("首次成功 → 只呼叫一次 HTTP")
        void successFirstAttempt() throws IOException {
            mockHttpResponse(200, """
                    {"orderId":12345,"symbol":"BTCUSDT","side":"BUY","type":"LIMIT","price":"95000.0","origQty":"0.250"}""");

            OrderResult result = adapter.placeLimitOrder("BTCUSDT", "BUY", 95000, 0.25);

            assertThat(result.isSuccess()).isTrue();
            verify(mockHttpClient, times(1)).newCall(any(Request.class));
        }

        @Test
        @DisplayName("首次 IOException → 重試後成功")
        void retryAfterIOException() throws IOException {
            String successResponse = """
                    {"orderId":12345,"symbol":"BTCUSDT","side":"BUY","type":"LIMIT","price":"95000.0","origQty":"0.250"}""";

            AtomicInteger callCount = new AtomicInteger(0);
            when(mockCall.execute()).thenAnswer(inv -> {
                if (callCount.getAndIncrement() == 0) {
                    throw new IOException("Connection reset");
                }
                return buildResponse(200, successResponse);
            });

            OrderResult result = adapter.placeLimitOrder("BTCUSDT", "BUY", 95000, 0.25);

            assertThat(result.isSuccess()).isTrue();
            verify(mockHttpClient, times(2)).newCall(any(Request.class));
        }

        @Test
        @DisplayName("全部重試都 IOException → 拋出 RuntimeException")
        void allRetriesFail() throws IOException {
            when(mockCall.execute()).thenThrow(new IOException("Network unreachable"));

            assertThatThrownBy(() -> adapter.placeLimitOrder("BTCUSDT", "BUY", 95000, 0.25))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("failed after")
                    .hasMessageContaining("retries");

            // 3 calls: initial + 2 retries (ORDER_MAX_RETRIES = 2)
            verify(mockHttpClient, times(3)).newCall(any(Request.class));
        }

        @Test
        @DisplayName("HTTP 4xx/5xx → 不重試，直接回傳（由 parseResponse 處理）")
        void httpErrorNoRetry() throws IOException {
            mockHttpResponse(400, """
                    {"code":-1021,"msg":"Timestamp outside recvWindow"}""");

            OrderResult result = adapter.placeLimitOrder("BTCUSDT", "BUY", 95000, 0.25);

            // sendSignedPostWithRetry returns body even for non-200, parseOrderResponse handles error
            assertThat(result.isSuccess()).isFalse();
            verify(mockHttpClient, times(1)).newCall(any(Request.class));
        }

        @Test
        @DisplayName("SL 下單全部重試失敗 → RuntimeException")
        void slRetryAllFail() throws IOException {
            when(mockCall.execute()).thenThrow(new IOException("timeout"));

            assertThatThrownBy(() -> adapter.setStopLoss("BTCUSDT", "SELL", 93000, 0.25))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("failed after");

            verify(mockHttpClient, times(3)).newCall(any(Request.class));
        }
    }

    // ==================== 連線失敗處理 ====================

    @Nested
    @DisplayName("連線失敗處理（executeRequest）")
    class ConnectionFailure {

        @Test
        @DisplayName("查詢餘額 IOException → RuntimeException")
        void balanceQueryIOException() throws IOException {
            when(mockCall.execute()).thenThrow(new IOException("Connection refused"));

            assertThatThrownBy(() -> adapter.getAvailableBalance())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Binance API request failed");
        }

        @Test
        @DisplayName("查詢持倉 HTTP 500 → RuntimeException（含 Binance 錯誤碼）")
        void positionQuery500() throws IOException {
            mockHttpResponse(500, """
                    {"code":-1000,"msg":"Internal server error"}""");

            assertThatThrownBy(() -> adapter.getCurrentPositionAmount("BTCUSDT"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Binance API 錯誤");
        }

        @Test
        @DisplayName("取消訂單 IOException → RuntimeException")
        void cancelOrderIOException() throws IOException {
            when(mockCall.execute()).thenThrow(new IOException("Connection timeout"));

            assertThatThrownBy(() -> adapter.cancelOrder("BTCUSDT", "12345"))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("HTTP 500 無法解析 body → 回傳 HTTP code")
        void http500UnparsableBody() throws IOException {
            mockHttpResponse(500, "plain text error");

            assertThatThrownBy(() -> adapter.getAccountBalanceRaw())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("500");
        }
    }

    // ==================== cancelAllOrders ====================

    @Nested
    @DisplayName("cancelAllOrders — 標準訂單 + Algo 訂單取消")
    class CancelAllOrders {

        @Test
        @DisplayName("標準訂單 + Algo SL 取消都成功")
        void cancelStandardAndAlgo() throws IOException {
            String algoResponse = """
                    [{"algoId":123,"orderType":"STOP_MARKET"}]""";
            // Call 1: DELETE allOpenOrders
            // Call 2: GET openAlgoOrders
            // Call 3: DELETE algoOrder (SL)
            mockSequentialResponses("{}", algoResponse, "{}");

            adapter.cancelAllOrders("BTCUSDT");

            verify(mockHttpClient, times(3)).newCall(any(Request.class));
        }

        @Test
        @DisplayName("Algo 取消失敗 → 不拋異常（標準訂單已取消）")
        void algoFailureDoesNotThrow() throws IOException {
            AtomicInteger callCount = new AtomicInteger(0);
            when(mockCall.execute()).thenAnswer(inv -> {
                int i = callCount.getAndIncrement();
                if (i == 0) {
                    return buildResponse(200, "{}"); // DELETE allOpenOrders 成功
                }
                // GET openAlgoOrders 失敗
                throw new IOException("Algo API timeout");
            });

            // cancelAllOrders 內部 catch Algo 失敗，不會拋出
            assertThatCode(() -> adapter.cancelAllOrders("BTCUSDT"))
                    .doesNotThrowAnyException();
        }
    }

    // ==================== 格式化方法 ====================

    @Nested
    @DisplayName("格式化方法")
    class FormatMethods {

        @Test
        @DisplayName("formatPrice — 高價位 (>=1000) → 1 位小數")
        void formatPriceHigh() {
            assertThat(adapter.formatPrice(95000)).isEqualTo("95000.0");
            assertThat(adapter.formatPrice(1000)).isEqualTo("1000.0");
            assertThat(adapter.formatPrice(95123.456)).isEqualTo("95123.5");
        }

        @Test
        @DisplayName("formatPrice — 中價位 (>=1, <1000) → 2 位小數")
        void formatPriceMedium() {
            assertThat(adapter.formatPrice(500.0)).isEqualTo("500.00");
            assertThat(adapter.formatPrice(1.23)).isEqualTo("1.23");
            assertThat(adapter.formatPrice(999.45)).isEqualTo("999.45");
        }

        @Test
        @DisplayName("formatPrice — 低價位 (<1) → 4 位小數")
        void formatPriceLow() {
            assertThat(adapter.formatPrice(0.5)).isEqualTo("0.5000");
            assertThat(adapter.formatPrice(0.00123)).isEqualTo("0.0012");
        }

        @Test
        @DisplayName("formatQuantity — BTC/ETH → 3 位小數")
        void formatQuantityBTCETH() {
            assertThat(adapter.formatQuantity("BTCUSDT", 0.25)).isEqualTo("0.250");
            assertThat(adapter.formatQuantity("ETHUSDT", 1.5)).isEqualTo("1.500");
        }

        @Test
        @DisplayName("formatQuantity — 其他幣種 → 2 位小數")
        void formatQuantityOther() {
            assertThat(adapter.formatQuantity("SOLUSDT", 10.0)).isEqualTo("10.00");
            assertThat(adapter.formatQuantity("DOGEUSDT", 100.5)).isEqualTo("100.50");
        }
    }

    // ==================== 認證上下文 ====================

    @Nested
    @DisplayName("認證上下文 — ExchangeCredentials ThreadLocal")
    class CredentialResolution {

        @Test
        @DisplayName("未設認證 → 拋出 IllegalStateException（fail-fast）")
        void throwsWhenNoCredentials() {
            adapter.clearCredentials();

            assertThatThrownBy(() -> adapter.getAvailableBalance())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("credentials");
        }

        @Test
        @DisplayName("設 ExchangeCredentials → 使用 per-user API Key")
        void usesExchangeCredentials() throws IOException {
            adapter.setCredentials(new ExchangeCredentials("per-user-key", "per-user-secret"));

            mockHttpResponse(200, """
                    [{"asset":"USDT","balance":"1000","availableBalance":"1000","crossUnPnl":"0"}]""");

            adapter.getAvailableBalance();

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(mockHttpClient).newCall(captor.capture());
            assertThat(captor.getValue().header("X-MBX-APIKEY")).isEqualTo("per-user-key");
        }

        @Test
        @DisplayName("clearCredentials → 拋出 IllegalStateException（不再 fallback）")
        void clearThrowsWhenNoCredentials() {
            adapter.setCredentials(new ExchangeCredentials("per-user-key", "per-user-secret"));
            adapter.clearCredentials();

            assertThatThrownBy(() -> adapter.getAvailableBalance())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("credentials");
        }

        @Test
        @DisplayName("getExchangeName → BINANCE")
        void exchangeNameIsBinance() {
            assertThat(adapter.getExchangeName()).isEqualTo("BINANCE");
        }
    }

    // ==================== HTTP 請求格式驗證 ====================

    @Nested
    @DisplayName("HTTP 請求格式驗證")
    class HttpRequestFormat {

        @Test
        @DisplayName("簽名請求包含 timestamp 和 signature 參數")
        void signedRequestHasTimestampAndSignature() throws IOException {
            mockHttpResponse(200, """
                    [{"asset":"USDT","balance":"1000","availableBalance":"1000","crossUnPnl":"0"}]""");

            adapter.getAccountBalanceRaw();

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(mockHttpClient).newCall(captor.capture());
            String url = captor.getValue().url().toString();
            assertThat(url).contains("timestamp=");
            assertThat(url).contains("signature=");
        }

        @Test
        @DisplayName("公開請求不包含簽名和 API Key header")
        void publicRequestNoSignature() throws IOException {
            mockHttpResponse(200, """
                    {"symbol":"BTCUSDT","price":"95000.0"}""");

            adapter.getMarkPrice("BTCUSDT");

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(mockHttpClient).newCall(captor.capture());
            String url = captor.getValue().url().toString();
            assertThat(url).doesNotContain("signature=");
            assertThat(captor.getValue().header("X-MBX-APIKEY")).isNull();
        }

        @Test
        @DisplayName("下單 POST 請求使用 form-urlencoded")
        void orderPostUsesFormUrlEncoded() throws IOException {
            mockHttpResponse(200, """
                    {"orderId":12345,"symbol":"BTCUSDT","side":"BUY","type":"LIMIT","price":"95000.0","origQty":"0.250"}""");

            adapter.placeLimitOrder("BTCUSDT", "BUY", 95000, 0.25);

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(mockHttpClient).newCall(captor.capture());
            Request captured = captor.getValue();
            assertThat(captured.method()).isEqualTo("POST");
            assertThat(captured.body()).isNotNull();
            assertThat(captured.body().contentType().toString())
                    .contains("application/x-www-form-urlencoded");
        }

        @Test
        @DisplayName("setLeverage — POST 到 /fapi/v1/leverage")
        void setLeverageEndpoint() throws IOException {
            mockHttpResponse(200, "{}");

            adapter.setLeverage("BTCUSDT", 20);

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(mockHttpClient).newCall(captor.capture());
            assertThat(captor.getValue().url().encodedPath()).isEqualTo("/fapi/v1/leverage");
            assertThat(captor.getValue().method()).isEqualTo("POST");
        }

        @Test
        @DisplayName("cancelOrder — DELETE 到 /fapi/v1/order")
        void cancelOrderEndpoint() throws IOException {
            mockHttpResponse(200, "{}");

            adapter.cancelOrder("BTCUSDT", "12345");

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(mockHttpClient).newCall(captor.capture());
            assertThat(captor.getValue().url().encodedPath()).isEqualTo("/fapi/v1/order");
            assertThat(captor.getValue().method()).isEqualTo("DELETE");
        }
    }

    // ==================== Rate Limiter 整合 ====================

    @Nested
    @DisplayName("Rate Limiter 整合")
    class RateLimiterIntegration {

        @Test
        @DisplayName("每個 API 請求都呼叫 rateLimiter.acquire()")
        void acquireCalledOnRequest() throws IOException {
            mockHttpResponse(200, """
                    [{"asset":"USDT","balance":"1000","availableBalance":"1000","crossUnPnl":"0"}]""");

            adapter.getAccountBalanceRaw();

            verify(mockRateLimiter).acquire();
        }

        @Test
        @DisplayName("重試時每次都呼叫 rateLimiter.acquire()")
        void acquireCalledOnEachRetry() throws IOException {
            AtomicInteger callCount = new AtomicInteger(0);
            when(mockCall.execute()).thenAnswer(inv -> {
                if (callCount.getAndIncrement() < 2) {
                    throw new IOException("timeout");
                }
                return buildResponse(200, """
                        {"orderId":1,"symbol":"BTCUSDT","side":"BUY","type":"LIMIT","price":"95000","origQty":"0.25"}""");
            });

            adapter.placeLimitOrder("BTCUSDT", "BUY", 95000, 0.25);

            // 3 attempts: initial + 2 retries, each calls acquire()
            verify(mockRateLimiter, times(3)).acquire();
        }

        @Test
        @DisplayName("成功回應的 X-MBX-USED-WEIGHT-1M header → 更新 rate limiter")
        void usedWeightHeaderUpdatesLimiter() throws IOException {
            Response response = new Response.Builder()
                    .request(new Request.Builder().url("https://fapi.binance.com/test").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("X-MBX-USED-WEIGHT-1M", "150")
                    .body(ResponseBody.create("""
                            [{"asset":"USDT","balance":"1000","availableBalance":"1000","crossUnPnl":"0"}]""",
                            MediaType.parse("application/json")))
                    .build();
            when(mockCall.execute()).thenReturn(response);

            adapter.getAccountBalanceRaw();

            verify(mockRateLimiter).updateFromHeader(150);
        }
    }
}
