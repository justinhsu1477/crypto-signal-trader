package com.trader.trading.exchange.binance;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trader.shared.config.BinanceConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.service.MetricsService;
import com.trader.shared.util.BinanceApiRateLimiter;
import com.trader.shared.util.BinanceSignatureUtil;
import com.trader.trading.exchange.ExchangeAdapter;
import com.trader.trading.exchange.ExchangeCredentials;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Binance Futures API 適配器
 *
 * 純粹負責 Binance API 操作：HTTP 請求、簽名、回應解析。
 * 不包含業務邏輯（風控/DCA/熔斷），業務邏輯留在 BinanceFuturesService（Phase 2 → TradingOrchestrator）。
 *
 * 從 BinanceFuturesService 抽取的方法：
 * - HTTP: sendSignedGet/Post/Delete, buildSignedQueryString, executeRequest
 * - 帳戶: getAccountBalance, getAvailableBalance, getPositions
 * - 持倉: getCurrentPositionAmount, getAllPositionAmounts, getActivePositionCount
 * - 市場: getMarkPrice
 * - 訂單: placeLimitOrder, placeMarketOrder, placeStopLoss, placeTakeProfit
 * - 取消: cancelOrder, cancelAllOrders, cancelSLTPOrders
 * - 查詢: hasOpenEntryOrders, getCurrentSLTPPrices, getOpenOrders, getForceOrders
 * - 配置: setLeverage, setMarginType
 * - ThreadLocal per-user credentials
 */
@Slf4j
@Service("binanceAdapter")
public class BinanceAdapter implements ExchangeAdapter {

    private final OkHttpClient httpClient;
    private final BinanceConfig binanceConfig;
    private final BinanceApiRateLimiter binanceApiRateLimiter;
    private final MetricsService metricsService;
    private final Gson gson = new Gson();

    /**
     * ThreadLocal 暫存 per-user API Key（新版，使用 ExchangeCredentials）
     * 與 BinanceFuturesService 的舊版 ThreadLocal&lt;ExchangeKeys&gt; 平行存在，
     * Phase 5 遷移完成後刪除舊版。
     */
    private static final ThreadLocal<ExchangeCredentials> CURRENT_CREDENTIALS = new ThreadLocal<>();

    // 下單重試配置（Market / Limit / SL / TP 共用）
    private static final int ORDER_MAX_RETRIES = 2;
    private static final long[] ORDER_RETRY_DELAYS_MS = {1000, 3000};

    public BinanceAdapter(OkHttpClient httpClient,
                          BinanceConfig binanceConfig,
                          BinanceApiRateLimiter binanceApiRateLimiter,
                          @Autowired(required = false) MetricsService metricsService) {
        this.httpClient = httpClient;
        this.binanceConfig = binanceConfig;
        this.binanceApiRateLimiter = binanceApiRateLimiter;
        this.metricsService = metricsService;
        log.info("BinanceAdapter 初始化完成，baseUrl={}", binanceConfig.getBaseUrl());
    }

    // ==================== 帳戶查詢 ====================

    @Override
    public double getAvailableBalance() {
        String response = getAccountBalanceRaw();
        try {
            JsonArray balances = gson.fromJson(response, JsonArray.class);
            for (JsonElement elem : balances) {
                JsonObject bal = elem.getAsJsonObject();
                if ("USDT".equals(bal.get("asset").getAsString())) {
                    double available = bal.get("availableBalance").getAsDouble();
                    double wallet = bal.get("balance").getAsDouble();
                    log.debug("Binance USDT balance 原始回傳: walletBalance={}, availableBalance={}, crossUnPnl={}",
                            wallet, available, bal.get("crossUnPnl"));
                    return available;
                }
            }
            throw new RuntimeException("找不到 USDT 餘額");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("查詢帳戶餘額失敗: " + e.getMessage(), e);
        }
    }

    @Override
    public String getAccountBalanceRaw() {
        return sendSignedGet("/fapi/v2/balance", Map.of());
    }

    @Override
    public String getPositionsRaw() {
        return sendSignedGet("/fapi/v2/positionRisk", Map.of());
    }

    @Override
    public String getExchangeInfoRaw() {
        return sendPublicGet("/fapi/v1/exchangeInfo");
    }

    // ==================== 持倉查詢 ====================

    @Override
    public double getCurrentPositionAmount(String symbol) {
        String response = getPositionsRaw();
        try {
            JsonArray positions = gson.fromJson(response, JsonArray.class);
            for (JsonElement elem : positions) {
                JsonObject pos = elem.getAsJsonObject();
                if (pos.get("symbol").getAsString().equals(symbol)) {
                    double positionAmt = pos.get("positionAmt").getAsDouble();
                    if (positionAmt != 0) {
                        log.info("當前持倉: {} {} BTC", symbol, positionAmt);
                        return positionAmt;
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("查詢持倉失敗，拒絕交易: " + e.getMessage(), e);
        }
        return 0;
    }

    @Override
    public Map<String, Double> getAllPositionAmounts() {
        String response = getPositionsRaw();
        Map<String, Double> result = new HashMap<>();
        JsonArray positions = gson.fromJson(response, JsonArray.class);
        for (JsonElement elem : positions) {
            JsonObject pos = elem.getAsJsonObject();
            double amt = pos.get("positionAmt").getAsDouble();
            if (amt != 0) {
                result.put(pos.get("symbol").getAsString(), amt);
            }
        }
        return result;
    }

    @Override
    public int getActivePositionCount() {
        String response = getPositionsRaw();
        int count = 0;
        try {
            JsonArray positions = gson.fromJson(response, JsonArray.class);
            for (JsonElement elem : positions) {
                JsonObject pos = elem.getAsJsonObject();
                double positionAmt = pos.get("positionAmt").getAsDouble();
                if (positionAmt != 0) {
                    count++;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("查詢持倉數量失敗，拒絕交易: " + e.getMessage(), e);
        }
        return count;
    }

    // ==================== 市場數據 ====================

    @Override
    public double getMarkPrice(String symbol) {
        String response = sendPublicGet("/fapi/v1/ticker/price?symbol=" + symbol);
        try {
            JsonObject json = gson.fromJson(response, JsonObject.class);
            return json.get("price").getAsDouble();
        } catch (Exception e) {
            throw new RuntimeException("取得市價失敗，拒絕交易: " + e.getMessage(), e);
        }
    }

    // ==================== 訂單操作 ====================

    @Override
    public OrderResult placeLimitOrder(String symbol, String side, double price, double quantity) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", "LIMIT");
        params.put("timeInForce", "GTC");
        params.put("price", formatPrice(price));
        params.put("quantity", formatQuantity(symbol, quantity));
        params.put("newClientOrderId", generateClientOrderId("LMT"));

        log.info("下限價單: {} {} {} @ {}", symbol, side, quantity, price);
        long start = System.currentTimeMillis();
        String response = sendSignedPostWithRetry("/fapi/v1/order", params, "newClientOrderId");
        OrderResult result = parseOrderResponse(response);
        if (metricsService != null) {
            metricsService.recordApiLatency("placeLimitOrder", System.currentTimeMillis() - start);
            metricsService.recordOrder("LIMIT", result.isSuccess());
        }
        return result;
    }

    @Override
    public OrderResult placeMarketOrder(String symbol, String side, double quantity) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", "MARKET");
        params.put("newOrderRespType", "RESULT");
        params.put("quantity", formatQuantity(symbol, quantity));
        params.put("newClientOrderId", generateClientOrderId("MKT"));

        log.info("下市價單: {} {} {}", symbol, side, quantity);
        long start = System.currentTimeMillis();
        String response = sendSignedPostWithRetry("/fapi/v1/order", params, "newClientOrderId");
        OrderResult result = parseOrderResponse(response);
        if (metricsService != null) {
            metricsService.recordApiLatency("placeMarketOrder", System.currentTimeMillis() - start);
            metricsService.recordOrder("MARKET", result.isSuccess());
        }
        return result;
    }

    @Override
    public OrderResult setStopLoss(String symbol, String closeSide, double triggerPrice, double quantity) {
        String formattedQty = formatQuantity(symbol, quantity);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", closeSide);
        params.put("type", "STOP_MARKET");
        params.put("algoType", "CONDITIONAL");
        params.put("triggerPrice", formatPrice(triggerPrice));
        params.put("quantity", formattedQty);
        params.put("clientAlgoId", generateClientOrderId("SL"));

        log.info("設定止損 (Algo): {} {} triggerPrice={}", symbol, closeSide, triggerPrice);
        long start = System.currentTimeMillis();
        String response = sendSignedPostWithRetry("/fapi/v1/algoOrder", params, "clientAlgoId");
        OrderResult result = parseAlgoOrderResponse(response, symbol, closeSide, "STOP_MARKET", triggerPrice,
                Double.parseDouble(formattedQty));
        if (metricsService != null) {
            metricsService.recordApiLatency("placeStopLoss", System.currentTimeMillis() - start);
            metricsService.recordOrder("SL", result.isSuccess());
        }
        return result;
    }

    @Override
    public OrderResult setTakeProfit(String symbol, String closeSide, double triggerPrice, double quantity) {
        String formattedQty = formatQuantity(symbol, quantity);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", closeSide);
        params.put("type", "TAKE_PROFIT_MARKET");
        params.put("algoType", "CONDITIONAL");
        params.put("triggerPrice", formatPrice(triggerPrice));
        params.put("quantity", formattedQty);
        params.put("clientAlgoId", generateClientOrderId("TP"));

        log.info("設定止盈 (Algo): {} {} triggerPrice={}", symbol, closeSide, triggerPrice);
        long start = System.currentTimeMillis();
        String response = sendSignedPostWithRetry("/fapi/v1/algoOrder", params, "clientAlgoId");
        OrderResult result = parseAlgoOrderResponse(response, symbol, closeSide, "TAKE_PROFIT_MARKET", triggerPrice,
                Double.parseDouble(formattedQty));
        if (metricsService != null) {
            metricsService.recordApiLatency("placeTakeProfit", System.currentTimeMillis() - start);
            metricsService.recordOrder("TP", result.isSuccess());
        }
        return result;
    }

    @Override
    public void cancelOrder(String symbol, String orderId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("orderId", orderId);
        sendSignedDelete("/fapi/v1/order", params);
    }

    /**
     * 取消所有訂單：標準訂單 + Algo 訂單（SL/TP）
     * 標準 allOpenOrders 不包含 Algo 訂單，需額外取消
     *
     * ⚠️ Phase 1 note: 原 BinanceFuturesService 的通知邏輯（SL/TP 取消失敗時的 Discord 告警）
     * 已移至 BinanceFuturesService 的業務層處理。Adapter 只負責 API 操作和日誌記錄。
     */
    @Override
    public void cancelAllOrders(String symbol) {
        // 1. 取消標準訂單 (LIMIT 入場單等)
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        sendSignedDelete("/fapi/v1/allOpenOrders", params);

        // 2. 取消 Algo 訂單 (SL/TP)
        try {
            cancelSLTPOrders(symbol);
        } catch (Exception e) {
            log.error("cancelAllOrders: 取消 Algo SL/TP 失敗: {}", e.getMessage());
            // 不 re-throw：標準訂單已取消成功，SL/TP 失敗由上層業務邏輯處理
        }
    }

    /**
     * 只取消 STOP_MARKET 和 TAKE_PROFIT_MARKET Algo 訂單，保留 LIMIT 入場單
     * 用於 DCA 補倉時：需要更新 SL/TP 但不能取消已掛的入場單
     */
    @Override
    public void cancelSLTPOrders(String symbol) {
        String response = getOpenAlgoOrders(symbol);
        try {
            JsonArray orders = parseAlgoOrdersResponse(response);
            if (orders == null || orders.isEmpty()) return;

            int failCount = 0;
            for (JsonElement elem : orders) {
                JsonObject order = elem.getAsJsonObject();
                String type = order.has("orderType") ? order.get("orderType").getAsString() : "";
                if ("STOP_MARKET".equals(type) || "TAKE_PROFIT_MARKET".equals(type)) {
                    if (!order.has("algoId")) {
                        log.warn("Algo 訂單缺少 algoId，跳過: {}", order);
                        failCount++;
                        continue;
                    }
                    long algoId = order.get("algoId").getAsLong();
                    try {
                        log.info("取消舊的 {} Algo 訂單 algoId={}", type, algoId);
                        cancelAlgoOrder(symbol, algoId);
                    } catch (Exception e) {
                        log.error("取消 {} Algo 訂單 algoId={} 失敗: {}", type, algoId, e.getMessage());
                        failCount++;
                    }
                }
            }
            if (failCount > 0) {
                String msg = String.format("取消 SL/TP Algo 訂單部分失敗: %d 筆失敗", failCount);
                log.error(msg);
                // Phase 1 note: 通知邏輯已移至 BinanceFuturesService 業務層
            }
        } catch (Exception e) {
            log.error("取消 SL/TP Algo 訂單失敗: {}", e.getMessage());
            throw new RuntimeException("取消 SL/TP Algo 訂單失敗: " + e.getMessage(), e);
        }
    }

    // ==================== 查詢訂單 ====================

    @Override
    public boolean hasOpenEntryOrders(String symbol) {
        String response = getOpenOrdersRaw(symbol);
        try {
            JsonArray orders = gson.fromJson(response, JsonArray.class);
            for (JsonElement elem : orders) {
                JsonObject order = elem.getAsJsonObject();
                String type = order.get("type").getAsString();
                if ("LIMIT".equals(type)) {
                    return true;
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("檢查掛單失敗，拒絕交易: " + e.getMessage(), e);
        }
        return false;
    }

    @Override
    public double[] getCurrentSLTPPrices(String symbol) {
        double slPrice = 0;
        double tpPrice = 0;
        try {
            String response = getOpenAlgoOrders(symbol);
            JsonArray orders = parseAlgoOrdersResponse(response);
            if (orders != null) {
                for (JsonElement elem : orders) {
                    JsonObject order = elem.getAsJsonObject();
                    String type = order.has("orderType") ? order.get("orderType").getAsString() : "";
                    if ("STOP_MARKET".equals(type) && order.has("triggerPrice")) {
                        slPrice = Double.parseDouble(order.get("triggerPrice").getAsString());
                    } else if ("TAKE_PROFIT_MARKET".equals(type) && order.has("triggerPrice")) {
                        tpPrice = Double.parseDouble(order.get("triggerPrice").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            log.error("查詢 SL/TP 價格失敗: {}", e.getMessage());
        }
        return new double[]{slPrice, tpPrice};
    }

    @Override
    public String getOpenOrdersRaw(String symbol) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        return sendSignedGet("/fapi/v1/openOrders", params);
    }

    @Override
    public String getForceOrdersRaw() {
        return sendSignedGet("/fapi/v1/forceOrders", Map.of());
    }

    // ==================== 帳戶配置 ====================

    @Override
    public void setLeverage(String symbol, int leverage) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("leverage", String.valueOf(leverage));
        log.info("設定槓桿: {} x{}", symbol, leverage);
        sendSignedPost("/fapi/v1/leverage", params);
    }

    @Override
    public void setMarginType(String symbol, String marginType) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("marginType", marginType);
        log.info("設定保證金模式: {} {}", symbol, marginType);
        sendSignedPost("/fapi/v1/marginType", params);
    }

    /**
     * 設定持倉模式（Binance 特有，非 ExchangeAdapter 介面方法）
     */
    public void setPositionMode(boolean dualSidePosition) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dualSidePosition", String.valueOf(dualSidePosition));
        sendSignedPost("/fapi/v1/positionSide/dual", params);
    }

    // ==================== 格式化 ====================

    @Override
    public String formatPrice(double price) {
        if (price >= 1000) {
            return String.format(Locale.US, "%.1f", price);
        } else if (price >= 1) {
            return String.format(Locale.US, "%.2f", price);
        } else {
            return String.format(Locale.US, "%.4f", price);
        }
    }

    @Override
    public String formatQuantity(String symbol, double quantity) {
        if (symbol.startsWith("BTC") || symbol.startsWith("ETH")) {
            return String.format(Locale.US, "%.3f", quantity);
        } else {
            return String.format(Locale.US, "%.2f", quantity);
        }
    }

    // ==================== 認證上下文 ====================

    @Override
    public void setCredentials(ExchangeCredentials credentials) {
        CURRENT_CREDENTIALS.set(credentials);
    }

    @Override
    public void clearCredentials() {
        CURRENT_CREDENTIALS.remove();
    }

    @Override
    public String getExchangeName() {
        return "BINANCE";
    }

    // ==================== 內部方法：Algo 訂單 ====================

    /**
     * 查詢 Algo 掛單（SL/TP 已遷移至 Algo Order API）
     */
    private String getOpenAlgoOrders(String symbol) {
        Map<String, String> params = new LinkedHashMap<>();
        if (symbol != null) {
            params.put("symbol", symbol);
        }
        params.put("algoType", "CONDITIONAL");
        return sendSignedGet("/fapi/v1/openAlgoOrders", params);
    }

    /**
     * 取消 Algo 訂單（SL/TP）
     */
    private void cancelAlgoOrder(String symbol, long algoId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("algoId", String.valueOf(algoId));
        sendSignedDelete("/fapi/v1/algoOrder", params);
    }

    // ==================== 內部方法：回應解析 ====================

    private OrderResult parseOrderResponse(String response) {
        try {
            JsonObject json = gson.fromJson(response, JsonObject.class);

            if (json.has("code") && json.get("code").getAsInt() != 200) {
                return OrderResult.fail(json.has("msg") ? json.get("msg").getAsString() : response);
            }

            // MARKET 單 Binance 回傳 price=0，需用 avgPrice 取得實際成交價
            double price = json.has("price") ? json.get("price").getAsDouble() : 0;
            if (price == 0 && json.has("avgPrice")) {
                price = json.get("avgPrice").getAsDouble();
            }
            // 最後嘗試從 fills 陣列計算加權均價（RESULT 模式回傳）
            if (price == 0 && json.has("fills") && json.getAsJsonArray("fills").size() > 0) {
                double totalQty = 0, totalNotional = 0;
                for (var fill : json.getAsJsonArray("fills")) {
                    JsonObject f = fill.getAsJsonObject();
                    double fPrice = f.get("price").getAsDouble();
                    double fQty = f.get("qty").getAsDouble();
                    totalQty += fQty;
                    totalNotional += fPrice * fQty;
                }
                if (totalQty > 0) price = totalNotional / totalQty;
            }

            return OrderResult.builder()
                    .success(true)
                    .orderId(json.has("orderId") ? json.get("orderId").getAsString() : "")
                    .symbol(json.has("symbol") ? json.get("symbol").getAsString() : "")
                    .side(json.has("side") ? json.get("side").getAsString() : "")
                    .type(json.has("type") ? json.get("type").getAsString() : "")
                    .price(price)
                    .quantity(json.has("origQty") ? json.get("origQty").getAsDouble() : 0)
                    .commission(json.has("cumCommission") ? json.get("cumCommission").getAsDouble() : 0)
                    .rawResponse(response)
                    .build();
        } catch (Exception e) {
            return OrderResult.fail("Failed to parse response: " + response);
        }
    }

    /**
     * 解析 Algo Order API 回應
     * 成功回應格式: {"algoId":2146760, "clientAlgoId":"xxx", "algoType":"CONDITIONAL", ...}
     * 失敗回應格式: {"code":-2021, "msg":"Order would immediately trigger."}
     */
    private OrderResult parseAlgoOrderResponse(String response, String symbol, String side,
                                                String type, double triggerPrice, double quantity) {
        try {
            JsonObject json = gson.fromJson(response, JsonObject.class);

            if (json.has("code")) {
                int code = json.get("code").getAsInt();
                String msg = json.has("msg") ? json.get("msg").getAsString() : "";
                if (code < 0) {
                    return OrderResult.fail("Algo order failed [" + code + "]: " + msg);
                }
            }

            if (!json.has("algoId")) {
                return OrderResult.fail("Algo order response missing algoId: " + response);
            }

            String algoId = String.valueOf(json.get("algoId").getAsLong());

            return OrderResult.builder()
                    .success(true)
                    .orderId(algoId)
                    .symbol(symbol)
                    .side(side)
                    .type(type)
                    .price(triggerPrice)
                    .quantity(quantity)
                    .rawResponse(response)
                    .build();
        } catch (Exception e) {
            return OrderResult.fail("Failed to parse algo order response: " + response);
        }
    }

    /**
     * 解析 GET /fapi/v1/openAlgoOrders 的回應
     * 成功時回傳 JSON 陣列 [{...}, {...}]
     * 失敗時回傳 {"code":-xxx, "msg":"..."}
     */
    private JsonArray parseAlgoOrdersResponse(String response) {
        JsonElement element = gson.fromJson(response, JsonElement.class);

        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            if (obj.has("code") && obj.get("code").getAsInt() < 0) {
                String msg = obj.has("msg") ? obj.get("msg").getAsString() : "";
                throw new RuntimeException("查詢 Algo 訂單失敗 [" + obj.get("code").getAsInt() + "]: " + msg);
            }
            log.warn("openAlgoOrders 回傳非預期格式: {}", response);
            return null;
        }

        if (element.isJsonArray()) {
            return element.getAsJsonArray();
        }

        log.warn("openAlgoOrders 回傳無法解析: {}", response);
        return null;
    }

    // ==================== 內部方法：HTTP 請求 ====================

    private String sendPublicGet(String endpoint) {
        String url = binanceConfig.getBaseUrl() + endpoint;
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        return executeRequest(request);
    }

    private String sendSignedGet(String endpoint, Map<String, String> params) {
        String queryString = buildSignedQueryString(params);
        String url = binanceConfig.getBaseUrl() + endpoint + "?" + queryString;

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("X-MBX-APIKEY", getActiveApiKey())
                .build();
        return executeRequest(request);
    }

    private String sendSignedPost(String endpoint, Map<String, String> params) {
        String queryString = buildSignedQueryString(params);
        String url = binanceConfig.getBaseUrl() + endpoint;

        RequestBody body = RequestBody.create(
                queryString, MediaType.parse("application/x-www-form-urlencoded"));

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("X-MBX-APIKEY", getActiveApiKey())
                .build();
        return executeRequest(request);
    }

    private String sendSignedDelete(String endpoint, Map<String, String> params) {
        String queryString = buildSignedQueryString(params);
        String url = binanceConfig.getBaseUrl() + endpoint + "?" + queryString;

        Request request = new Request.Builder()
                .url(url)
                .delete()
                .addHeader("X-MBX-APIKEY", getActiveApiKey())
                .build();
        return executeRequest(request);
    }

    /**
     * 帶 idempotent key 的下單重試（Market / Limit / SL / TP 共用）
     * 用 newClientOrderId/clientAlgoId 確保 Binance 不會重複成交
     * 只有 IOException（網路斷線/timeout）才重試，收到 HTTP 回應（含 4xx/5xx）不重試
     */
    private String sendSignedPostWithRetry(String endpoint, Map<String, String> params, String clientIdKey) {
        String clientOrderId = params.get(clientIdKey);
        IOException lastException = null;

        for (int attempt = 0; attempt <= ORDER_MAX_RETRIES; attempt++) {
            try {
                binanceApiRateLimiter.acquire();

                String queryString = buildSignedQueryString(params);
                String url = binanceConfig.getBaseUrl() + endpoint;
                RequestBody body = RequestBody.create(
                        queryString, MediaType.parse("application/x-www-form-urlencoded"));
                Request request = new Request.Builder()
                        .url(url).post(body)
                        .addHeader("X-MBX-APIKEY", getActiveApiKey())
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String usedWeight = response.header("X-MBX-USED-WEIGHT-1M");
                    if (usedWeight != null) {
                        try {
                            binanceApiRateLimiter.updateFromHeader(Integer.parseInt(usedWeight));
                        } catch (NumberFormatException ignored) {}
                    }

                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        log.error("Binance API error: {} - {}", response.code(), responseBody);
                    }
                    return responseBody;
                }
            } catch (IOException e) {
                lastException = e;
                log.warn("下單網路失敗 (attempt {}/{}): clientOrderId={}, error={}",
                        attempt + 1, ORDER_MAX_RETRIES + 1, clientOrderId, e.getMessage());
                if (attempt < ORDER_MAX_RETRIES) {
                    try {
                        Thread.sleep(ORDER_RETRY_DELAYS_MS[attempt]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // 全部重試用完
        log.error("Binance 下單重試 {} 次全部失敗！clientOrderId: {}, error: {}",
                ORDER_MAX_RETRIES + 1, clientOrderId,
                lastException != null ? lastException.getMessage() : "unknown");
        throw new RuntimeException("Binance order request failed after " + (ORDER_MAX_RETRIES + 1) + " retries",
                lastException);
    }

    private String buildSignedQueryString(Map<String, String> params) {
        Map<String, String> allParams = new LinkedHashMap<>(params);
        allParams.put("timestamp", String.valueOf(System.currentTimeMillis()));

        String queryString = allParams.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));

        String signature = BinanceSignatureUtil.sign(queryString, getActiveSecretKey());
        return queryString + "&signature=" + signature;
    }

    private String executeRequest(Request request) {
        binanceApiRateLimiter.acquire();

        try (Response response = httpClient.newCall(request).execute()) {
            String usedWeight = response.header("X-MBX-USED-WEIGHT-1M");
            if (usedWeight != null) {
                try {
                    binanceApiRateLimiter.updateFromHeader(Integer.parseInt(usedWeight));
                } catch (NumberFormatException ignored) {}
            }

            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Binance API error: {} {} - {}", request.method(), request.url().encodedPath(), body);
                String errorMsg = parseBinanceError(body, response.code());
                throw new RuntimeException(errorMsg);
            }
            return body;
        } catch (RuntimeException e) {
            throw e;
        } catch (IOException e) {
            log.error("HTTP request failed: {}", e.getMessage(), e);
            throw new RuntimeException("Binance API request failed", e);
        }
    }

    // ==================== 內部方法：工具 ====================

    /**
     * 產生 Binance newClientOrderId（冪等性 key）
     * 格式: {prefix}-{timestamp}-{random4hex}
     */
    private String generateClientOrderId(String prefix) {
        String ts = String.valueOf(System.currentTimeMillis());
        String rand = Integer.toHexString((int) (Math.random() * 0xFFFF));
        return String.format("%s-%s-%s", prefix, ts, rand);
    }

    /**
     * 解析 Binance API 錯誤回應
     */
    private String parseBinanceError(String body, int httpCode) {
        try {
            JsonObject error = gson.fromJson(body, JsonObject.class);
            int code = error.has("code") ? error.get("code").getAsInt() : httpCode;
            String msg = error.has("msg") ? error.get("msg").getAsString() : body;
            return String.format("Binance API 錯誤 [%d]: %s", code, msg);
        } catch (Exception e) {
            return String.format("Binance API HTTP %d: %s", httpCode, body);
        }
    }

    /**
     * 取得當前生效的 API Key
     * 優先順序：ExchangeCredentials ThreadLocal → BinanceFuturesService 舊版 ThreadLocal → 全局 Config
     */
    private String getActiveApiKey() {
        // 1. 新版 ExchangeCredentials
        ExchangeCredentials creds = CURRENT_CREDENTIALS.get();
        if (creds != null) return creds.apiKey();

        // 2. 舊版 BinanceFuturesService ThreadLocal（Phase 5 前向後相容）
        var legacyKeys = com.trader.trading.service.BinanceFuturesService.getCurrentUserKeys();
        if (legacyKeys != null) return legacyKeys.apiKey();

        // 3. 全局 Config
        return binanceConfig.getApiKey();
    }

    /**
     * 取得當前生效的 Secret Key
     * 優先順序：ExchangeCredentials ThreadLocal → BinanceFuturesService 舊版 ThreadLocal → 全局 Config
     */
    private String getActiveSecretKey() {
        // 1. 新版 ExchangeCredentials
        ExchangeCredentials creds = CURRENT_CREDENTIALS.get();
        if (creds != null) return creds.secretKey();

        // 2. 舊版 BinanceFuturesService ThreadLocal（Phase 5 前向後相容）
        var legacyKeys = com.trader.trading.service.BinanceFuturesService.getCurrentUserKeys();
        if (legacyKeys != null) return legacyKeys.secretKey();

        // 3. 全局 Config
        return binanceConfig.getSecretKey();
    }
}
