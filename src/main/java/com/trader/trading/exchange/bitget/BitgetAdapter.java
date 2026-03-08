package com.trader.trading.exchange.bitget;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trader.shared.config.BitgetConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.util.BitgetApiRateLimiter;
import com.trader.shared.util.BitgetSignatureUtil;
import com.trader.trading.exchange.ExchangeAdapter;
import com.trader.trading.exchange.ExchangeCredentials;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Bitget V2 Mix USDT-Futures ExchangeAdapter 實作
 *
 * 所有 REST API 對應 Bitget V2 端點，productType 固定為 USDT-FUTURES。
 * 簽名方式：Base64(HMAC-SHA256(secretKey, prehash))
 * prehash = timestamp + METHOD + requestPath + queryString + body
 *
 * @see <a href="https://www.bitget.com/api-doc/common/signature">Bitget API Signature</a>
 */
@Slf4j
@Service("bitgetAdapter")
@ConditionalOnExpression("'${exchanges.enabled:BINANCE}'.toUpperCase().contains('BITGET')")
public class BitgetAdapter implements ExchangeAdapter {

    private static final String PRODUCT_TYPE = "USDT-FUTURES";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json");
    private static final Gson gson = new Gson();

    private final BitgetConfig bitgetConfig;
    private final BitgetApiRateLimiter rateLimiter;
    private final OkHttpClient httpClient;

    /** ThreadLocal 暫存 per-user API Key */
    private static final ThreadLocal<ExchangeCredentials> CURRENT_CREDENTIALS = new ThreadLocal<>();

    public BitgetAdapter(BitgetConfig bitgetConfig, BitgetApiRateLimiter rateLimiter) {
        this(new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build(), bitgetConfig, rateLimiter);
    }

    /** 測試用建構子 — 允許注入 mock OkHttpClient */
    BitgetAdapter(OkHttpClient httpClient, BitgetConfig bitgetConfig, BitgetApiRateLimiter rateLimiter) {
        this.httpClient = httpClient;
        this.bitgetConfig = bitgetConfig;
        this.rateLimiter = rateLimiter;
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
        return "BITGET";
    }

    /**
     * 取得當前生效的 API Key（fail-fast：未設定 credentials 時拋 exception）
     * 所有 per-user credential 由呼叫端透過 setCredentials() 設入。
     */
    private String getActiveApiKey() {
        ExchangeCredentials creds = CURRENT_CREDENTIALS.get();
        if (creds == null) {
            throw new IllegalStateException("BITGET API 呼叫缺少 credentials — 請先呼叫 setCredentials()");
        }
        return creds.apiKey();
    }

    /**
     * 取得當前生效的 Secret Key（fail-fast）
     */
    private String getActiveSecretKey() {
        ExchangeCredentials creds = CURRENT_CREDENTIALS.get();
        if (creds == null) {
            throw new IllegalStateException("BITGET API 呼叫缺少 credentials — 請先呼叫 setCredentials()");
        }
        return creds.secretKey();
    }

    /**
     * 取得當前生效的 Passphrase（fail-fast）
     */
    private String getActivePassphrase() {
        ExchangeCredentials creds = CURRENT_CREDENTIALS.get();
        if (creds == null || creds.passphrase() == null) {
            throw new IllegalStateException("BITGET API 呼叫缺少 passphrase — 請先呼叫 setCredentials()");
        }
        return creds.passphrase();
    }

    // ==================== 核心 HTTP 方法 ====================

    /**
     * 發送帶簽名的 GET 請求
     * Bitget 簽名: Base64(HMAC-SHA256(secretKey, timestamp + GET + path + ?queryString))
     */
    private String sendSignedGet(String endpoint, Map<String, String> params) {
        rateLimiter.acquire();

        String queryString = buildQueryString(params);
        String queryPart = queryString.isEmpty() ? "" : "?" + queryString;
        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = BitgetSignatureUtil.sign(
                timestamp, "GET", endpoint, queryPart, "", getActiveSecretKey());

        String url = bitgetConfig.getBaseUrl() + endpoint + queryPart;

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("ACCESS-KEY", getActiveApiKey())
                .addHeader("ACCESS-SIGN", signature)
                .addHeader("ACCESS-TIMESTAMP", timestamp)
                .addHeader("ACCESS-PASSPHRASE", getActivePassphrase())
                .addHeader("Content-Type", "application/json")
                .addHeader("locale", "en-US")
                .build();
        return executeRequest(request);
    }

    /**
     * 發送帶簽名的 POST 請求（JSON body）
     * Bitget 簽名: Base64(HMAC-SHA256(secretKey, timestamp + POST + path + body))
     */
    private String sendSignedPost(String endpoint, String jsonBody) {
        rateLimiter.acquire();

        String timestamp = String.valueOf(System.currentTimeMillis());
        String signature = BitgetSignatureUtil.sign(
                timestamp, "POST", endpoint, "", jsonBody, getActiveSecretKey());

        String url = bitgetConfig.getBaseUrl() + endpoint;
        RequestBody body = RequestBody.create(jsonBody, JSON_MEDIA_TYPE);

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("ACCESS-KEY", getActiveApiKey())
                .addHeader("ACCESS-SIGN", signature)
                .addHeader("ACCESS-TIMESTAMP", timestamp)
                .addHeader("ACCESS-PASSPHRASE", getActivePassphrase())
                .addHeader("Content-Type", "application/json")
                .addHeader("locale", "en-US")
                .build();
        return executeRequest(request);
    }

    /**
     * 發送無簽名的 GET 請求（公開 API）
     */
    private String sendPublicGet(String endpoint, Map<String, String> params) {
        String queryString = buildQueryString(params);
        String url = bitgetConfig.getBaseUrl() + endpoint;
        if (!queryString.isEmpty()) {
            url += "?" + queryString;
        }

        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("Content-Type", "application/json")
                .addHeader("locale", "en-US")
                .build();
        return executeRequest(request);
    }

    // ==================== Response 解析 ====================

    /**
     * 解析 Bitget V2 統一回應格式
     * 成功: {"code":"00000", "msg":"success", "data":{...}}
     * 失敗: {"code":"xxxxx", "msg":"error message", "data":null}
     *
     * @return data JsonElement（可能是 Object 或 Array）
     * @throws RuntimeException code != 00000 時
     */
    private JsonElement parseBitgetResponse(String response) {
        JsonObject json = gson.fromJson(response, JsonObject.class);
        String code = json.has("code") ? json.get("code").getAsString() : "-1";
        if (!"00000".equals(code)) {
            String msg = json.has("msg") ? json.get("msg").getAsString() : "Unknown error";
            throw new RuntimeException("Bitget API error [" + code + "]: " + msg);
        }
        return json.has("data") && !json.get("data").isJsonNull() ? json.get("data") : new JsonObject();
    }

    /**
     * 解析下單回應為 OrderResult
     */
    private OrderResult parseOrderResult(String response) {
        try {
            JsonElement dataElem = parseBitgetResponse(response);
            JsonObject data = dataElem.isJsonObject() ? dataElem.getAsJsonObject() : new JsonObject();
            return OrderResult.builder()
                    .success(true)
                    .orderId(data.has("orderId") ? data.get("orderId").getAsString() : "")
                    .rawResponse(response)
                    .build();
        } catch (RuntimeException e) {
            return OrderResult.fail("Bitget order failed: " + e.getMessage());
        }
    }

    // ==================== 帳戶查詢 ====================

    @Override
    public double getAvailableBalance() {
        String response = sendSignedGet("/api/v2/mix/account/accounts",
                Map.of("productType", PRODUCT_TYPE));
        JsonElement data = parseBitgetResponse(response);
        if (data.isJsonArray()) {
            JsonArray arr = data.getAsJsonArray();
            for (JsonElement elem : arr) {
                JsonObject acct = elem.getAsJsonObject();
                if (acct.has("marginCoin") && "USDT".equals(acct.get("marginCoin").getAsString())) {
                    return parseDoubleOrZero(acct.get("available").getAsString());
                }
            }
        }
        return 0;
    }

    @Override
    public String getAccountBalanceRaw() {
        return sendSignedGet("/api/v2/mix/account/accounts",
                Map.of("productType", PRODUCT_TYPE));
    }

    // ==================== 持倉查詢 ====================

    @Override
    public String getPositionsRaw() {
        return sendSignedGet("/api/v2/mix/position/all-position",
                Map.of("productType", PRODUCT_TYPE));
    }

    @Override
    public double getCurrentPositionAmount(String symbol) {
        String response = sendSignedGet("/api/v2/mix/position/all-position",
                Map.of("productType", PRODUCT_TYPE));
        JsonElement data = parseBitgetResponse(response);
        if (data.isJsonArray()) {
            for (JsonElement elem : data.getAsJsonArray()) {
                JsonObject pos = elem.getAsJsonObject();
                if (pos.has("symbol") && symbol.equals(pos.get("symbol").getAsString())) {
                    double total = parseDoubleOrZero(
                            pos.has("total") ? pos.get("total").getAsString() : "0");
                    String holdSide = pos.has("holdSide") ? pos.get("holdSide").getAsString() : "";
                    return "long".equalsIgnoreCase(holdSide) ? total : -total;
                }
            }
        }
        return 0;
    }

    @Override
    public Map<String, Double> getAllPositionAmounts() {
        String response = sendSignedGet("/api/v2/mix/position/all-position",
                Map.of("productType", PRODUCT_TYPE));
        JsonElement data = parseBitgetResponse(response);
        Map<String, Double> result = new HashMap<>();
        if (data.isJsonArray()) {
            for (JsonElement elem : data.getAsJsonArray()) {
                JsonObject pos = elem.getAsJsonObject();
                String symbol = pos.has("symbol") ? pos.get("symbol").getAsString() : "";
                double total = parseDoubleOrZero(
                        pos.has("total") ? pos.get("total").getAsString() : "0");
                if (total > 0 && !symbol.isEmpty()) {
                    String holdSide = pos.has("holdSide") ? pos.get("holdSide").getAsString() : "";
                    double signedAmount = "long".equalsIgnoreCase(holdSide) ? total : -total;
                    result.put(symbol, signedAmount);
                }
            }
        }
        return result;
    }

    @Override
    public int getActivePositionCount() {
        Map<String, Double> positions = getAllPositionAmounts();
        return (int) positions.values().stream().filter(v -> v != 0).count();
    }

    // ==================== 市場數據 ====================

    @Override
    public double getMarkPrice(String symbol) {
        String response = sendPublicGet("/api/v2/mix/market/symbol-price",
                Map.of("symbol", symbol, "productType", PRODUCT_TYPE));
        JsonElement data = parseBitgetResponse(response);
        if (data.isJsonArray() && !data.getAsJsonArray().isEmpty()) {
            JsonObject item = data.getAsJsonArray().get(0).getAsJsonObject();
            return parseDoubleOrZero(item.has("markPrice") ? item.get("markPrice").getAsString() : "0");
        } else if (data.isJsonObject()) {
            return parseDoubleOrZero(
                    data.getAsJsonObject().has("markPrice")
                            ? data.getAsJsonObject().get("markPrice").getAsString() : "0");
        }
        return 0;
    }

    @Override
    public String getExchangeInfoRaw() {
        return sendPublicGet("/api/v2/mix/market/contracts",
                Map.of("productType", PRODUCT_TYPE));
    }

    // ==================== 訂單操作 ====================

    @Override
    public OrderResult placeLimitOrder(String symbol, String side, double price, double quantity) {
        JsonObject body = new JsonObject();
        body.addProperty("symbol", symbol);
        body.addProperty("productType", PRODUCT_TYPE);
        body.addProperty("marginMode", "isolated");
        body.addProperty("marginCoin", "USDT");
        body.addProperty("side", toBitgetSide(side));
        body.addProperty("orderType", "limit");
        body.addProperty("price", formatPrice(price));
        body.addProperty("size", formatQuantity(symbol, quantity));
        body.addProperty("force", "GTC");

        log.info("Bitget 下限價單: {} {} {} @ {}", symbol, side, quantity, price);
        String response = sendSignedPost("/api/v2/mix/order/place-order", gson.toJson(body));
        return parseOrderResult(response);
    }

    @Override
    public OrderResult placeMarketOrder(String symbol, String side, double quantity) {
        JsonObject body = new JsonObject();
        body.addProperty("symbol", symbol);
        body.addProperty("productType", PRODUCT_TYPE);
        body.addProperty("marginMode", "isolated");
        body.addProperty("marginCoin", "USDT");
        body.addProperty("side", toBitgetSide(side));
        body.addProperty("orderType", "market");
        body.addProperty("size", formatQuantity(symbol, quantity));

        log.info("Bitget 下市價單: {} {} {}", symbol, side, quantity);
        String response = sendSignedPost("/api/v2/mix/order/place-order", gson.toJson(body));
        return parseOrderResult(response);
    }

    @Override
    public OrderResult setStopLoss(String symbol, String closeSide, double triggerPrice, double quantity) {
        JsonObject body = new JsonObject();
        body.addProperty("symbol", symbol);
        body.addProperty("productType", PRODUCT_TYPE);
        body.addProperty("marginMode", "isolated");
        body.addProperty("planType", "pos_loss");
        body.addProperty("triggerPrice", formatPrice(triggerPrice));
        body.addProperty("triggerType", "mark_price");
        body.addProperty("holdSide", closeSideToHoldSide(closeSide));

        log.info("Bitget 設定止損: {} triggerPrice={}", symbol, triggerPrice);
        String response = sendSignedPost("/api/v2/mix/order/place-tpsl-order", gson.toJson(body));
        return parseOrderResult(response);
    }

    @Override
    public OrderResult setTakeProfit(String symbol, String closeSide, double triggerPrice, double quantity) {
        JsonObject body = new JsonObject();
        body.addProperty("symbol", symbol);
        body.addProperty("productType", PRODUCT_TYPE);
        body.addProperty("marginMode", "isolated");
        body.addProperty("planType", "pos_profit");
        body.addProperty("triggerPrice", formatPrice(triggerPrice));
        body.addProperty("triggerType", "mark_price");
        body.addProperty("holdSide", closeSideToHoldSide(closeSide));

        log.info("Bitget 設定止盈: {} triggerPrice={}", symbol, triggerPrice);
        String response = sendSignedPost("/api/v2/mix/order/place-tpsl-order", gson.toJson(body));
        return parseOrderResult(response);
    }

    @Override
    public void cancelOrder(String symbol, String orderId) {
        JsonObject body = new JsonObject();
        body.addProperty("symbol", symbol);
        body.addProperty("productType", PRODUCT_TYPE);
        body.addProperty("orderId", orderId);

        log.info("Bitget 取消訂單: {} orderId={}", symbol, orderId);
        String response = sendSignedPost("/api/v2/mix/order/cancel-order", gson.toJson(body));
        parseBitgetResponse(response);
    }

    @Override
    public void cancelAllOrders(String symbol) {
        JsonObject body = new JsonObject();
        body.addProperty("symbol", symbol);
        body.addProperty("productType", PRODUCT_TYPE);

        log.info("Bitget 取消所有訂單: {}", symbol);
        String response = sendSignedPost("/api/v2/mix/order/cancel-all-orders", gson.toJson(body));
        parseBitgetResponse(response);
    }

    @Override
    public void cancelSLTPOrders(String symbol) {
        // 查詢 pending plan orders (SL/TP)
        String response = sendSignedGet("/api/v2/mix/order/orders-plan-pending",
                Map.of("symbol", symbol, "productType", PRODUCT_TYPE));
        JsonElement data = parseBitgetResponse(response);

        if (!data.isJsonObject()) return;
        JsonObject dataObj = data.getAsJsonObject();
        JsonArray entrustedList = dataObj.has("entrustedList")
                ? dataObj.getAsJsonArray("entrustedList") : new JsonArray();

        for (JsonElement elem : entrustedList) {
            JsonObject plan = elem.getAsJsonObject();
            String planType = plan.has("planType") ? plan.get("planType").getAsString() : "";
            if ("pos_loss".equals(planType) || "pos_profit".equals(planType)) {
                String orderId = plan.has("orderId") ? plan.get("orderId").getAsString() : "";
                if (!orderId.isEmpty()) {
                    JsonObject cancelBody = new JsonObject();
                    cancelBody.addProperty("symbol", symbol);
                    cancelBody.addProperty("productType", PRODUCT_TYPE);
                    cancelBody.addProperty("orderId", orderId);

                    try {
                        String cancelResp = sendSignedPost(
                                "/api/v2/mix/order/cancel-plan-order", gson.toJson(cancelBody));
                        parseBitgetResponse(cancelResp);
                        log.info("Bitget 取消 {} 訂單: {} orderId={}", planType, symbol, orderId);
                    } catch (Exception e) {
                        log.warn("Bitget 取消計畫單失敗: {} orderId={}, {}", symbol, orderId, e.getMessage());
                    }
                }
            }
        }
    }

    // ==================== 查詢訂單 ====================

    @Override
    public boolean hasOpenEntryOrders(String symbol) {
        String response = sendSignedGet("/api/v2/mix/order/orders-pending",
                Map.of("symbol", symbol, "productType", PRODUCT_TYPE));
        JsonElement data = parseBitgetResponse(response);
        if (!data.isJsonObject()) return false;
        JsonObject dataObj = data.getAsJsonObject();
        JsonArray entrustedList = dataObj.has("entrustedList")
                ? dataObj.getAsJsonArray("entrustedList") : new JsonArray();
        return !entrustedList.isEmpty();
    }

    @Override
    public double[] getCurrentSLTPPrices(String symbol) {
        double slPrice = 0;
        double tpPrice = 0;
        try {
            String response = sendSignedGet("/api/v2/mix/order/orders-plan-pending",
                    Map.of("symbol", symbol, "productType", PRODUCT_TYPE));
            JsonElement data = parseBitgetResponse(response);

            if (data.isJsonObject()) {
                JsonObject dataObj = data.getAsJsonObject();
                JsonArray entrustedList = dataObj.has("entrustedList")
                        ? dataObj.getAsJsonArray("entrustedList") : new JsonArray();

                for (JsonElement elem : entrustedList) {
                    JsonObject plan = elem.getAsJsonObject();
                    String planType = plan.has("planType") ? plan.get("planType").getAsString() : "";
                    double triggerPrice = parseDoubleOrZero(
                            plan.has("triggerPrice") ? plan.get("triggerPrice").getAsString() : "0");
                    if ("pos_loss".equals(planType)) {
                        slPrice = triggerPrice;
                    } else if ("pos_profit".equals(planType)) {
                        tpPrice = triggerPrice;
                    }
                }
            }
        } catch (Exception e) {
            log.error("查詢 Bitget SL/TP 價格失敗: {}", e.getMessage());
        }
        return new double[]{slPrice, tpPrice};
    }

    @Override
    public String getOpenOrdersRaw(String symbol) {
        return sendSignedGet("/api/v2/mix/order/orders-pending",
                Map.of("symbol", symbol, "productType", PRODUCT_TYPE));
    }

    @Override
    public String getForceOrdersRaw() {
        try {
            String response = sendSignedGet("/api/v2/mix/order/orders-history",
                    Map.of("productType", PRODUCT_TYPE));
            JsonElement data = parseBitgetResponse(response);

            if (!data.isJsonObject()) return "[]";
            JsonObject dataObj = data.getAsJsonObject();
            JsonArray entrustedList = dataObj.has("entrustedList")
                    ? dataObj.getAsJsonArray("entrustedList") : new JsonArray();

            // 過濾強平訂單
            JsonArray normalized = new JsonArray();
            for (JsonElement elem : entrustedList) {
                JsonObject order = elem.getAsJsonObject();
                String orderType = order.has("orderType") ? order.get("orderType").getAsString() : "";
                // Bitget 強平訂單的 orderType 或 enterPointSource 會標記 liquidation
                String enterPointSource = order.has("enterPointSource")
                        ? order.get("enterPointSource").getAsString() : "";
                if ("liquidation".equalsIgnoreCase(orderType)
                        || "liquidation".equalsIgnoreCase(enterPointSource)
                        || "SYS".equalsIgnoreCase(enterPointSource)) {
                    JsonObject mapped = new JsonObject();
                    mapped.addProperty("orderId",
                            order.has("orderId") ? order.get("orderId").getAsString() : "");
                    mapped.addProperty("time",
                            order.has("cTime") ? Long.parseLong(order.get("cTime").getAsString()) : 0L);
                    mapped.addProperty("symbol",
                            order.has("symbol") ? order.get("symbol").getAsString() : "UNKNOWN");
                    mapped.addProperty("side",
                            order.has("side") ? order.get("side").getAsString().toUpperCase() : "");
                    mapped.addProperty("avgPrice",
                            parseDoubleOrZero(order.has("priceAvg") ? order.get("priceAvg").getAsString() : "0"));
                    mapped.addProperty("origQty",
                            parseDoubleOrZero(order.has("size") ? order.get("size").getAsString() : "0"));
                    normalized.add(mapped);
                }
            }
            return gson.toJson(normalized);
        } catch (Exception e) {
            log.warn("查詢 Bitget 強制平倉記錄失敗: {}", e.getMessage());
            return "[]";
        }
    }

    // ==================== 帳戶配置 ====================

    @Override
    public void setLeverage(String symbol, int leverage) {
        JsonObject body = new JsonObject();
        body.addProperty("symbol", symbol);
        body.addProperty("productType", PRODUCT_TYPE);
        body.addProperty("marginCoin", "USDT");
        body.addProperty("leverage", String.valueOf(leverage));

        log.info("Bitget 設定槓桿: {} x{}", symbol, leverage);
        try {
            String response = sendSignedPost("/api/v2/mix/account/set-leverage", gson.toJson(body));
            parseBitgetResponse(response);
        } catch (RuntimeException e) {
            // Bitget 槓桿已設定時的錯誤碼（40723 或 already set），靜默忽略
            if (e.getMessage() != null && (e.getMessage().contains("40723")
                    || e.getMessage().toLowerCase().contains("leverage"))) {
                log.debug("Bitget 槓桿已設定為 {}x，無需變更", leverage);
            } else {
                throw e;
            }
        }
    }

    @Override
    public void setMarginType(String symbol, String marginType) {
        String mode = "ISOLATED".equalsIgnoreCase(marginType) ? "isolated" : "crossed";

        JsonObject body = new JsonObject();
        body.addProperty("symbol", symbol);
        body.addProperty("productType", PRODUCT_TYPE);
        body.addProperty("marginCoin", "USDT");
        body.addProperty("marginMode", mode);

        log.info("Bitget 設定保證金模式: {} {}", symbol, marginType);
        try {
            String response = sendSignedPost("/api/v2/mix/account/set-margin-mode", gson.toJson(body));
            parseBitgetResponse(response);
        } catch (RuntimeException e) {
            // 有持倉/掛單時可能被拒，或模式已相同
            if (e.getMessage() != null && (e.getMessage().contains("40724")
                    || e.getMessage().toLowerCase().contains("margin mode"))) {
                log.debug("Bitget 保證金模式已設定為 {}，無需變更", marginType);
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

    // ==================== Private Helpers ====================

    /**
     * 介面 side ("BUY"/"SELL") → Bitget side ("buy"/"sell")
     */
    private String toBitgetSide(String side) {
        return side.toLowerCase();
    }

    /**
     * 介面 closeSide ("BUY"/"SELL") → Bitget holdSide ("long"/"short")
     * closeSide="SELL" 表示平多 → holdSide="long"
     * closeSide="BUY" 表示平空 → holdSide="short"
     */
    private String closeSideToHoldSide(String closeSide) {
        return "SELL".equalsIgnoreCase(closeSide) ? "long" : "short";
    }

    private String buildQueryString(Map<String, String> params) {
        if (params == null || params.isEmpty()) return "";
        StringJoiner sj = new StringJoiner("&");
        // 按 key 排序確保簽名一致
        new TreeMap<>(params).forEach((k, v) -> sj.add(k + "=" + v));
        return sj.toString();
    }

    private String executeRequest(Request request) {
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.body() == null) {
                throw new RuntimeException("Bitget API 回應 body 為空: " + response.code());
            }
            String responseBody = response.body().string();
            if (!response.isSuccessful()) {
                log.error("Bitget HTTP 錯誤 {}: {}", response.code(), responseBody);
                throw new RuntimeException("Bitget HTTP error " + response.code() + ": " + responseBody);
            }
            return responseBody;
        } catch (IOException e) {
            throw new RuntimeException("Bitget API 請求失敗: " + e.getMessage(), e);
        }
    }

    private double parseDoubleOrZero(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
