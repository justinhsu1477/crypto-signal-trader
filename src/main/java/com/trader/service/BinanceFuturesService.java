package com.trader.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trader.config.BinanceConfig;
import com.trader.config.RiskConfig;
import com.trader.model.OrderResult;
import com.trader.model.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BinanceFuturesService {

    private final OkHttpClient httpClient;
    private final BinanceConfig binanceConfig;
    private final RiskConfig riskConfig;
    private final TradeRecordService tradeRecordService;
    private final SignalDeduplicationService deduplicationService;
    private final Gson gson = new Gson();

    public BinanceFuturesService(OkHttpClient httpClient, BinanceConfig binanceConfig,
                                  RiskConfig riskConfig, TradeRecordService tradeRecordService,
                                  SignalDeduplicationService deduplicationService) {
        this.httpClient = httpClient;
        this.binanceConfig = binanceConfig;
        this.riskConfig = riskConfig;
        this.tradeRecordService = tradeRecordService;
        this.deduplicationService = deduplicationService;
    }

    // ==================== 帳戶相關 ====================

    public String getAccountBalance() {
        String endpoint = "/fapi/v2/balance";
        return sendSignedGet(endpoint, Map.of());
    }

    public String getPositions() {
        String endpoint = "/fapi/v2/positionRisk";
        return sendSignedGet(endpoint, Map.of());
    }

    public String getExchangeInfo() {
        String endpoint = "/fapi/v1/exchangeInfo";
        return sendPublicGet(endpoint);
    }

    /**
     * 取得某交易對的當前持倉數量（絕對值）
     * 回傳 0 表示無持倉
     */
    public double getCurrentPositionAmount(String symbol) {
        String response = getPositions();
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
            log.error("解析持倉資訊失敗: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * 取得市場價格
     */
    public double getMarkPrice(String symbol) {
        String endpoint = "/fapi/v1/ticker/price";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        String response = sendPublicGet(endpoint + "?symbol=" + symbol);
        try {
            JsonObject json = gson.fromJson(response, JsonObject.class);
            return json.get("price").getAsDouble();
        } catch (Exception e) {
            log.error("取得市價失敗: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 取得目前活躍持倉數量（positionAmt != 0 的交易對數量）
     */
    public int getActivePositionCount() {
        String response = getPositions();
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
            log.error("解析持倉數量失敗: {}", e.getMessage());
        }
        return count;
    }

    /**
     * 檢查是否有未成交的 LIMIT 入場掛單
     */
    public boolean hasOpenEntryOrders(String symbol) {
        String response = getOpenOrders(symbol);
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
            log.error("檢查掛單失敗: {}", e.getMessage());
        }
        return false;
    }

    // ==================== 交易相關 ====================

    public String setLeverage(String symbol, int leverage) {
        String endpoint = "/fapi/v1/leverage";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("leverage", String.valueOf(leverage));

        log.info("設定槓桿: {} x{}", symbol, leverage);
        return sendSignedPost(endpoint, params);
    }

    /**
     * 設定保證金模式 (ISOLATED 逐倉 / CROSSED 全倉)
     */
    public String setMarginType(String symbol, String marginType) {
        String endpoint = "/fapi/v1/marginType";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("marginType", marginType);

        log.info("設定保證金模式: {} {}", symbol, marginType);
        return sendSignedPost(endpoint, params);
    }

    public String setPositionMode(boolean dualSidePosition) {
        String endpoint = "/fapi/v1/positionSide/dual";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dualSidePosition", String.valueOf(dualSidePosition));
        return sendSignedPost(endpoint, params);
    }

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

    public OrderResult placeStopLoss(String symbol, String side, double stopPrice, double quantity) {
        String endpoint = "/fapi/v1/order";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", "STOP_MARKET");
        params.put("stopPrice", formatPrice(stopPrice));
        params.put("quantity", formatQuantity(symbol, quantity));
        params.put("closePosition", "true");

        log.info("設定止損: {} {} stopPrice={}", symbol, side, stopPrice);
        String response = sendSignedPost(endpoint, params);
        return parseOrderResponse(response);
    }

    public OrderResult placeTakeProfit(String symbol, String side, double stopPrice, double quantity) {
        String endpoint = "/fapi/v1/order";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("side", side);
        params.put("type", "TAKE_PROFIT_MARKET");
        params.put("stopPrice", formatPrice(stopPrice));
        params.put("quantity", formatQuantity(symbol, quantity));
        params.put("closePosition", "true");

        log.info("設定止盈: {} {} stopPrice={}", symbol, side, stopPrice);
        String response = sendSignedPost(endpoint, params);
        return parseOrderResponse(response);
    }

    public String cancelOrder(String symbol, long orderId) {
        String endpoint = "/fapi/v1/order";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        params.put("orderId", String.valueOf(orderId));
        return sendSignedDelete(endpoint, params);
    }

    public String cancelAllOrders(String symbol) {
        String endpoint = "/fapi/v1/allOpenOrders";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        return sendSignedDelete(endpoint, params);
    }

    public String getOpenOrders(String symbol) {
        String endpoint = "/fapi/v1/openOrders";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("symbol", symbol);
        return sendSignedGet(endpoint, params);
    }

    // ==================== 新的交易流程（以損定倉） ====================

    /**
     * ENTRY: 以損定倉開倉
     * 1. 檢查交易對白名單
     * 2. 檢查持倉限制
     * 3. 設定逐倉 ISOLATED + 固定槓桿
     * 4. 以損定倉計算數量
     * 5. 掛 LIMIT 入場單
     * 6. 掛 STOP_MARKET 止損單
     * 7. Fail-Safe: SL 失敗則取消入場單
     */
    public List<OrderResult> executeSignal(TradeSignal signal) {
        String symbol = signal.getSymbol();

        // 1. 交易對白名單檢查
        if (!riskConfig.isSymbolAllowed(symbol)) {
            log.warn("交易對不在白名單: {}, 允許清單: {}", symbol, riskConfig.getAllowedSymbols());
            return List.of(OrderResult.fail("交易對不在白名單: " + symbol + ", 允許: " + riskConfig.getAllowedSymbols()));
        }

        // 2. 持倉限制檢查：有持倉或有掛單則拒絕
        double currentPosition = getCurrentPositionAmount(symbol);
        int maxPositions = riskConfig.getMaxPositions();
        if (currentPosition != 0 && getActivePositionCount() >= maxPositions) {
            log.warn("已有持倉 {} BTC，持倉數已達上限 {}，拒絕新開倉", currentPosition, maxPositions);
            return List.of(OrderResult.fail("持倉數已達上限 " + maxPositions + "，拒絕新開倉"));
        }

        // 2b. 檢查是否有未成交的入場掛單（LIMIT BUY/SELL），防止重複下單
        if (hasOpenEntryOrders(symbol)) {
            log.warn("已有未成交的入場掛單，拒絕重複下單");
            return List.of(OrderResult.fail("已有未成交的入場掛單，拒絕重複下單"));
        }

        // 2c. 重複訊號防護（signalHash 時間窗口檢查）
        if (deduplicationService.isDuplicate(signal)) {
            log.warn("重複訊號，拒絕執行: {} {} entry={} SL={}",
                    symbol, signal.getSide(), signal.getEntryPriceLow(), signal.getStopLoss());
            return List.of(OrderResult.fail("重複訊號，5分鐘內已收到相同訊號"));
        }

        // 3. 驗證止損
        if (signal.getStopLoss() == 0) {
            log.warn("ENTRY 訊號缺少止損");
            return List.of(OrderResult.fail("ENTRY 訊號必須包含 stop_loss"));
        }

        // 4. 方向邏輯驗證
        double entry = signal.getEntryPriceLow();
        double sl = signal.getStopLoss();
        if (signal.getSide() == TradeSignal.Side.LONG && sl >= entry) {
            return List.of(OrderResult.fail("做多止損不應高於入場價"));
        }
        if (signal.getSide() == TradeSignal.Side.SHORT && sl <= entry) {
            return List.of(OrderResult.fail("做空止損不應低於入場價"));
        }

        // 5. 價格偏離檢查
        double markPrice = getMarkPrice(symbol);
        if (markPrice > 0) {
            double deviation = Math.abs(entry - markPrice) / markPrice;
            if (deviation > 0.10) {
                log.warn("入場價 {} 偏離市價 {} 超過 10% ({}%)", entry, markPrice, String.format("%.1f", deviation * 100));
                return List.of(OrderResult.fail("入場價偏離市價超過 10%"));
            }
        }

        int leverage = riskConfig.getFixedLeverage();

        // 6. 設定逐倉 + 槓桿
        try {
            setMarginType(symbol, "ISOLATED");
        } catch (Exception e) {
            // 如果已經是 ISOLATED 模式，Binance 會報錯，可以忽略
            log.info("設定保證金模式: {}", e.getMessage());
        }
        setLeverage(symbol, leverage);

        // 7. 以損定倉計算數量
        double riskDistance = Math.abs(entry - sl);
        double quantity = riskConfig.getFixedLossPerTrade() / riskDistance;

        log.info("以損定倉: 固定虧損={}, 風險距離={}, 數量={}", riskConfig.getFixedLossPerTrade(), riskDistance, quantity);

        // 入場方向
        String entrySide = signal.getSide() == TradeSignal.Side.SHORT ? "SELL" : "BUY";
        String closeSide = signal.getSide() == TradeSignal.Side.SHORT ? "BUY" : "SELL";

        // 8. 掛 LIMIT 入場單
        OrderResult entryOrder = placeLimitOrder(symbol, entrySide, entry, quantity);
        if (!entryOrder.isSuccess()) {
            log.error("入場單失敗: {}", entryOrder.getErrorMessage());
            return List.of(entryOrder);
        }

        // 9. 掛 STOP_MARKET 止損單
        OrderResult slOrder = placeStopLoss(symbol, closeSide, sl, quantity);

        // 10. 掛 TAKE_PROFIT_MARKET 止盈單（如果訊號有給 TP）
        OrderResult tpOrder = null;
        if (signal.getTakeProfits() != null && !signal.getTakeProfits().isEmpty()) {
            double tp = signal.getTakeProfits().get(0);
            tpOrder = placeTakeProfit(symbol, closeSide, tp, quantity);
            if (!tpOrder.isSuccess()) {
                log.warn("止盈單失敗（不影響入場和止損）: {}", tpOrder.getErrorMessage());
            }
        }

        // 11. Fail-Safe: SL 掛失敗 → 取消入場單
        if (!slOrder.isSuccess()) {
            log.error("止損單失敗! 觸發 Fail-Safe，取消入場單");
            tradeRecordService.recordFailSafe(symbol,
                    String.format("{\"reason\":\"SL下單失敗\",\"sl_error\":\"%s\"}", slOrder.getErrorMessage()));
            try {
                long entryOrderId = Long.parseLong(entryOrder.getOrderId());
                cancelOrder(symbol, entryOrderId);
                log.info("Fail-Safe: 已取消入場單 {}", entryOrderId);
            } catch (Exception e) {
                log.error("Fail-Safe: 取消入場單失敗，嘗試市價平倉", e);
                placeMarketOrder(symbol, closeSide, quantity);
            }
            return List.of(entryOrder, slOrder);
        }

        // 11. 記錄交易到資料庫（含 signalHash 用於去重）
        try {
            String signalHash = deduplicationService.generateHash(signal);
            tradeRecordService.recordEntry(signal, entryOrder, slOrder, leverage,
                    riskConfig.getFixedLossPerTrade(), signalHash);
        } catch (Exception e) {
            log.error("交易紀錄寫入失敗（不影響交易）: {}", e.getMessage());
        }

        List<OrderResult> results = new ArrayList<>();
        results.add(entryOrder);
        results.add(slOrder);
        if (tpOrder != null) {
            results.add(tpOrder);
        }

        String tpInfo = (signal.getTakeProfits() != null && !signal.getTakeProfits().isEmpty())
                ? " TP=" + signal.getTakeProfits().get(0) : "";
        log.info("ENTRY 完成: {} {} qty={} entry={} SL={}{} 槓桿={}x ISOLATED",
                symbol, signal.getSide(), String.format("%.3f", quantity), entry, sl, tpInfo, leverage);

        return results;
    }

    /**
     * CLOSE: 分批平倉
     * 1. 取得持倉方向和數量
     * 2. 計算平倉數量
     * 3. 取消所有掛單
     * 4. 掛反向 LIMIT 平倉單
     */
    public List<OrderResult> executeClose(TradeSignal signal) {
        String symbol = signal.getSymbol();

        // 1. 取得持倉
        double positionAmt = getCurrentPositionAmount(symbol);
        if (positionAmt == 0) {
            return List.of(OrderResult.fail("無持倉可平"));
        }

        // 正數=多倉, 負數=空倉
        boolean isLong = positionAmt > 0;
        double absPosition = Math.abs(positionAmt);

        // 2. 計算平倉數量
        double closeRatio = signal.getCloseRatio() != null ? signal.getCloseRatio() : 1.0;
        double closeQty = absPosition * closeRatio;

        log.info("平倉: {} 持倉={} ratio={} 平倉數量={}", symbol, positionAmt, closeRatio, closeQty);

        // 3. 取消所有掛單
        cancelAllOrders(symbol);

        // 4. 取得市價作為平倉價格
        double markPrice = getMarkPrice(symbol);
        if (markPrice == 0) {
            return List.of(OrderResult.fail("無法取得市價"));
        }

        // 平倉方向：多倉用 SELL，空倉用 BUY
        String closeSide = isLong ? "SELL" : "BUY";

        // 掛反向 LIMIT 平倉單（用市價附近的價格）
        // 做多平倉: 賣出價略低於市價以確保成交
        // 做空平倉: 買入價略高於市價以確保成交
        double closePrice = isLong ? markPrice * 0.999 : markPrice * 1.001;

        OrderResult closeOrder = placeLimitOrder(symbol, closeSide, closePrice, closeQty);

        // 記錄平倉到資料庫
        if (closeOrder.isSuccess()) {
            try {
                tradeRecordService.recordClose(symbol, closeOrder, "SIGNAL_CLOSE");
            } catch (Exception e) {
                log.error("平倉紀錄寫入失敗（不影響交易）: {}", e.getMessage());
            }
        }

        List<OrderResult> results = new ArrayList<>();
        results.add(closeOrder);

        // 如果不是全平，需要重新掛 SL（如果有 newStopLoss 的話）
        if (closeRatio < 1.0 && signal.getNewStopLoss() != null) {
            double remainingQty = absPosition - closeQty;
            String slSide = isLong ? "SELL" : "BUY";
            OrderResult newSl = placeStopLoss(symbol, slSide, signal.getNewStopLoss(), remainingQty);
            results.add(newSl);
        }

        return results;
    }

    /**
     * MOVE_SL: 移動止損/止盈
     * 1. 取消所有舊掛單（SL + TP）
     * 2. 掛新的 STOP_MARKET（如果有新 SL）
     * 3. 掛新的 TAKE_PROFIT_MARKET（如果有新 TP）
     */
    public List<OrderResult> executeMoveSL(TradeSignal signal) {
        String symbol = signal.getSymbol();

        // 1. 取得持倉
        double positionAmt = getCurrentPositionAmount(symbol);
        if (positionAmt == 0) {
            return List.of(OrderResult.fail("無持倉，無法修改 TP/SL"));
        }

        boolean isLong = positionAmt > 0;
        double absPosition = Math.abs(positionAmt);
        String closeSide = isLong ? "SELL" : "BUY";

        // 2. 取消所有掛單（包含舊的 SL 和 TP）
        cancelAllOrders(symbol);

        // 取得舊的 SL 價（從 DB 紀錄中）
        double oldSl = tradeRecordService.findOpenTrade(symbol)
                .map(t -> t.getStopLoss() != null ? t.getStopLoss() : 0.0)
                .orElse(0.0);

        List<OrderResult> results = new ArrayList<>();

        // 3. 掛新的 STOP_MARKET（如果有新 SL）
        if (signal.getNewStopLoss() != null && signal.getNewStopLoss() > 0) {
            double newSl = signal.getNewStopLoss();
            log.info("移動止損: {} 舊SL={} 新SL={} 持倉={}", symbol, oldSl, newSl, positionAmt);

            OrderResult slOrder = placeStopLoss(symbol, closeSide, newSl, absPosition);
            results.add(slOrder);

            // 記錄移動止損到資料庫
            if (slOrder.isSuccess()) {
                try {
                    tradeRecordService.recordMoveSL(symbol, slOrder, oldSl, newSl);
                } catch (Exception e) {
                    log.error("移動止損紀錄寫入失敗（不影響交易）: {}", e.getMessage());
                }
            }
        }

        // 4. 掛新的 TAKE_PROFIT_MARKET（如果有新 TP）
        if (signal.getTakeProfits() != null && !signal.getTakeProfits().isEmpty()) {
            double newTp = signal.getTakeProfits().get(0);
            log.info("更新止盈: {} 新TP={} 持倉={}", symbol, newTp, positionAmt);

            OrderResult tpOrder = placeTakeProfit(symbol, closeSide, newTp, absPosition);
            results.add(tpOrder);

            if (!tpOrder.isSuccess()) {
                log.warn("新止盈單失敗: {}", tpOrder.getErrorMessage());
            }
        }

        if (results.isEmpty()) {
            return List.of(OrderResult.fail("TP-SL 修改訊號缺少新的 TP 或 SL"));
        }

        return results;
    }

    // ==================== 內部方法 ====================

    /**
     * 以損定倉計算下單數量
     * qty = fixedLossPerTrade / |entry - SL|
     */
    public double calculateFixedRiskQuantity(double entryPrice, double stopLoss) {
        double riskDistance = Math.abs(entryPrice - stopLoss);
        if (riskDistance == 0) {
            throw new IllegalArgumentException("入場價與止損價不可相同");
        }
        return riskConfig.getFixedLossPerTrade() / riskDistance;
    }

    private String formatPrice(double price) {
        if (price >= 1000) {
            return String.format("%.1f", price);
        } else if (price >= 1) {
            return String.format("%.2f", price);
        } else {
            return String.format("%.4f", price);
        }
    }

    private String formatQuantity(String symbol, double quantity) {
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
