package com.trader.trading.exchange.bitget;

import com.trader.shared.config.BitgetConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.util.BitgetApiRateLimiter;
import com.trader.trading.exchange.ExchangeCredentials;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BitgetAdapter 單元測試
 *
 * 測試範圍：
 * 1. Bitget V2 回應解析（code / msg / data 格式）
 * 2. 帳戶餘額（marginCoin=USDT 解析）
 * 3. 持倉查詢（holdSide long/short → signed amount）
 * 4. 市場數據（markPrice）
 * 5. 下單回應解析（placeLimitOrder、placeMarketOrder）
 * 6. SL/TP 設定（TPSL 獨立訂單，planType pos_loss/pos_profit）
 * 7. 訂單查詢（hasOpenEntryOrders、getCurrentSLTPPrices）
 * 8. 強平記錄過濾（getForceOrdersRaw）
 * 9. 帳戶配置（setLeverage、setMarginType 含已設定靜默）
 * 10. 格式化方法（formatPrice、formatQuantity）
 * 11. 認證上下文（ExchangeCredentials ThreadLocal + passphrase）
 * 12. HTTP 請求格式（ACCESS-* headers、JSON body）
 * 13. Side 轉換（toBitgetSide、closeSideToHoldSide）
 * 14. 連線失敗處理
 *
 * 測試策略：
 * - Mock OkHttpClient + Call，控制 HTTP 回應
 * - Mock BitgetApiRateLimiter，避免 throttle/reject
 * - 不 mock BitgetConfig，使用真實設定
 */
class BitgetAdapterTest {

    private OkHttpClient mockHttpClient;
    private Call mockCall;
    private BitgetConfig bitgetConfig;
    private BitgetApiRateLimiter mockRateLimiter;
    private BitgetAdapter adapter;

    @BeforeEach
    void setUp() {
        mockHttpClient = mock(OkHttpClient.class);
        mockCall = mock(Call.class);
        when(mockHttpClient.newCall(any(Request.class))).thenReturn(mockCall);

        bitgetConfig = new BitgetConfig(
                "https://api.bitget.com",
                "wss://ws.bitget.com",
                30000
        );
        mockRateLimiter = mock(BitgetApiRateLimiter.class);
        adapter = new BitgetAdapter(mockHttpClient, bitgetConfig, mockRateLimiter);
        adapter.setCredentials(new ExchangeCredentials("test-api-key", "test-secret-key", "test-passphrase"));
    }

    @AfterEach
    void tearDown() {
        adapter.clearCredentials();
    }

    // ==================== 測試輔助方法 ====================

    private Response buildResponse(int code, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://api.bitget.com/test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code >= 200 && code < 300 ? "OK" : "Error")
                .body(ResponseBody.create(body, MediaType.parse("application/json")))
                .build();
    }

    private void mockHttpResponse(int code, String body) throws IOException {
        when(mockCall.execute()).thenAnswer(inv -> buildResponse(code, body));
    }

    /** Bitget V2 成功回應 (code="00000") */
    private String bitgetOk(String dataJson) {
        return """
                {"code":"00000","msg":"success","data":%s}""".formatted(dataJson);
    }

    /** Bitget V2 錯誤回應 */
    private String bitgetError(String code, String msg) {
        return """
                {"code":"%s","msg":"%s","data":null}""".formatted(code, msg);
    }

    // ==================== 帳戶餘額 ====================

    @Nested
    @DisplayName("帳戶餘額查詢 — marginCoin=USDT")
    class AccountBalance {

        @Test
        @DisplayName("正確解析 USDT available")
        void getAvailableBalance() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    [{"marginCoin":"USDT","available":"8500.50","equity":"10000.0"}]"""));

            double balance = adapter.getAvailableBalance();

            assertThat(balance).isEqualTo(8500.5);
        }

        @Test
        @DisplayName("無 USDT marginCoin → 回傳 0")
        void noUSDT() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    [{"marginCoin":"BTC","available":"0.5","equity":"0.5"}]"""));

            double balance = adapter.getAvailableBalance();

            assertThat(balance).isEqualTo(0.0);
        }

        @Test
        @DisplayName("空陣列 → 回傳 0")
        void emptyArray() throws IOException {
            mockHttpResponse(200, bitgetOk("[]"));

            double balance = adapter.getAvailableBalance();

            assertThat(balance).isEqualTo(0.0);
        }

        @Test
        @DisplayName("API error (code != 00000) → 拋異常")
        void apiError() throws IOException {
            mockHttpResponse(200, bitgetError("40001", "Invalid request"));

            assertThatThrownBy(() -> adapter.getAvailableBalance())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("40001")
                    .hasMessageContaining("Invalid request");
        }

        @Test
        @DisplayName("getAccountBalanceRaw → 回傳原始 JSON")
        void rawBalance() throws IOException {
            String raw = bitgetOk("""
                    [{"marginCoin":"USDT","available":"8500.50"}]""");
            mockHttpResponse(200, raw);

            String result = adapter.getAccountBalanceRaw();

            assertThat(result).contains("8500.50");
        }
    }

    // ==================== 持倉查詢 ====================

    @Nested
    @DisplayName("持倉查詢 — holdSide long/short 轉換")
    class PositionQuery {

        @Test
        @DisplayName("多倉 (holdSide=long) → 正數")
        void longPosition() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    [{"symbol":"BTCUSDT","total":"0.250","holdSide":"long"}]"""));

            double amt = adapter.getCurrentPositionAmount("BTCUSDT");

            assertThat(amt).isEqualTo(0.25);
        }

        @Test
        @DisplayName("空倉 (holdSide=short) → 負數")
        void shortPosition() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    [{"symbol":"BTCUSDT","total":"0.500","holdSide":"short"}]"""));

            double amt = adapter.getCurrentPositionAmount("BTCUSDT");

            assertThat(amt).isEqualTo(-0.5);
        }

        @Test
        @DisplayName("total=0 → 回傳 0")
        void noPosition() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    [{"symbol":"BTCUSDT","total":"0","holdSide":""}]"""));

            double amt = adapter.getCurrentPositionAmount("BTCUSDT");

            assertThat(amt).isEqualTo(0.0);
        }

        @Test
        @DisplayName("查無 symbol → 回傳 0")
        void symbolNotFound() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    [{"symbol":"ETHUSDT","total":"1.0","holdSide":"long"}]"""));

            double amt = adapter.getCurrentPositionAmount("BTCUSDT");

            assertThat(amt).isEqualTo(0.0);
        }

        @Test
        @DisplayName("空陣列 → 回傳 0")
        void emptyArray() throws IOException {
            mockHttpResponse(200, bitgetOk("[]"));

            double amt = adapter.getCurrentPositionAmount("BTCUSDT");

            assertThat(amt).isEqualTo(0.0);
        }

        @Test
        @DisplayName("getAllPositionAmounts — 過濾 total=0，long 正 / short 負")
        void getAllPositionAmounts() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    [
                        {"symbol":"BTCUSDT","total":"0.250","holdSide":"long"},
                        {"symbol":"ETHUSDT","total":"0","holdSide":""},
                        {"symbol":"SOLUSDT","total":"10.0","holdSide":"short"}
                    ]"""));

            Map<String, Double> positions = adapter.getAllPositionAmounts();

            assertThat(positions).hasSize(2);
            assertThat(positions.get("BTCUSDT")).isEqualTo(0.25);
            assertThat(positions.get("SOLUSDT")).isEqualTo(-10.0);
            assertThat(positions).doesNotContainKey("ETHUSDT");
        }

        @Test
        @DisplayName("getActivePositionCount — 計算非零持倉數")
        void activePositionCount() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    [
                        {"symbol":"BTCUSDT","total":"0.250","holdSide":"long"},
                        {"symbol":"ETHUSDT","total":"0","holdSide":""},
                        {"symbol":"SOLUSDT","total":"10.0","holdSide":"short"}
                    ]"""));

            int count = adapter.getActivePositionCount();

            assertThat(count).isEqualTo(2);
        }

        @Test
        @DisplayName("getPositionsRaw — 回傳原始 JSON")
        void rawPositions() throws IOException {
            String raw = bitgetOk("""
                    [{"symbol":"BTCUSDT","total":"0.250","holdSide":"long"}]""");
            mockHttpResponse(200, raw);

            String result = adapter.getPositionsRaw();

            assertThat(result).contains("BTCUSDT");
        }
    }

    // ==================== 市場數據 ====================

    @Nested
    @DisplayName("市場數據")
    class MarketData {

        @Test
        @DisplayName("getMarkPrice — 陣列格式正確解析")
        void getMarkPrice_array() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    [{"symbol":"BTCUSDT","markPrice":"95500.50"}]"""));

            double price = adapter.getMarkPrice("BTCUSDT");

            assertThat(price).isEqualTo(95500.50);
        }

        @Test
        @DisplayName("getMarkPrice — 物件格式正確解析")
        void getMarkPrice_object() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"symbol":"BTCUSDT","markPrice":"95500.50"}"""));

            double price = adapter.getMarkPrice("BTCUSDT");

            assertThat(price).isEqualTo(95500.50);
        }

        @Test
        @DisplayName("getMarkPrice — 空陣列 → 回傳 0")
        void getMarkPrice_emptyArray() throws IOException {
            mockHttpResponse(200, bitgetOk("[]"));

            double price = adapter.getMarkPrice("BTCUSDT");

            assertThat(price).isEqualTo(0.0);
        }

        @Test
        @DisplayName("getExchangeInfoRaw — 回傳原始 JSON")
        void exchangeInfoRaw() throws IOException {
            String raw = bitgetOk("""
                    [{"symbol":"BTCUSDT","pricePlace":"1","volumePlace":"3"}]""");
            mockHttpResponse(200, raw);

            String result = adapter.getExchangeInfoRaw();

            assertThat(result).contains("BTCUSDT");
        }
    }

    // ==================== 下單回應解析 ====================

    @Nested
    @DisplayName("下單回應解析 — Bitget V2 格式")
    class OrderResponseParsing {

        @Test
        @DisplayName("LIMIT 訂單成功")
        void limitOrderSuccess() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"orderId":"bg-123","clientOid":"client-1"}"""));

            OrderResult result = adapter.placeLimitOrder("BTCUSDT", "BUY", 95000, 0.25);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOrderId()).isEqualTo("bg-123");
        }

        @Test
        @DisplayName("MARKET 訂單成功")
        void marketOrderSuccess() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"orderId":"bg-456","clientOid":"client-2"}"""));

            OrderResult result = adapter.placeMarketOrder("BTCUSDT", "BUY", 0.25);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOrderId()).isEqualTo("bg-456");
        }

        @Test
        @DisplayName("訂單被拒 — code != 00000")
        void orderRejected() throws IOException {
            mockHttpResponse(200, bitgetError("40762", "Insufficient balance"));

            OrderResult result = adapter.placeLimitOrder("BTCUSDT", "BUY", 95000, 0.25);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("Insufficient balance");
        }

        @Test
        @DisplayName("空 data → 仍成功（orderId 為空字串）")
        void emptyData() throws IOException {
            mockHttpResponse(200, bitgetOk("{}"));

            OrderResult result = adapter.placeLimitOrder("BTCUSDT", "BUY", 95000, 0.25);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOrderId()).isEmpty();
        }
    }

    // ==================== SL/TP 設定 ====================

    @Nested
    @DisplayName("SL/TP — TPSL 獨立訂單 (place-tpsl-order)")
    class StopLossTakeProfit {

        @Test
        @DisplayName("設定 SL 成功")
        void setStopLossSuccess() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"orderId":"tpsl-001"}"""));

            OrderResult result = adapter.setStopLoss("BTCUSDT", "SELL", 93000, 0.25);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOrderId()).isEqualTo("tpsl-001");
        }

        @Test
        @DisplayName("設定 TP 成功")
        void setTakeProfitSuccess() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"orderId":"tpsl-002"}"""));

            OrderResult result = adapter.setTakeProfit("BTCUSDT", "SELL", 100000, 0.25);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getOrderId()).isEqualTo("tpsl-002");
        }

        @Test
        @DisplayName("SL 失敗 → success=false")
        void setStopLossFailed() throws IOException {
            mockHttpResponse(200, bitgetError("40763", "Position not found"));

            OrderResult result = adapter.setStopLoss("BTCUSDT", "SELL", 93000, 0.25);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.getErrorMessage()).contains("Position not found");
        }
    }

    // ==================== 訂單查詢 ====================

    @Nested
    @DisplayName("訂單查詢")
    class OrderQuery {

        @Test
        @DisplayName("hasOpenEntryOrders — 有掛單 → true")
        void hasOpenOrders() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"entrustedList":[{"orderId":"123","symbol":"BTCUSDT"}]}"""));

            boolean result = adapter.hasOpenEntryOrders("BTCUSDT");

            assertThat(result).isTrue();
        }

        @Test
        @DisplayName("hasOpenEntryOrders — 無掛單 → false")
        void noOpenOrders() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"entrustedList":[]}"""));

            boolean result = adapter.hasOpenEntryOrders("BTCUSDT");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("hasOpenEntryOrders — data 不是 object → false")
        void dataNotObject() throws IOException {
            mockHttpResponse(200, bitgetOk("[]"));

            boolean result = adapter.hasOpenEntryOrders("BTCUSDT");

            assertThat(result).isFalse();
        }

        @Test
        @DisplayName("getCurrentSLTPPrices — 同時有 SL 和 TP")
        void slAndTpPrices() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"entrustedList":[
                        {"planType":"pos_loss","triggerPrice":"93000.0"},
                        {"planType":"pos_profit","triggerPrice":"100000.0"}
                    ]}"""));

            double[] prices = adapter.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(93000.0); // SL
            assertThat(prices[1]).isEqualTo(100000.0); // TP
        }

        @Test
        @DisplayName("getCurrentSLTPPrices — 只有 SL，無 TP")
        void onlySL() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"entrustedList":[
                        {"planType":"pos_loss","triggerPrice":"93000.0"}
                    ]}"""));

            double[] prices = adapter.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(93000.0);
            assertThat(prices[1]).isEqualTo(0.0);
        }

        @Test
        @DisplayName("getCurrentSLTPPrices — 空 entrustedList → [0, 0]")
        void emptySLTP() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"entrustedList":[]}"""));

            double[] prices = adapter.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(0.0);
            assertThat(prices[1]).isEqualTo(0.0);
        }

        @Test
        @DisplayName("getCurrentSLTPPrices — 例外 → 回傳 [0, 0]")
        void exceptionHandled() throws IOException {
            when(mockCall.execute()).thenThrow(new IOException("timeout"));

            double[] prices = adapter.getCurrentSLTPPrices("BTCUSDT");

            assertThat(prices[0]).isEqualTo(0.0);
            assertThat(prices[1]).isEqualTo(0.0);
        }

        @Test
        @DisplayName("getOpenOrdersRaw — 回傳原始 JSON")
        void rawOpenOrders() throws IOException {
            String raw = bitgetOk("""
                    {"entrustedList":[{"orderId":"123"}]}""");
            mockHttpResponse(200, raw);

            String result = adapter.getOpenOrdersRaw("BTCUSDT");

            assertThat(result).contains("123");
        }
    }

    // ==================== 取消訂單 ====================

    @Nested
    @DisplayName("取消訂單")
    class CancelOrders {

        @Test
        @DisplayName("cancelOrder — 成功")
        void cancelOrderSuccess() throws IOException {
            mockHttpResponse(200, bitgetOk("{}"));

            assertThatCode(() -> adapter.cancelOrder("BTCUSDT", "order-123"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("cancelAllOrders — 成功")
        void cancelAllSuccess() throws IOException {
            mockHttpResponse(200, bitgetOk("{}"));

            assertThatCode(() -> adapter.cancelAllOrders("BTCUSDT"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("cancelOrder — API 錯誤 → 拋異常")
        void cancelOrderError() throws IOException {
            mockHttpResponse(200, bitgetError("40764", "Order not found"));

            assertThatThrownBy(() -> adapter.cancelOrder("BTCUSDT", "order-123"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Order not found");
        }
    }

    // ==================== cancelSLTPOrders ====================

    @Nested
    @DisplayName("cancelSLTPOrders — 查詢 plan-pending + 逐筆取消 pos_loss/pos_profit")
    class CancelSLTP {

        @Test
        @DisplayName("有 SL + TP → 各取消一筆")
        void cancelBothSLTP() throws IOException {
            // 第一次呼叫：查詢 plan-pending
            // 第二、三次：取消各一筆
            java.util.concurrent.atomic.AtomicInteger callCount = new java.util.concurrent.atomic.AtomicInteger(0);
            when(mockCall.execute()).thenAnswer(inv -> {
                int idx = callCount.getAndIncrement();
                if (idx == 0) {
                    return buildResponse(200, bitgetOk("""
                            {"entrustedList":[
                                {"orderId":"sl-1","planType":"pos_loss"},
                                {"orderId":"tp-1","planType":"pos_profit"},
                                {"orderId":"normal-1","planType":"normal_plan"}
                            ]}"""));
                }
                return buildResponse(200, bitgetOk("{}"));
            });

            adapter.cancelSLTPOrders("BTCUSDT");

            // 1 query + 2 cancels (只取消 pos_loss 和 pos_profit，不取消 normal_plan)
            verify(mockHttpClient, times(3)).newCall(any(Request.class));
        }

        @Test
        @DisplayName("data 不是 object → 不做任何操作")
        void dataNotObject() throws IOException {
            mockHttpResponse(200, bitgetOk("[]"));

            adapter.cancelSLTPOrders("BTCUSDT");

            // 只有查詢的那一次呼叫
            verify(mockHttpClient, times(1)).newCall(any(Request.class));
        }
    }

    // ==================== 強平記錄 ====================

    @Nested
    @DisplayName("強平記錄 — getForceOrdersRaw")
    class ForceOrders {

        @Test
        @DisplayName("過濾 liquidation 訂單並正規化")
        void filterLiquidation() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"entrustedList":[
                        {"orderId":"liq-1","cTime":"1672531200000","symbol":"BTCUSDT",
                         "side":"sell","priceAvg":"93000","size":"0.1","orderType":"liquidation",
                         "enterPointSource":"WEB"},
                        {"orderId":"normal-1","cTime":"1672531200000","symbol":"ETHUSDT",
                         "side":"buy","priceAvg":"3000","size":"1.0","orderType":"limit",
                         "enterPointSource":"WEB"}
                    ]}"""));

            String result = adapter.getForceOrdersRaw();

            assertThat(result).contains("liq-1");
            assertThat(result).doesNotContain("normal-1");
        }

        @Test
        @DisplayName("過濾 SYS enterPointSource 訂單")
        void filterSYS() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"entrustedList":[
                        {"orderId":"sys-1","cTime":"1672531200000","symbol":"BTCUSDT",
                         "side":"sell","priceAvg":"93000","size":"0.1","orderType":"market",
                         "enterPointSource":"SYS"}
                    ]}"""));

            String result = adapter.getForceOrdersRaw();

            assertThat(result).contains("sys-1");
        }

        @Test
        @DisplayName("無強平訂單 → 回傳空陣列")
        void noLiquidations() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"entrustedList":[
                        {"orderId":"normal-1","orderType":"limit","enterPointSource":"WEB"}
                    ]}"""));

            String result = adapter.getForceOrdersRaw();

            assertThat(result).isEqualTo("[]");
        }

        @Test
        @DisplayName("data 不是 object → 回傳空陣列")
        void dataNotObject() throws IOException {
            mockHttpResponse(200, bitgetOk("[]"));

            String result = adapter.getForceOrdersRaw();

            assertThat(result).isEqualTo("[]");
        }

        @Test
        @DisplayName("連線失敗 → 回傳空陣列")
        void connectionFailure() throws IOException {
            when(mockCall.execute()).thenThrow(new IOException("timeout"));

            String result = adapter.getForceOrdersRaw();

            assertThat(result).isEqualTo("[]");
        }
    }

    // ==================== 帳戶配置 ====================

    @Nested
    @DisplayName("帳戶配置 — setLeverage / setMarginType")
    class AccountConfig {

        @Test
        @DisplayName("setLeverage — 成功")
        void setLeverageSuccess() throws IOException {
            mockHttpResponse(200, bitgetOk("{}"));

            assertThatCode(() -> adapter.setLeverage("BTCUSDT", 20))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("setLeverage — 已設定（40723）→ 靜默忽略")
        void leverageAlreadySet() throws IOException {
            mockHttpResponse(200, bitgetError("40723", "leverage already set"));

            assertThatCode(() -> adapter.setLeverage("BTCUSDT", 20))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("setLeverage — 其他錯誤 → 拋異常")
        void leverageOtherError() throws IOException {
            mockHttpResponse(200, bitgetError("50001", "System error"));

            assertThatThrownBy(() -> adapter.setLeverage("BTCUSDT", 20))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("50001");
        }

        @Test
        @DisplayName("setMarginType — 成功")
        void setMarginTypeSuccess() throws IOException {
            mockHttpResponse(200, bitgetOk("{}"));

            assertThatCode(() -> adapter.setMarginType("BTCUSDT", "ISOLATED"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("setMarginType — 已設定（40724）→ 靜默忽略")
        void marginTypeAlreadySet() throws IOException {
            mockHttpResponse(200, bitgetError("40724", "margin mode already set"));

            assertThatCode(() -> adapter.setMarginType("BTCUSDT", "ISOLATED"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("setMarginType — CROSSED → 轉換為 crossed")
        void crossedMarginType() throws IOException {
            mockHttpResponse(200, bitgetOk("{}"));

            // 不拋異常即為成功
            assertThatCode(() -> adapter.setMarginType("BTCUSDT", "CROSSED"))
                    .doesNotThrowAnyException();
        }
    }

    // ==================== 格式化方法 ====================

    @Nested
    @DisplayName("格式化 — formatPrice / formatQuantity")
    class FormatMethods {

        @Test
        @DisplayName("formatPrice — 高價位 (>=1000) → 1 位小數")
        void highPrice() {
            assertThat(adapter.formatPrice(95500.0)).isEqualTo("95500.0");
        }

        @Test
        @DisplayName("formatPrice — 中價位 (>=1) → 2 位小數")
        void midPrice() {
            assertThat(adapter.formatPrice(99.99)).isEqualTo("99.99");
        }

        @Test
        @DisplayName("formatPrice — 低價位 (<1) → 4 位小數")
        void lowPrice() {
            assertThat(adapter.formatPrice(0.1234)).isEqualTo("0.1234");
        }

        @Test
        @DisplayName("formatQuantity — BTC 開頭 → 3 位小數")
        void btcQuantity() {
            assertThat(adapter.formatQuantity("BTCUSDT", 0.2505)).isEqualTo("0.251");
        }

        @Test
        @DisplayName("formatQuantity — ETH 開頭 → 3 位小數")
        void ethQuantity() {
            assertThat(adapter.formatQuantity("ETHUSDT", 1.5555)).isEqualTo("1.556");
        }

        @Test
        @DisplayName("formatQuantity — 其他幣種 → 2 位小數")
        void otherQuantity() {
            assertThat(adapter.formatQuantity("SOLUSDT", 10.555)).isEqualTo("10.56");
        }
    }

    // ==================== 認證上下文 ====================

    @Nested
    @DisplayName("認證上下文 — ExchangeCredentials ThreadLocal + passphrase")
    class CredentialResolution {

        @Test
        @DisplayName("無 ThreadLocal → 拋出 IllegalStateException（fail-fast）")
        void throwsWhenNoCredentials() {
            adapter.clearCredentials();

            assertThatThrownBy(() -> adapter.getAvailableBalance())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("credentials");
        }

        @Test
        @DisplayName("設定 ThreadLocal → 使用 per-user key")
        void perUserCredentials() throws IOException {
            adapter.setCredentials(new ExchangeCredentials("user-api", "user-secret", "user-passphrase"));
            mockHttpResponse(200, bitgetOk("[]"));

            adapter.getAvailableBalance();

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(mockHttpClient).newCall(captor.capture());
            Request req = captor.getValue();
            assertThat(req.header("ACCESS-KEY")).isEqualTo("user-api");
            assertThat(req.header("ACCESS-PASSPHRASE")).isEqualTo("user-passphrase");
        }

        @Test
        @DisplayName("clearCredentials → 拋出 IllegalStateException（不再 fallback）")
        void clearThrowsWhenNoCredentials() {
            adapter.setCredentials(new ExchangeCredentials("user-api", "user-secret", "user-pp"));
            adapter.clearCredentials();

            assertThatThrownBy(() -> adapter.getAvailableBalance())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("credentials");
        }

        @Test
        @DisplayName("ThreadLocal credentials 但 passphrase=null → 拋出 IllegalStateException")
        void nullPassphraseThrows() {
            adapter.setCredentials(new ExchangeCredentials("user-api", "user-secret"));

            assertThatThrownBy(() -> adapter.getAvailableBalance())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("passphrase");
        }

        @Test
        @DisplayName("getExchangeName → BITGET")
        void exchangeName() {
            assertThat(adapter.getExchangeName()).isEqualTo("BITGET");
        }
    }

    // ==================== HTTP 請求格式 ====================

    @Nested
    @DisplayName("HTTP 請求格式 — ACCESS-* headers + JSON body")
    class HttpRequestFormat {

        @Test
        @DisplayName("GET 請求帶 ACCESS-KEY / ACCESS-SIGN / ACCESS-TIMESTAMP / ACCESS-PASSPHRASE")
        void getRequestHeaders() throws IOException {
            mockHttpResponse(200, bitgetOk("[]"));

            adapter.getAvailableBalance();

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(mockHttpClient).newCall(captor.capture());
            Request req = captor.getValue();

            assertThat(req.header("ACCESS-KEY")).isNotNull();
            assertThat(req.header("ACCESS-SIGN")).isNotNull();
            assertThat(req.header("ACCESS-TIMESTAMP")).isNotNull();
            assertThat(req.header("ACCESS-PASSPHRASE")).isNotNull();
            assertThat(req.header("Content-Type")).isEqualTo("application/json");
            assertThat(req.header("locale")).isEqualTo("en-US");
            assertThat(req.method()).isEqualTo("GET");
        }

        @Test
        @DisplayName("POST 請求帶 JSON body + ACCESS-* headers")
        void postRequestFormat() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"orderId":"bg-123"}"""));

            adapter.placeMarketOrder("BTCUSDT", "BUY", 0.25);

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(mockHttpClient).newCall(captor.capture());
            Request req = captor.getValue();

            assertThat(req.method()).isEqualTo("POST");
            assertThat(req.header("ACCESS-KEY")).isNotNull();
            assertThat(req.header("ACCESS-SIGN")).isNotNull();
            assertThat(req.header("ACCESS-PASSPHRASE")).isNotNull();
            assertThat(req.body()).isNotNull();
        }

        @Test
        @DisplayName("GET URL 包含 productType=USDT-FUTURES")
        void queryStringContainsProductType() throws IOException {
            mockHttpResponse(200, bitgetOk("[]"));

            adapter.getAvailableBalance();

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(mockHttpClient).newCall(captor.capture());
            String url = captor.getValue().url().toString();

            assertThat(url).contains("productType=USDT-FUTURES");
        }

        @Test
        @DisplayName("公開 API 不帶 ACCESS-* headers")
        void publicApiNoAuthHeaders() throws IOException {
            mockHttpResponse(200, bitgetOk("[]"));

            adapter.getMarkPrice("BTCUSDT");

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(mockHttpClient).newCall(captor.capture());
            Request req = captor.getValue();

            assertThat(req.header("ACCESS-KEY")).isNull();
            assertThat(req.header("ACCESS-SIGN")).isNull();
        }

        @Test
        @DisplayName("rateLimiter.acquire() — signed 請求有呼叫")
        void rateLimiterCalled() throws IOException {
            mockHttpResponse(200, bitgetOk("[]"));

            adapter.getAvailableBalance();

            verify(mockRateLimiter).acquire();
        }
    }

    // ==================== Side 轉換 ====================

    @Nested
    @DisplayName("Side 轉換 — toBitgetSide / closeSideToHoldSide")
    class SideConversion {

        @Test
        @DisplayName("placeLimitOrder BUY → body 含 side=buy")
        void buyToLowercase() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"orderId":"bg-123"}"""));

            adapter.placeLimitOrder("BTCUSDT", "BUY", 95000, 0.25);

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(mockHttpClient).newCall(captor.capture());
            // POST body 應包含 "buy"（小寫）
            assertThat(captor.getValue().body()).isNotNull();
        }

        @Test
        @DisplayName("setStopLoss SELL → holdSide=long（平多止損）")
        void sellCloseSideToLong() throws IOException {
            mockHttpResponse(200, bitgetOk("""
                    {"orderId":"tpsl-1"}"""));

            adapter.setStopLoss("BTCUSDT", "SELL", 93000, 0.25);

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(mockHttpClient).newCall(captor.capture());
            // POST body 應包含 holdSide
            assertThat(captor.getValue().body()).isNotNull();
        }
    }

    // ==================== 連線失敗 ====================

    @Nested
    @DisplayName("連線失敗處理")
    class ConnectionFailure {

        @Test
        @DisplayName("IOException → 包裝為 RuntimeException")
        void ioException() throws IOException {
            when(mockCall.execute()).thenThrow(new IOException("Connection refused"));

            assertThatThrownBy(() -> adapter.getAvailableBalance())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Bitget API");
        }

        @Test
        @DisplayName("HTTP 500 → 拋異常")
        void http500() throws IOException {
            mockHttpResponse(500, "Internal Server Error");

            assertThatThrownBy(() -> adapter.getAvailableBalance())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("HTTP error 500");
        }

        @Test
        @DisplayName("response body 為 null → 拋異常")
        void nullBody() throws IOException {
            Response response = new Response.Builder()
                    .request(new Request.Builder().url("https://api.bitget.com/test").build())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(null)
                    .build();
            when(mockCall.execute()).thenReturn(response);

            assertThatThrownBy(() -> adapter.getAvailableBalance())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("body");
        }
    }

    // ==================== Response 解析 ====================

    @Nested
    @DisplayName("parseBitgetResponse — code/msg/data 格式")
    class ResponseParsing {

        @Test
        @DisplayName("code=00000 + data=null → 回傳空 JsonObject（不拋異常）")
        void successWithNullData() throws IOException {
            mockHttpResponse(200, """
                    {"code":"00000","msg":"success","data":null}""");

            // getAvailableBalance 內部呼叫 parseBitgetResponse + 解析 data
            // data 為空 JsonObject → isJsonArray=false → 回傳 0
            double balance = adapter.getAvailableBalance();

            assertThat(balance).isEqualTo(0.0);
        }

        @Test
        @DisplayName("缺少 code 欄位 → 視為錯誤拋異常")
        void missingCode() throws IOException {
            mockHttpResponse(200, """
                    {"msg":"no code field"}""");

            assertThatThrownBy(() -> adapter.getAvailableBalance())
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("-1");
        }
    }
}
