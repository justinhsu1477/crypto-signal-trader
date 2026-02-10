package com.trader.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.config.BinanceConfig;
import com.trader.config.RiskConfig;
import com.trader.model.OrderResult;
import com.trader.model.TradeSignal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Binance Futures API 串接服務
 *
 * 主要功能：
 * 1. 設定槓桿
 * 2. 下限價單 (入場)
 * 3. 設定止損單 (STOP_MARKET)
 * 4. 設定止盈單 (TAKE_PROFIT_MARKET)
 * 5. 查詢帳戶餘額
 * 6. 查詢當前持倉
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BinanceFuturesService {

    private final OkHttpClient httpClient;
    private final BinanceConfig binanceConfig;
    private final RiskConfig riskConfig;
    private final Gson gson = new Gson();

    // ==================== 帳戶相關 ====================

    /**
     * 查詢 Futures 帳戶餘額
     */
    public String getAccountBalance() {
        String endpoint = "/fapi/v2/balance";
        return sendSignedGet(endpoint, Map.of());
    }

    /**
     * 查詢當前持倉
     */
    public String getPositions() {
        String endpoint = "/fapi/v2/positionRisk";
        return sendSignedGet(endpoint, Map.of());
    }

    /**
     * 查詢交易對資訊 (價格精度、數量精度等)
     */
    public String getExchangeInfo() {
        String endpoint = "/fapi/v1/exchangeInfo";
        return sendPublicGet(endpoint);
    }

    // ==================== 交易相關 ====================

    /**
     * 設定槓桿倍數
     */
    public String setLeverage(String symbol, int leverage) {
        String endpoint = "/fapi/v1/leverage";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("leverage", String.valueOf(leverage));

        log.info("設定槓桿: {} x{}", symbol, leverage);
        return sendSignedPost(endpoint, params);
    }

    /**
     * 設定持倉模式 (單向 or 雙向)
     * "true" = 雙向持倉 (Hedge Mode)
     * "false" = 單向持倉 (One-way Mode)
     */
    public String setPositionMode(boolean dualSidePosition) {
        String endpoint = "/fapi/v1/positionSide/dual";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dualSidePosition", String.valueOf(dualSidePosition));
        return sendSignedPost(endpoint, params);
    }

    /**
     * 下限價單 (入場單)
     *
     * @param symbol   交易對 e.g. "BTCUSDT"
     * @param side     "BUY" or "SELL"
     * @param price    限價
     * @param quantity 數量
     * @return API 回應
     */
    public OrderResult placeLimitOrder(String symbol, String side, double price, double quantity) {
        String endpoint = "/fapi/v1/order";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", "LIMIT");
        params.put("timeInForce", "GTC");
        params.put("price", formatPrice(price));
        params.put("quantity", formatQuantity(symbol, quantity));

        log.info("下限價單: {} {} {} @ {}", symbol, side, quantity, price);
        String response = sendSignedPost(endpoint, params);
        return parseOrderResponse(response);
    }

    /**
     * 下市價單 (立即成交)
     */
    public OrderResult placeMarketOrder(String symbol, String side, double quantity) {
        String endpoint = "/fapi/v1/order";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", "MARKET");
        params.put("quantity", formatQuantity(symbol, quantity));

        log.info("下市價單: {} {} {}", symbol, side, quantity);
        String response = sendSignedPost(endpoint, params);
        return parseOrderResponse(response);
    }

    /**
     * 設定止損單 (STOP_MARKET)
     * 觸發後以市價平倉
     *
     * @param symbol    交易對
     * @param side      平倉方向: 做空的止損用 "BUY", 做多的止損用 "SELL"
     * @param stopPrice 觸發價格
     * @param quantity  數量
     */
    public OrderResult placeStopLoss(String symbol, String side, double stopPrice, double quantity) {
        String endpoint = "/fapi/v1/order";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", "STOP_MARKET");
        params.put("stopPrice", formatPrice(stopPrice));
        params.put("quantity", formatQuantity(symbol, quantity));
        params.put("closePosition", "false");

        log.info("設定止損: {} {} stopPrice={}", symbol, side, stopPrice);
        String response = sendSignedPost(endpoint, params);
        return parseOrderResponse(response);
    }

    /**
     * 設定止盈單 (TAKE_PROFIT_MARKET)
     * 觸發後以市價平倉
     */
    public OrderResult placeTakeProfit(String symbol, String side, double stopPrice, double quantity) {
        String endpoint = "/fapi/v1/order";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", "TAKE_PROFIT_MARKET");
        params.put("stopPrice", formatPrice(stopPrice));
        params.put("quantity", formatQuantity(symbol, quantity));
        params.put("closePosition", "false");

        log.info("設定止盈: {} {} stopPrice={}", symbol, side, stopPrice);
        String response = sendSignedPost(endpoint, params);
        return parseOrderResponse(response);
    }

    /**
     * 取消訂單
     */
    public String cancelOrder(String symbol, long orderId) {
        String endpoint = "/fapi/v1/order";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("orderId", String.valueOf(orderId));
        return sendSignedDelete(endpoint, params);
    }

    /**
     * 取消某交易對的所有訂單
     */
    public String cancelAllOrders(String symbol) {
        String endpoint = "/fapi/v1/allOpenOrders";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        return sendSignedDelete(endpoint, params);
    }

    /**
     * 查詢未成交訂單
     */
    public String getOpenOrders(String symbol) {
        String endpoint = "/fapi/v1/openOrders";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        return sendSignedGet(endpoint, params);
    }

    // ==================== 完整下單流程 ====================

    /**
     * 根據交易訊號執行完整下單流程:
     * 1. 設定槓桿
     * 2. 下限價入場單
     * 3. 設定止損
     * 4. 設定止盈 (多個目標)
     */
    public List<OrderResult> executeSignal(TradeSignal signal) {
        String symbol = signal.getSymbol();
        int leverage = signal.getLeverage() != null
                ? Math.min(signal.getLeverage(), riskConfig.getMaxLeverage())
                : riskConfig.getDefaultLeverage();

        // 1. 設定槓桿
        setLeverage(symbol, leverage);

        // 2. 計算下單數量
        double entryPrice = signal.getEntryPriceLow(); // 用入場價下限
        double quantity = calculateQuantity(entryPrice, leverage);

        // 入場方向: 做空 → SELL, 做多 → BUY
        String entrySide = signal.getSide() == TradeSignal.Side.SHORT ? "SELL" : "BUY";
        // 平倉方向: 反向
        String closeSide = signal.getSide() == TradeSignal.Side.SHORT ? "BUY" : "SELL";

        // 3. 下限價入場單
        OrderResult entryOrder = placeLimitOrder(symbol, entrySide, entryPrice, quantity);
        if (!entryOrder.isSuccess()) {
            log.error("入場單失敗: {}", entryOrder.getErrorMessage());
            return List.of(entryOrder);
        }

        // 4. 設定止損
        OrderResult slOrder = placeStopLoss(symbol, closeSide, signal.getStopLoss(), quantity);

        // 5. 設定止盈 (分批平倉: 均分數量到各個止盈目標)
        List<Double> tps = signal.getTakeProfits();
        double tpQuantityEach = quantity / tps.size();

        List<OrderResult> tpOrders = tps.stream()
                .map(tp -> placeTakeProfit(symbol, closeSide, tp, tpQuantityEach))
                .collect(Collectors.toList());

        // 彙整結果
        List<OrderResult> allResults = new java.util.ArrayList<>();
        allResults.add(entryOrder);
        allResults.add(slOrder);
        allResults.addAll(tpOrders);

        log.info("訊號執行完成: {} {} 入場@{} 止損@{} 止盈@{}",
                symbol, signal.getSide(), entryPrice, signal.getStopLoss(), tps);

        return allResults;
    }

    // ==================== 內部方法 ====================

    /**
     * 根據最大倉位和槓桿計算下單數量
     */
    private double calculateQuantity(double price, int leverage) {
        // 倉位價值 = maxPositionUsdt * leverage
        // 數量 = 倉位價值 / 價格
        double positionValue = riskConfig.getMaxPositionUsdt() * leverage;
        return positionValue / price;
    }

    private String formatPrice(double price) {
        // BTC 通常精度到小數點 1 位, 其他幣種可能不同
        // TODO: 從 exchangeInfo 動態取得精度
        if (price >= 1000) {
            return String.format("%.1f", price);
        } else if (price >= 1) {
            return String.format("%.2f", price);
        } else {
            return String.format("%.4f", price);
        }
    }

    private String formatQuantity(String symbol, double quantity) {
        // BTC 最小數量 0.001
        // TODO: 從 exchangeInfo 動態取得精度
        if (symbol.startsWith("BTC")) {
            return String.format("%.3f", quantity);
        } else {
            return String.format("%.2f", quantity);
        }
    }

    private OrderResult parseOrderResponse(String response) {
        try {
            JsonObject json = gson.fromJson(response, JsonObject.class);

            if (json.has("code") && json.get("code").getAsInt() != 200) {
                return OrderResult.fail(json.has("msg") ? json.get("msg").getAsString() : response);
            }

            return OrderResult.builder()
                    .success(true)
                    .orderId(json.has("orderId") ? json.get("orderId").getAsString() : "")
                    .symbol(json.has("symbol") ? json.get("symbol").getAsString() : "")
                    .side(json.has("side") ? json.get("side").getAsString() : "")
                    .type(json.has("type") ? json.get("type").getAsString() : "")
                    .price(json.has("price") ? json.get("price").getAsDouble() : 0)
                    .quantity(json.has("origQty") ? json.get("origQty").getAsDouble() : 0)
                    .rawResponse(response)
                    .build();
        } catch (Exception e) {
            return OrderResult.fail("Failed to parse response: " + response);
        }
    }

    // ==================== HTTP 請求方法 ====================

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
                .addHeader("X-MBX-APIKEY", binanceConfig.getApiKey())
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
                .addHeader("X-MBX-APIKEY", binanceConfig.getApiKey())
                .build();
        return executeRequest(request);
    }

    private String sendSignedDelete(String endpoint, Map<String, String> params) {
        String queryString = buildSignedQueryString(params);
        String url = binanceConfig.getBaseUrl() + endpoint + "?" + queryString;

        Request request = new Request.Builder()
                .url(url)
                .delete()
                .addHeader("X-MBX-APIKEY", binanceConfig.getApiKey())
                .build();
        return executeRequest(request);
    }

    /**
     * 組裝帶簽名的查詢字串
     * Binance 要求: timestamp + signature
     */
    private String buildSignedQueryString(Map<String, String> params) {
        Map<String, String> allParams = new LinkedHashMap<>(params);
        allParams.put("timestamp", String.valueOf(System.currentTimeMillis()));

        String queryString = allParams.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));

        String signature = BinanceSignatureUtil.sign(queryString, binanceConfig.getSecretKey());
        return queryString + "&signature=" + signature;
    }

    private String executeRequest(Request request) {
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Binance API error: {} - {}", response.code(), body);
            }
            return body;
        } catch (IOException e) {
            log.error("HTTP request failed: {}", e.getMessage(), e);
            throw new RuntimeException("Binance API request failed", e);
        }
    }
}
