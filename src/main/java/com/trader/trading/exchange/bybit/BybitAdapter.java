package com.trader.trading.exchange.bybit;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trader.shared.config.BybitConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.service.MetricsService;
import com.trader.shared.util.BybitApiRateLimiter;
import com.trader.shared.util.BybitSignatureUtil;
import com.trader.trading.exchange.ExchangeAdapter;
import com.trader.trading.exchange.ExchangeCredentials;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bybit V5 Linear Perpetual API 適配器
 *
 * 純粹負責 Bybit V5 API 操作：HTTP 請求、簽名、回應解析。
 * 不包含業務邏輯（風控/DCA/熔斷），業務邏輯留在 TradingOrchestrator。
 *
 * Bybit V5 與 Binance 差異：
 * - 所有請求帶 category: "linear"
 * - Side 格式: "Buy"/"Sell"（PascalCase），本 Adapter 內部轉換
 * - 持倉: size（無號）+ side 組合為有號數量
 * - 回應: 統一 {"retCode":0, "retMsg":"OK", "result":{...}}
 * - SL/TP: 透過 /v5/position/trading-stop 設定（position-level），非獨立訂單
 * - 簽名: HMAC(timestamp + apiKey + recvWindow + payload, secret) → header
 */
@Slf4j
@Service("bybitAdapter")
@ConditionalOnProperty(name = "bybit.linear.api-key")
public class BybitAdapter implements ExchangeAdapter {

    private final OkHttpClient httpClient;
    private final BybitConfig bybitConfig;
    private final BybitApiRateLimiter rateLimiter;
    private final MetricsService metricsService;
    private final Gson gson = new Gson();

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");

    /**
     * ThreadLocal 暫存 per-user API Key
     */
    private static final ThreadLocal<ExchangeCredentials> CURRENT_CREDENTIALS = new ThreadLocal<>();

    // 下單重試配置（Market / Limit 共用）
    private static final int ORDER_MAX_RETRIES = 2;
    private static final long[] ORDER_RETRY_DELAYS_MS = {1000, 3000};

    public BybitAdapter(OkHttpClient httpClient,
                        BybitConfig bybitConfig,
                        BybitApiRateLimiter rateLimiter,
                        @Autowired(required = false) MetricsService metricsService) {
        this.httpClient = httpClient;
        this.bybitConfig = bybitConfig;
        this.rateLimiter = rateLimiter;
        this.metricsService = metricsService;
        log.info("BybitAdapter 初始化完成，baseUrl={}", bybitConfig.getBaseUrl());
    }

    // ==================== 帳戶查詢 ====================

    @Override
    public double getAvailableBalance() {
        String response = getAccountBalanceRaw();
        try {
            JsonObject result = parseBybitResponse(response);
            JsonArray list = result.getAsJsonArray("list");
            if (list == null || list.isEmpty()) {
                throw new RuntimeException("找不到 Bybit 帳戶資訊");
            }
            // UNIFIED 帳戶，取第一個帳戶的 coin 列表
            JsonObject account = list.get(0).getAsJsonObject();
            JsonArray coins = account.getAsJsonArray("coin");
            if (coins != null) {
                for (JsonElement coinElem : coins) {
                    JsonObject coin = coinElem.getAsJsonObject();
                    String coinName = coin.has("coin") ? coin.get("coin").getAsString() : "";
                    if ("USDT".equals(coinName)) {
                        double available = parseDoubleOrZero(coin.get("availableToWithdraw").getAsString());
                        double wallet = parseDoubleOrZero(coin.get("walletBalance").getAsString());
                        log.debug("Bybit USDT balance: walletBalance={}, availableToWithdraw={}", wallet, available);
                        return available;
                    }
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
        return sendSignedGet("/v5/account/wallet-balance", Map.of("accountType", "UNIFIED"));
    }

    @Override
    public String getPositionsRaw() {
        return sendSignedGet("/v5/position/list", Map.of("category", "linear"));
    }

    @Override
    public String getExchangeInfoRaw() {
        return sendPublicGet("/v5/market/instruments-info?category=linear");
    }

    // ==================== 持倉查詢 ====================

    @Override
    public double getCurrentPositionAmount(String symbol) {
        String response = sendSignedGet("/v5/position/list",
                Map.of("category", "linear", "symbol", symbol));
        try {
            JsonObject result = parseBybitResponse(response);
            JsonArray list = result.getAsJsonArray("list");
            if (list != null) {
                for (JsonElement elem : list) {
                    JsonObject pos = elem.getAsJsonObject();
                    String posSymbol = pos.has("symbol") ? pos.get("symbol").getAsString() : "";
                    if (posSymbol.equals(symbol)) {
                        double size = parseDoubleOrZero(pos.get("size").getAsString());
                        if (size == 0) return 0;
                        String side = pos.has("side") ? pos.get("side").getAsString() : "";
                        double signedAmount = size * ("Buy".equals(side) ? 1 : -1);
                        log.info("當前持倉: {} {} (side={})", symbol, signedAmount, side);
                        return signedAmount;
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
        Map<String, Double> positions = new HashMap<>();
        try {
            JsonObject result = parseBybitResponse(response);
            JsonArray list = result.getAsJsonArray("list");
            if (list != null) {
                for (JsonElement elem : list) {
                    JsonObject pos = elem.getAsJsonObject();
                    double size = parseDoubleOrZero(pos.get("size").getAsString());
                    if (size != 0) {
                        String symbol = pos.get("symbol").getAsString();
                        String side = pos.has("side") ? pos.get("side").getAsString() : "";
                        double signedAmount = size * ("Buy".equals(side) ? 1 : -1);
                        positions.put(symbol, signedAmount);
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("查詢所有持倉失敗: " + e.getMessage(), e);
        }
        return positions;
    }

    @Override
    public int getActivePositionCount() {
        String response = getPositionsRaw();
        int count = 0;
        try {
            JsonObject result = parseBybitResponse(response);
            JsonArray list = result.getAsJsonArray("list");
            if (list != null) {
                for (JsonElement elem : list) {
                    JsonObject pos = elem.getAsJsonObject();
                    double size = parseDoubleOrZero(pos.get("size").getAsString());
                    if (size != 0) count++;
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
        String response = sendPublicGet("/v5/market/tickers?category=linear&symbol=" + symbol);
        try {
            JsonObject result = parseBybitResponse(response);
            JsonArray list = result.getAsJsonArray("list");
            if (list == null || list.isEmpty()) {
                throw new RuntimeException("找不到 " + symbol + " 的行情數據");
            }
            String markPrice = list.get(0).getAsJsonObject().get("markPrice").getAsString();
            return Double.parseDouble(markPrice);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("取得市價失敗，拒絕交易: " + e.getMessage(), e);
        }
    }

    // ==================== 訂單操作 ====================

    @Override
    public OrderResult placeLimitOrder(String symbol, String side, double price, double quantity) {
        JsonObject body = new JsonObject();
        body.addProperty("category", "linear");
        body.addProperty("symbol", symbol);
        body.addProperty("side", toBybitSide(side));
        body.addProperty("orderType", "Limit");
        body.addProperty("qty", formatQuantity(symbol, quantity));
        body.addProperty("price", formatPrice(price));
        body.addProperty("timeInForce", "GTC");

        log.info("Bybit 下限價單: {} {} {} @ {}", symbol, side, quantity, price);
        long start = System.currentTimeMillis();
        String response = sendSignedPostWithRetry("/v5/order/create", gson.toJson(body));
        OrderResult result = parseOrderResult(response);
        if (metricsService != null) {
            metricsService.recordApiLatency("placeLimitOrder", System.currentTimeMillis() - start);
            metricsService.recordOrder("LIMIT", result.isSuccess());
        }
        return result;
    }

    @Override
    public OrderResult placeMarketOrder(String symbol, String side, double quantity) {
        JsonObject body = new JsonObject();
        body.addProperty("category", "linear");
        body.addProperty("symbol", symbol);
        body.addProperty("side", toBybitSide(side));
        body.addProperty("orderType", "Market");
        body.addProperty("qty", formatQuantity(symbol, quantity));

        log.info("Bybit 下市價單: {} {} {}", symbol, side, quantity);
        long start = System.currentTimeMillis();
        String response = sendSignedPostWithRetry("/v5/order/create", gson.toJson(body));
        OrderResult result = parseOrderResult(response);
        if (metricsService != null) {
            metricsService.recordApiLatency("placeMarketOrder", System.currentTimeMillis() - start);
            metricsService.recordOrder("MARKET", result.isSuccess());
        }
        return result;
    }

    /**
     * 設定止損 — Bybit 使用 position-level trading-stop
     * closeSide 和 quantity 參數保留以符合介面，但 Bybit 的 trading-stop 直接作用於 position，
     * 不需要指定 side/quantity。
     */
    @Override
    public OrderResult setStopLoss(String symbol, String closeSide, double triggerPrice, double quantity) {
        JsonObject body = new JsonObject();
        body.addProperty("category", "linear");
        body.addProperty("symbol", symbol);
        body.addProperty("stopLoss", formatPrice(triggerPrice));
        body.addProperty("slTriggerBy", "MarkPrice");
        body.addProperty("positionIdx", 0); // one-way mode

        log.info("Bybit 設定止損: {} triggerPrice={}", symbol, triggerPrice);
        long start = System.currentTimeMillis();
        try {
            String response = sendSignedPost("/v5/position/trading-stop", gson.toJson(body));
            parseBybitResponse(response); // 驗證 retCode == 0
            if (metricsService != null) {
                metricsService.recordApiLatency("placeStopLoss", System.currentTimeMillis() - start);
                metricsService.recordOrder("SL", true);
            }
            return OrderResult.builder()
                    .success(true)
                    .symbol(symbol)
                    .side(closeSide.toUpperCase())
                    .type("STOP_LOSS")
                    .price(triggerPrice)
                    .quantity(quantity)
                    .rawResponse(response)
                    .build();
        } catch (RuntimeException e) {
            if (metricsService != null) {
                metricsService.recordOrder("SL", false);
            }
            return OrderResult.fail("Bybit 設定止損失敗: " + e.getMessage());
        }
    }

    /**
     * 設定止盈 — Bybit 使用 position-level trading-stop
     */
    @Override
    public OrderResult setTakeProfit(String symbol, String closeSide, double triggerPrice, double quantity) {
        JsonObject body = new JsonObject();
        body.addProperty("category", "linear");
        body.addProperty("symbol", symbol);
        body.addProperty("takeProfit", formatPrice(triggerPrice));
        body.addProperty("tpTriggerBy", "MarkPrice");
        body.addProperty("positionIdx", 0); // one-way mode

        log.info("Bybit 設定止盈: {} triggerPrice={}", symbol, triggerPrice);
        long start = System.currentTimeMillis();
        try {
            String response = sendSignedPost("/v5/position/trading-stop", gson.toJson(body));
            parseBybitResponse(response); // 驗證 retCode == 0
            if (metricsService != null) {
                metricsService.recordApiLatency("placeTakeProfit", System.currentTimeMillis() - start);
                metricsService.recordOrder("TP", true);
            }
            return OrderResult.builder()
                    .success(true)
                    .symbol(symbol)
                    .side(closeSide.toUpperCase())
                    .type("TAKE_PROFIT")
                    .price(triggerPrice)
                    .quantity(quantity)
                    .rawResponse(response)
                    .build();
        } catch (RuntimeException e) {
            if (metricsService != null) {
                metricsService.recordOrder("TP", false);
            }
            return OrderResult.fail("Bybit 設定止盈失敗: " + e.getMessage());
        }
    }

    @Override
    public void cancelOrder(String symbol, String orderId) {
        JsonObject body = new JsonObject();
        body.addProperty("category", "linear");
        body.addProperty("symbol", symbol);
        body.addProperty("orderId", orderId);
        String response = sendSignedPost("/v5/order/cancel", gson.toJson(body));
        parseBybitResponse(response);
    }

    @Override
    public void cancelAllOrders(String symbol) {
        JsonObject body = new JsonObject();
        body.addProperty("category", "linear");
        body.addProperty("symbol", symbol);
        String response = sendSignedPost("/v5/order/cancel-all", gson.toJson(body));
        parseBybitResponse(response);
    }

    /**
     * 清除 SL/TP — Bybit position-level trading-stop 透過設為 "0" 清除
     */
    @Override
    public void cancelSLTPOrders(String symbol) {
        JsonObject body = new JsonObject();
        body.addProperty("category", "linear");
        body.addProperty("symbol", symbol);
        body.addProperty("stopLoss", "0");
        body.addProperty("takeProfit", "0");
        body.addProperty("positionIdx", 0);

        log.info("Bybit 清除 SL/TP: {}", symbol);
        String response = sendSignedPost("/v5/position/trading-stop", gson.toJson(body));
        parseBybitResponse(response);
    }

    // ==================== 查詢訂單 ====================

    @Override
    public boolean hasOpenEntryOrders(String symbol) {
        String response = getOpenOrdersRaw(symbol);
        try {
            JsonObject result = parseBybitResponse(response);
            JsonArray list = result.getAsJsonArray("list");
            if (list != null) {
                for (JsonElement elem : list) {
                    JsonObject order = elem.getAsJsonObject();
                    String orderType = order.has("orderType") ? order.get("orderType").getAsString() : "";
                    String status = order.has("orderStatus") ? order.get("orderStatus").getAsString() : "";
                    if ("Limit".equals(orderType) && "New".equals(status)) {
                        return true;
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("檢查掛單失敗，拒絕交易: " + e.getMessage(), e);
        }
        return false;
    }

    /**
     * 查詢當前 SL/TP 價格 — Bybit 從 position 資訊中讀取
     * @return [0]=SL, [1]=TP, 0 表示不存在
     */
    @Override
    public double[] getCurrentSLTPPrices(String symbol) {
        double slPrice = 0;
        double tpPrice = 0;
        try {
            String response = sendSignedGet("/v5/position/list",
                    Map.of("category", "linear", "symbol", symbol));
            JsonObject result = parseBybitResponse(response);
            JsonArray list = result.getAsJsonArray("list");
            if (list != null && !list.isEmpty()) {
                JsonObject pos = list.get(0).getAsJsonObject();
                if (pos.has("stopLoss")) {
                    slPrice = parseDoubleOrZero(pos.get("stopLoss").getAsString());
                }
                if (pos.has("takeProfit")) {
                    tpPrice = parseDoubleOrZero(pos.get("takeProfit").getAsString());
                }
            }
        } catch (Exception e) {
            log.error("查詢 Bybit SL/TP 價格失敗: {}", e.getMessage());
        }
        return new double[]{slPrice, tpPrice};
    }

    @Override
    public String getOpenOrdersRaw(String symbol) {
        return sendSignedGet("/v5/order/realtime",
                Map.of("category", "linear", "symbol", symbol));
    }

    @Override
    public String getForceOrdersRaw() {
        // Bybit V5 沒有直接的 forceOrders 端點，回傳空陣列
        // 強平記錄可透過 /v5/position/list 的 liqPrice 或交易紀錄查詢
        return "[]";
    }

    // ==================== 帳戶配置 ====================

    @Override
    public void setLeverage(String symbol, int leverage) {
        JsonObject body = new JsonObject();
        body.addProperty("category", "linear");
        body.addProperty("symbol", symbol);
        body.addProperty("buyLeverage", String.valueOf(leverage));
        body.addProperty("sellLeverage", String.valueOf(leverage));

        log.info("Bybit 設定槓桿: {} x{}", symbol, leverage);
        try {
            String response = sendSignedPost("/v5/position/set-leverage", gson.toJson(body));
            parseBybitResponse(response);
        } catch (RuntimeException e) {
            // Bybit retCode=110043 表示槓桿已相同，不需要拋出
            if (e.getMessage() != null && e.getMessage().contains("110043")) {
                log.debug("Bybit 槓桿已設定為 {}x，無需變更", leverage);
            } else {
                throw e;
            }
        }
    }

    /**
     * 設定保證金模式
     * 介面傳入 "ISOLATED" / "CROSSED"，轉換為 Bybit tradeMode: 1=isolated, 0=cross
     */
    @Override
    public void setMarginType(String symbol, String marginType) {
        int tradeMode = "ISOLATED".equalsIgnoreCase(marginType) ? 1 : 0;

        JsonObject body = new JsonObject();
        body.addProperty("category", "linear");
        body.addProperty("symbol", symbol);
        body.addProperty("tradeMode", tradeMode);
        body.addProperty("buyLeverage", "20");
        body.addProperty("sellLeverage", "20");

        log.info("Bybit 設定保證金模式: {} {} (tradeMode={})", symbol, marginType, tradeMode);
        try {
            String response = sendSignedPost("/v5/position/switch-isolated", gson.toJson(body));
            parseBybitResponse(response);
        } catch (RuntimeException e) {
            // Bybit retCode=110026 表示模式已相同，不需要拋出
            if (e.getMessage() != null && e.getMessage().contains("110026")) {
                log.debug("Bybit 保證金模式已設定為 {}，無需變更", marginType);
            } else {
                throw e;
            }
        }
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
        return "BYBIT";
    }

    // ==================== 內部方法：Side 轉換 ====================

    /**
     * 將正規化 side（"BUY"/"SELL"）轉換為 Bybit 格式（"Buy"/"Sell"）
     */
    private String toBybitSide(String side) {
        return "BUY".equalsIgnoreCase(side) ? "Buy" : "Sell";
    }

    // ==================== 內部方法：回應解析 ====================

    /**
     * 解析 Bybit V5 統一回應格式
     * 成功: {"retCode":0, "retMsg":"OK", "result":{...}}
     * 失敗: {"retCode":xxxxx, "retMsg":"error message", "result":{}}
     *
     * @return result JsonObject
     * @throws RuntimeException retCode != 0 時
     */
    private JsonObject parseBybitResponse(String response) {
        JsonObject json = gson.fromJson(response, JsonObject.class);
        int retCode = json.has("retCode") ? json.get("retCode").getAsInt() : -1;
        if (retCode != 0) {
            String retMsg = json.has("retMsg") ? json.get("retMsg").getAsString() : "Unknown error";
            throw new RuntimeException("Bybit API error [" + retCode + "]: " + retMsg);
        }
        return json.has("result") ? json.getAsJsonObject("result") : new JsonObject();
    }

    /**
     * 解析下單回應為 OrderResult
     */
    private OrderResult parseOrderResult(String response) {
        try {
            JsonObject result = parseBybitResponse(response);
            return OrderResult.builder()
                    .success(true)
                    .orderId(result.has("orderId") ? result.get("orderId").getAsString() : "")
                    .symbol(result.has("symbol") ? result.get("symbol").getAsString() : "")
                    .side(result.has("side") ? result.get("side").getAsString().toUpperCase() : "")
                    .type(result.has("orderType") ? result.get("orderType").getAsString().toUpperCase() : "")
                    .price(result.has("price") ? parseDoubleOrZero(result.get("price").getAsString()) : 0)
                    .quantity(result.has("qty") ? parseDoubleOrZero(result.get("qty").getAsString()) : 0)
                    .rawResponse(response)
                    .build();
        } catch (RuntimeException e) {
            return OrderResult.fail("Bybit order failed: " + e.getMessage());
        }
    }

    /**
     * 安全解析 double，空字串或 null 回傳 0
     */
    private double parseDoubleOrZero(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== 內部方法：HTTP 請求 ====================

    private String sendPublicGet(String endpoint) {
        String url = bybitConfig.getBaseUrl() + endpoint;
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        return executeRequest(request);
    }

    /**
     * 發送帶簽名的 GET 請求
     * Bybit V5 GET 簽名: HMAC(timestamp + apiKey + recvWindow + queryString, secret)
     * 簽名放在 X-BAPI-SIGN header
     */
    private String sendSignedGet(String endpoint, Map<String, String> params) {
        String queryString = buildQueryString(params);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String recvWindow = String.valueOf(bybitConfig.getRecvWindow());
        String signature = BybitSignatureUtil.sign(timestamp, getActiveApiKey(), recvWindow,
                queryString, getActiveSecretKey());

        String url = bybitConfig.getBaseUrl() + endpoint;
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("X-BAPI-API-KEY", getActiveApiKey())
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-SIGN-TYPE", "2")
                .addHeader("X-BAPI-TIMESTAMP", timestamp)
                .addHeader("X-BAPI-RECV-WINDOW", recvWindow)
                .build();
        return executeRequest(request);
    }

    /**
     * 發送帶簽名的 POST 請求（JSON body）
     * Bybit V5 POST 簽名: HMAC(timestamp + apiKey + recvWindow + jsonBody, secret)
     */
    private String sendSignedPost(String endpoint, String jsonBody) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String recvWindow = String.valueOf(bybitConfig.getRecvWindow());
        String signature = BybitSignatureUtil.sign(timestamp, getActiveApiKey(), recvWindow,
                jsonBody, getActiveSecretKey());

        String url = bybitConfig.getBaseUrl() + endpoint;
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("X-BAPI-API-KEY", getActiveApiKey())
                .addHeader("X-BAPI-SIGN", signature)
                .addHeader("X-BAPI-SIGN-TYPE", "2")
                .addHeader("X-BAPI-TIMESTAMP", timestamp)
                .addHeader("X-BAPI-RECV-WINDOW", recvWindow)
                .build();
        return executeRequest(request);
    }

    /**
     * 帶重試的 POST 請求（下單用）
     * 只有 IOException（網路斷線/timeout）才重試，收到 HTTP 回應不重試
     */
    private String sendSignedPostWithRetry(String endpoint, String jsonBody) {
        IOException lastException = null;

        for (int attempt = 0; attempt <= ORDER_MAX_RETRIES; attempt++) {
            try {
                rateLimiter.acquire();

                String timestamp = String.valueOf(System.currentTimeMillis());
                String recvWindow = String.valueOf(bybitConfig.getRecvWindow());
                String signature = BybitSignatureUtil.sign(timestamp, getActiveApiKey(), recvWindow,
                        jsonBody, getActiveSecretKey());

                String url = bybitConfig.getBaseUrl() + endpoint;
                RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

                Request request = new Request.Builder()
                        .url(url)
                        .post(body)
                        .addHeader("Content-Type", "application/json")
                        .addHeader("X-BAPI-API-KEY", getActiveApiKey())
                        .addHeader("X-BAPI-SIGN", signature)
                        .addHeader("X-BAPI-SIGN-TYPE", "2")
                        .addHeader("X-BAPI-TIMESTAMP", timestamp)
                        .addHeader("X-BAPI-RECV-WINDOW", recvWindow)
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    String responseBody = response.body() != null ? response.body().string() : "";
                    if (!response.isSuccessful()) {
                        log.error("Bybit API error: {} - {}", response.code(), responseBody);
                    }
                    return responseBody;
                }
            } catch (IOException e) {
                lastException = e;
                log.warn("Bybit 下單網路失敗 (attempt {}/{}): error={}",
                        attempt + 1, ORDER_MAX_RETRIES + 1, e.getMessage());
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

        log.error("Bybit 下單重試 {} 次全部失敗！error: {}",
                ORDER_MAX_RETRIES + 1,
                lastException != null ? lastException.getMessage() : "unknown");
        throw new RuntimeException("Bybit order request failed after " + (ORDER_MAX_RETRIES + 1) + " retries",
                lastException);
    }

    private String executeRequest(Request request) {
        rateLimiter.acquire();

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Bybit API error: {} {} - {}", request.method(), request.url().encodedPath(), body);
                throw new RuntimeException(
                        String.format("Bybit API HTTP %d: %s", response.code(), body));
            }
            return body;
        } catch (RuntimeException e) {
            throw e;
        } catch (IOException e) {
            log.error("HTTP request failed: {}", e.getMessage(), e);
            throw new RuntimeException("Bybit API request failed", e);
        }
    }

    // ==================== 內部方法：工具 ====================

    /**
     * 從 Map 建構 query string（用於 GET 請求）
     */
    private String buildQueryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(entry.getKey()).append("=").append(entry.getValue());
        }
        return sb.toString();
    }

    /**
     * 取得當前生效的 API Key
     * 優先順序：ExchangeCredentials ThreadLocal → 全局 Config
     */
    private String getActiveApiKey() {
        ExchangeCredentials creds = CURRENT_CREDENTIALS.get();
        if (creds != null) return creds.apiKey();
        return bybitConfig.getApiKey();
    }

    /**
     * 取得當前生效的 Secret Key
     * 優先順序：ExchangeCredentials ThreadLocal → 全局 Config
     */
    private String getActiveSecretKey() {
        ExchangeCredentials creds = CURRENT_CREDENTIALS.get();
        if (creds != null) return creds.secretKey();
        return bybitConfig.getSecretKey();
    }
}
