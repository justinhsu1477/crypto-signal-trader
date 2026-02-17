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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

@Slf4j
@Service
public class BinanceFuturesService {

    private final OkHttpClient httpClient;
    private final BinanceConfig binanceConfig;
    private final RiskConfig riskConfig;
    private final TradeRecordService tradeRecordService;
    private final SignalDeduplicationService deduplicationService;
    private final DiscordWebhookService discordWebhookService;
    private final Gson gson = new Gson();

    /**
     * Per-symbol 互斥鎖 — 防止同一幣種的並發操作
     * 同一個 symbol 的 ENTRY / CLOSE / MOVE_SL 一次只能有一個 thread 執行
     */
    private final ConcurrentHashMap<String, ReentrantLock> symbolLocks = new ConcurrentHashMap<>();

    // SL/TP 下單重試配置
    private static final int ORDER_MAX_RETRIES = 2;
    private static final long[] ORDER_RETRY_DELAYS_MS = {1000, 3000};

    public BinanceFuturesService(OkHttpClient httpClient, BinanceConfig binanceConfig,
                                  RiskConfig riskConfig, TradeRecordService tradeRecordService,
                                  SignalDeduplicationService deduplicationService,
                                  DiscordWebhookService discordWebhookService) {
        this.httpClient = httpClient;
        this.binanceConfig = binanceConfig;
        this.riskConfig = riskConfig;
        this.tradeRecordService = tradeRecordService;
        this.deduplicationService = deduplicationService;
        this.discordWebhookService = discordWebhookService;
    }

    // ==================== 帳戶相關 ====================

    public String getAccountBalance() {
        String endpoint = "/fapi/v2/balance";
        return sendSignedGet(endpoint, Map.of());
    }

    /**
     * 取得 USDT 可用餘額
     * ⚠️ API 失敗時拋出 RuntimeException，避免用 0 餘額算出 0 倉位
     */
    public double getAvailableBalance() {
        String response = getAccountBalance();
        try {
            JsonArray balances = gson.fromJson(response, JsonArray.class);
            for (JsonElement elem : balances) {
                JsonObject bal = elem.getAsJsonObject();
                if ("USDT".equals(bal.get("asset").getAsString())) {
                    return bal.get("availableBalance").getAsDouble();
                }
            }
            throw new RuntimeException("找不到 USDT 餘額");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("查詢帳戶餘額失敗: " + e.getMessage(), e);
        }
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
     * ⚠️ API 失敗時拋出 RuntimeException，避免誤判為「無持倉」而重複開倉
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
            throw new RuntimeException("查詢持倉失敗，拒絕交易: " + e.getMessage(), e);
        }
        return 0;
    }

    /**
     * 取得市場價格
     * ⚠️ API 失敗時拋出 RuntimeException，避免回傳 0 導致偏離檢查失效
     */
    public double getMarkPrice(String symbol) {
        String endpoint = "/fapi/v1/ticker/price";
        String response = sendPublicGet(endpoint + "?symbol=" + symbol);
        try {
            JsonObject json = gson.fromJson(response, JsonObject.class);
            return json.get("price").getAsDouble();
        } catch (Exception e) {
            throw new RuntimeException("取得市價失敗，拒絕交易: " + e.getMessage(), e);
        }
    }

    /**
     * 取得目前活躍持倉數量（positionAmt != 0 的交易對數量）
     * ⚠️ API 失敗時拋出 RuntimeException，避免回傳 0 繞過持倉上限檢查
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
            throw new RuntimeException("查詢持倉數量失敗，拒絕交易: " + e.getMessage(), e);
        }
        return count;
    }

    /**
     * 檢查是否有未成交的 LIMIT 入場掛單
     * ⚠️ API 失敗時拋出 RuntimeException，避免回傳 false 導致重複掛單
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
            throw new RuntimeException("檢查掛單失敗，拒絕交易: " + e.getMessage(), e);
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
        params.put("newClientOrderId", generateClientOrderId("SL"));

        log.info("設定止損: {} {} stopPrice={}", symbol, side, stopPrice);
        String response = sendSignedPostWithRetry(endpoint, params);
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
        params.put("newClientOrderId", generateClientOrderId("TP"));

        log.info("設定止盈: {} {} stopPrice={}", symbol, side, stopPrice);
        String response = sendSignedPostWithRetry(endpoint, params);
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

    /**
     * 只取消 STOP_MARKET 和 TAKE_PROFIT_MARKET 訂單，保留 LIMIT 入場單
     * 用於 DCA 補倉時：需要更新 SL/TP 但不能取消已掛的入場單
     */
    public void cancelSLTPOrders(String symbol) {
        String response = getOpenOrders(symbol);
        try {
            JsonArray orders = gson.fromJson(response, JsonArray.class);
            for (JsonElement elem : orders) {
                JsonObject order = elem.getAsJsonObject();
                String type = order.get("type").getAsString();
                if ("STOP_MARKET".equals(type) || "TAKE_PROFIT_MARKET".equals(type)) {
                    long orderId = order.get("orderId").getAsLong();
                    log.info("DCA: 取消舊的 {} 訂單 {}", type, orderId);
                    cancelOrder(symbol, orderId);
                }
            }
        } catch (Exception e) {
            log.error("取消 SL/TP 訂單失敗: {}", e.getMessage());
            throw new RuntimeException("取消 SL/TP 訂單失敗: " + e.getMessage(), e);
        }
    }

    /**
     * 查詢當前掛單中的 SL/TP 價格
     * @return double[2]: [0]=STOP_MARKET stopPrice, [1]=TAKE_PROFIT_MARKET stopPrice; 0 表示不存在
     */
    public double[] getCurrentSLTPPrices(String symbol) {
        double slPrice = 0;
        double tpPrice = 0;
        try {
            String response = getOpenOrders(symbol);
            JsonArray orders = gson.fromJson(response, JsonArray.class);
            for (JsonElement elem : orders) {
                JsonObject order = elem.getAsJsonObject();
                String type = order.get("type").getAsString();
                if ("STOP_MARKET".equals(type)) {
                    slPrice = order.get("stopPrice").getAsDouble();
                } else if ("TAKE_PROFIT_MARKET".equals(type)) {
                    tpPrice = order.get("stopPrice").getAsDouble();
                }
            }
        } catch (Exception e) {
            log.warn("查詢 SL/TP 價格失敗: {}", e.getMessage());
        }
        return new double[]{slPrice, tpPrice};
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
      ReentrantLock lock = symbolLocks.computeIfAbsent(signal.getSymbol(), k -> new ReentrantLock());
      lock.lock();
      try {
        return executeSignalInternal(signal);
      } catch (RuntimeException e) {
        log.error("交易前置檢查失敗，拒絕執行: {}", e.getMessage());
        return List.of(OrderResult.fail("前置檢查失敗: " + e.getMessage()));
      } finally {
        lock.unlock();
      }
    }

    /**
     * executeSignal 內部實作，被外層 try-catch 保護。
     * API 查詢失敗會拋出 RuntimeException，由外層攔截並拒絕交易。
     */
    private List<OrderResult> executeSignalInternal(TradeSignal signal) {
        String symbol = signal.getSymbol();

        // 1. 交易對白名單檢查
        if (!riskConfig.isSymbolAllowed(symbol)) {
            log.warn("交易對不在白名單: {}, 允許清單: {}", symbol, riskConfig.getAllowedSymbols());
            return List.of(OrderResult.fail("交易對不在白名單: " + symbol + ", 允許: " + riskConfig.getAllowedSymbols()));
        }

        // 1b. 查帳戶餘額（後續熔斷 + 倉位計算都會用）
        double balance = getAvailableBalance();
        double riskAmount = balance * riskConfig.getRiskPercent();
        log.info("帳戶餘額: {} USDT, 1R = {} USDT ({}%)", balance, riskAmount, riskConfig.getRiskPercent() * 100);

        // 1c. 每日虧損熔斷（固定上限，不隨餘額縮水而變鬆）
        double todayLoss = tradeRecordService.getTodayRealizedLoss();
        double maxDailyLoss = riskConfig.getMaxDailyLossUsdt();
        if (maxDailyLoss > 0 && Math.abs(todayLoss) >= maxDailyLoss) {
            String msg = String.format("每日虧損熔斷! 今日已虧損 %.2f USDT，上限 %.2f USDT",
                    todayLoss, maxDailyLoss);
            log.error(msg);
            discordWebhookService.sendNotification("🚨 每日虧損熔斷", msg, DiscordWebhookService.COLOR_RED);
            return List.of(OrderResult.fail("每日虧損已達上限，暫停交易"));
        }

        // 2. 持倉限制檢查 + DCA 補倉邏輯
        double currentPosition = getCurrentPositionAmount(symbol);
        if (currentPosition != 0) {
            if (signal.isDca()) {
                // DCA 模式：檢查補倉次數是否已達上限
                int dcaCount = tradeRecordService.getDcaCount(symbol);
                int maxDca = riskConfig.getMaxDcaPerSymbol();
                if (dcaCount >= maxDca - 1) {  // maxDca 包含首次入場，dcaCount 從 0 開始
                    log.warn("DCA 已達上限: {} 已補倉 {} 次，上限 {} 層", symbol, dcaCount, maxDca);
                    return List.of(OrderResult.fail("DCA 已達上限: " + symbol + " 已 " + (dcaCount + 1) + "/" + maxDca + " 層"));
                }

                // DCA 方向檢查：必須與現有持倉同方向
                boolean isCurrentLong = currentPosition > 0;
                // 如果訊號沒帶 side，從持倉推斷
                if (signal.getSide() == null) {
                    signal.setSide(isCurrentLong ? TradeSignal.Side.LONG : TradeSignal.Side.SHORT);
                    log.info("DCA 自動推斷方向: {} (持倉 {} BTC)", signal.getSide(), currentPosition);
                }
                boolean isSignalLong = signal.getSide() == TradeSignal.Side.LONG;
                if (isCurrentLong != isSignalLong) {
                    log.warn("DCA 方向不一致: 持倉={}, 訊號={}", isCurrentLong ? "LONG" : "SHORT", signal.getSide());
                    return List.of(OrderResult.fail("DCA 補倉方向與現有持倉不一致"));
                }

                log.info("DCA 補倉允許: {} 目前第 {} 層，上限 {} 層", symbol, dcaCount + 1, maxDca);
            } else {
                // 非 DCA：已有持倉時拒絕新開倉
                log.warn("已有持倉 {} BTC，拒絕新開倉（如需補倉請使用 DCA）", currentPosition);
                return List.of(OrderResult.fail("已有持倉，拒絕新開倉（如需補倉請使用 is_dca=true）"));
            }
        }

        // 2b. 檢查未成交入場掛單（DCA 時跳過，允許多張 LIMIT 同時存在）
        if (!signal.isDca() && hasOpenEntryOrders(symbol)) {
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

        // 5. 價格偏離檢查（markPrice 失敗會拋異常，由外層 catch）
        double markPrice = getMarkPrice(symbol);
        if (markPrice <= 0) {
            return List.of(OrderResult.fail("無法取得市價，拒絕交易"));
        }
        double deviation = Math.abs(entry - markPrice) / markPrice;
        if (deviation > 0.10) {
            log.warn("入場價 {} 偏離市價 {} 超過 10% ({}%)", entry, markPrice, String.format("%.1f", deviation * 100));
            return List.of(OrderResult.fail("入場價偏離市價超過 10%"));
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

        // 7. 動態以損定倉: 1R = 帳戶餘額 × riskPercent, DCA 用 2R
        double riskMultiplier = signal.isDca() ? riskConfig.getDcaRiskMultiplier() : 1.0;
        double effectiveRiskAmount = riskAmount * riskMultiplier;
        double riskDistance = Math.abs(entry - sl);
        double quantity = effectiveRiskAmount / riskDistance;
        if (signal.isDca()) {
            log.info("DCA 倉位計算: {}R = {} × {} = {} USDT", riskMultiplier, riskAmount, riskMultiplier, effectiveRiskAmount);
        }

        // 7b. 名目價值上限 cap — 防止窄止損產生超大倉位
        double notional = entry * quantity;
        double maxNotional = riskConfig.getMaxPositionUsdt();
        if (maxNotional > 0 && notional > maxNotional) {
            double cappedQty = maxNotional / entry;
            log.warn("倉位 cap 觸發: 原始數量={} (名目 {} USDT), 上限數量={} (名目 {} USDT)",
                    quantity, notional, cappedQty, maxNotional);
            quantity = cappedQty;
        }

        // 7c. 保證金充足性檢查 — 確保不超過可用餘額的 90%
        double requiredMargin = entry * quantity / leverage;
        double maxMargin = balance * 0.90;
        if (requiredMargin > maxMargin) {
            double marginCappedQty = maxMargin * leverage / entry;
            log.warn("保證金不足 cap: 需要 {} USDT，可用 {} USDT (90%), 數量 {} → {}",
                    requiredMargin, maxMargin, quantity, marginCappedQty);
            quantity = marginCappedQty;
        }

        // 7d. 最低下單量檢查 — Binance BTC 最小 0.001, 其他幣種最小 notional 5 USDT
        double minNotional = 5.0;
        if (entry * quantity < minNotional) {
            String msg = String.format("倉位太小: 名目 %.2f USDT < 最低 %.0f USDT (餘額 %.2f, 1R=%.2f)",
                    entry * quantity, minNotional, balance, riskAmount);
            log.warn(msg);
            return List.of(OrderResult.fail("餘額不足，計算出的倉位低於最低下單量"));
        }

        log.info("以損定倉: 餘額={}, 1R={}, 實際風險={}(×{}), 風險距離={}, 數量={}, 名目={} USDT, 保證金={} USDT",
                balance, riskAmount, effectiveRiskAmount, riskMultiplier,
                riskDistance, quantity, entry * quantity, entry * quantity / leverage);

        // 入場方向
        String entrySide = signal.getSide() == TradeSignal.Side.SHORT ? "SELL" : "BUY";
        String closeSide = signal.getSide() == TradeSignal.Side.SHORT ? "BUY" : "SELL";

        // 8. 掛 LIMIT 入場單
        OrderResult entryOrder = placeLimitOrder(symbol, entrySide, entry, quantity);
        if (!entryOrder.isSuccess()) {
            log.error("入場單失敗: {}", entryOrder.getErrorMessage());
            return List.of(entryOrder);
        }
        // 附加風控摘要到入場單（供 Discord 通知使用）
        entryOrder.setRiskSummary(String.format("餘額: %.2f | %s: %.2f (%.0f%%×%.0f) | 保證金: %.2f",
                balance, signal.isDca() ? "DCA風險" : "1R",
                effectiveRiskAmount, riskConfig.getRiskPercent() * 100, riskMultiplier,
                entry * quantity / leverage));

        // === DCA 補倉 SL/TP 處理（與首次入場不同） ===
        OrderResult slOrder;
        OrderResult tpOrder = null;

        if (signal.isDca()) {
            // DCA: 取消舊的 SL/TP（保留 LIMIT 入場單），重掛覆蓋全部持倉的 SL/TP
            cancelSLTPOrders(symbol);

            double totalQty = Math.abs(currentPosition) + quantity;
            log.info("DCA SL/TP 重掛: 舊持倉={}, 新掛單={}, 總數量={}", Math.abs(currentPosition), quantity, totalQty);

            // 掛新 SL（DCA 必帶 new_stop_loss）
            slOrder = placeStopLoss(symbol, closeSide, signal.getNewStopLoss(), totalQty);

            // 掛新 TP（如果有）
            if (signal.getNewTakeProfit() != null && signal.getNewTakeProfit() > 0) {
                tpOrder = placeTakeProfit(symbol, closeSide, signal.getNewTakeProfit(), totalQty);
                if (!tpOrder.isSuccess()) {
                    log.warn("DCA 止盈單失敗（不影響入場和止損）: {}", tpOrder.getErrorMessage());
                }
            }
        } else {
            // 正常入場: SL/TP 按入場數量掛

            // 9. 掛 STOP_MARKET 止損單
            slOrder = placeStopLoss(symbol, closeSide, sl, quantity);

            // 10. 掛 TAKE_PROFIT_MARKET 止盈單（如果訊號有給 TP）
            if (signal.getTakeProfits() != null && !signal.getTakeProfits().isEmpty()) {
                double tp = signal.getTakeProfits().get(0);
                tpOrder = placeTakeProfit(symbol, closeSide, tp, quantity);
                if (!tpOrder.isSuccess()) {
                    log.warn("止盈單失敗（不影響入場和止損）: {}", tpOrder.getErrorMessage());
                    discordWebhookService.sendNotification(
                            "⚠️ 止盈單失敗（需手動設定）",
                            String.format("%s %s\n入場和止損已正常設定\n止盈錯誤: %s\n請手動設定 TP",
                                    symbol, signal.getSide(), tpOrder.getErrorMessage()),
                            DiscordWebhookService.COLOR_YELLOW);
                }
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
                OrderResult marketClose = placeMarketOrder(symbol, closeSide, quantity);
                if (!marketClose.isSuccess()) {
                    // 最後防線失敗 — 必須人工介入
                    String alert = String.format("CRITICAL: %s 止損單+取消單+市價平倉全部失敗! 請立即手動處理! 數量=%s",
                            symbol, formatQuantity(symbol, quantity));
                    log.error(alert);
                    discordWebhookService.sendNotification("🚨 Fail-Safe 全部失敗",
                            alert, DiscordWebhookService.COLOR_RED);
                    tradeRecordService.recordFailSafe(symbol,
                            "{\"reason\":\"所有自動保護措施失敗\",\"market_close_error\":\"" + marketClose.getErrorMessage() + "\"}");
                }
            }
            return List.of(entryOrder, slOrder);
        }

        // 12. 記錄交易到資料庫
        try {
            if (signal.isDca()) {
                // DCA: 更新現有 Trade 的均價/數量/SL
                tradeRecordService.recordDcaEntry(symbol, signal, entryOrder, effectiveRiskAmount);
            } else {
                // 首次入場: 建立新 Trade（含 signalHash 用於去重）
                String signalHash = deduplicationService.generateHash(signal);
                tradeRecordService.recordEntry(signal, entryOrder, slOrder, leverage,
                        effectiveRiskAmount, signalHash);
            }
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
        ReentrantLock lock = symbolLocks.computeIfAbsent(symbol, k -> new ReentrantLock());
        lock.lock();
        try {
            return executeCloseInternal(signal);
        } catch (RuntimeException e) {
            log.error("平倉前置檢查失敗: {}", e.getMessage());
            return List.of(OrderResult.fail("平倉失敗: " + e.getMessage()));
        } finally {
            lock.unlock();
        }
    }

    private List<OrderResult> executeCloseInternal(TradeSignal signal) {
        String symbol = signal.getSymbol();

        // 1. 取得持倉（API 失敗會拋異常）
        double positionAmt;
        try {
            positionAmt = getCurrentPositionAmount(symbol);
        } catch (RuntimeException e) {
            log.error("平倉前查詢持倉失敗: {}", e.getMessage());
            return List.of(OrderResult.fail("查詢持倉失敗: " + e.getMessage()));
        }

        // 1b. Symbol fallback：該幣無持倉 → 查 DB 有沒有其他 OPEN trade
        if (positionAmt == 0) {
            String resolved = resolveSymbolFallback(symbol);
            if (resolved != null) {
                log.info("平倉 symbol fallback: {} 無持倉，改用 DB OPEN trade: {}", symbol, resolved);
                signal = TradeSignal.builder()
                        .symbol(resolved)
                        .signalType(signal.getSignalType())
                        .closeRatio(signal.getCloseRatio())
                        .newStopLoss(signal.getNewStopLoss())
                        .newTakeProfit(signal.getNewTakeProfit())
                        .build();
                symbol = resolved;
                try {
                    positionAmt = getCurrentPositionAmount(symbol);
                } catch (RuntimeException e) {
                    return List.of(OrderResult.fail("查詢持倉失敗: " + e.getMessage()));
                }
            }
            if (positionAmt == 0) {
                return List.of(OrderResult.fail("無持倉可平"));
            }
        }

        // 正數=多倉, 負數=空倉
        boolean isLong = positionAmt > 0;
        double absPosition = Math.abs(positionAmt);

        // 2. 計算平倉數量
        double closeRatio = signal.getCloseRatio() != null ? signal.getCloseRatio() : 1.0;
        double closeQty = absPosition * closeRatio;
        boolean isPartialClose = closeRatio < 1.0;

        log.info("平倉: {} 持倉={} ratio={} 平倉數量={} partial={}",
                symbol, positionAmt, closeRatio, closeQty, isPartialClose);

        // 3. 部分平倉前：先查詢現有 SL/TP 價格（取消前保存）
        double oldSlPrice = 0;
        double oldTpPrice = 0;
        if (isPartialClose) {
            double[] prices = getCurrentSLTPPrices(symbol);
            oldSlPrice = prices[0];
            oldTpPrice = prices[1];
            log.info("部分平倉: 保存舊 SL={} TP={} 用於重掛", oldSlPrice, oldTpPrice);
        }

        // 4. 取消所有掛單
        cancelAllOrders(symbol);

        // 5. 取得市價作為平倉價格（API 失敗會拋異常）
        double markPrice;
        try {
            markPrice = getMarkPrice(symbol);
        } catch (RuntimeException e) {
            log.error("平倉前取得市價失敗: {}", e.getMessage());
            return List.of(OrderResult.fail("取得市價失敗: " + e.getMessage()));
        }
        if (markPrice <= 0) {
            return List.of(OrderResult.fail("無法取得市價"));
        }

        // 平倉方向：多倉用 SELL，空倉用 BUY
        String closeSide = isLong ? "SELL" : "BUY";

        // 掛反向 LIMIT 平倉單（用市價附近的價格）
        double closePrice = isLong ? markPrice * 0.999 : markPrice * 1.001;

        OrderResult closeOrder = placeLimitOrder(symbol, closeSide, closePrice, closeQty);

        // 記錄平倉到資料庫
        if (closeOrder.isSuccess()) {
            try {
                if (isPartialClose) {
                    tradeRecordService.recordPartialClose(symbol, closeOrder, closeRatio, "SIGNAL_CLOSE");
                } else {
                    tradeRecordService.recordClose(symbol, closeOrder, "SIGNAL_CLOSE");
                }
            } catch (Exception e) {
                log.error("平倉紀錄寫入失敗（不影響交易）: {}", e.getMessage());
            }
        }

        List<OrderResult> results = new ArrayList<>();
        results.add(closeOrder);

        // 6. 部分平倉：一定要重掛 SL（保護剩餘倉位）
        if (isPartialClose) {
            double remainingQty = absPosition - closeQty;
            String slSide = isLong ? "SELL" : "BUY";

            // === SL 重掛邏輯 ===
            // 優先級：signal 帶的新 SL > DB 開倉價（成本保護）> 舊 SL
            double slToUse;
            if (signal.getNewStopLoss() != null && signal.getNewStopLoss() > 0) {
                // 訊號明確帶了新 SL 價格
                slToUse = signal.getNewStopLoss();
                log.info("部分平倉: 使用訊號指定 SL={}", slToUse);
            } else if (signal.getNewStopLoss() == null && signal.getNewTakeProfit() == null
                    && oldSlPrice == 0) {
                // 什麼都沒有（沒新SL、沒新TP、沒舊SL）→ 嘗試用開倉價做成本保護
                Double entryPrice = tradeRecordService.getEntryPrice(symbol);
                if (entryPrice != null && entryPrice > 0) {
                    slToUse = entryPrice;
                    log.info("部分平倉: 無 SL 資訊，使用開倉價做成本保護 SL={}", slToUse);
                } else {
                    slToUse = 0;
                    log.warn("部分平倉: ⚠️ 無法取得 SL 價格，剩餘倉位無止損保護！");
                }
            } else if (oldSlPrice > 0) {
                // 用取消前的舊 SL
                slToUse = oldSlPrice;
                log.info("部分平倉: 使用原有 SL={}", slToUse);
            } else {
                // newStopLoss 是 null 但不是 0（成本保護場景：null 表示用開倉價）
                Double entryPrice = tradeRecordService.getEntryPrice(symbol);
                if (entryPrice != null && entryPrice > 0) {
                    slToUse = entryPrice;
                    log.info("部分平倉: 成本保護，SL 移至開倉價={}", slToUse);
                } else {
                    slToUse = oldSlPrice;
                    log.warn("部分平倉: 成本保護但無開倉價，用舊 SL={}", slToUse);
                }
            }

            if (slToUse > 0) {
                OrderResult newSl = placeStopLoss(symbol, slSide, slToUse, remainingQty);
                results.add(newSl);
            } else {
                log.error("⚠️ 部分平倉後未能重掛 SL！{} 剩餘 {} 裸奔中", symbol, remainingQty);
                results.add(OrderResult.fail("部分平倉後無法重掛 SL — 剩餘倉位無保護"));
            }

            // === TP 重掛邏輯 ===
            double tpToUse = 0;
            if (signal.getNewTakeProfit() != null && signal.getNewTakeProfit() > 0) {
                tpToUse = signal.getNewTakeProfit();
            } else if (oldTpPrice > 0) {
                tpToUse = oldTpPrice;
                log.info("部分平倉: 使用原有 TP={}", tpToUse);
            }

            if (tpToUse > 0) {
                OrderResult newTp = placeTakeProfit(symbol, slSide, tpToUse, remainingQty);
                results.add(newTp);
            }
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
        ReentrantLock lock = symbolLocks.computeIfAbsent(symbol, k -> new ReentrantLock());
        lock.lock();
        try {
            return executeMoveSLInternal(signal);
        } catch (RuntimeException e) {
            log.error("修改 TP/SL 失敗: {}", e.getMessage());
            return List.of(OrderResult.fail("修改 TP/SL 失敗: " + e.getMessage()));
        } finally {
            lock.unlock();
        }
    }

    private List<OrderResult> executeMoveSLInternal(TradeSignal signal) {
        String symbol = signal.getSymbol();

        // 1. 取得持倉（API 失敗會拋異常）
        double positionAmt;
        try {
            positionAmt = getCurrentPositionAmount(symbol);
        } catch (RuntimeException e) {
            log.error("修改 TP/SL 前查詢持倉失敗: {}", e.getMessage());
            return List.of(OrderResult.fail("查詢持倉失敗: " + e.getMessage()));
        }

        // 1b. Symbol fallback：該幣無持倉 → 查 DB 有沒有其他 OPEN trade
        if (positionAmt == 0) {
            String resolved = resolveSymbolFallback(symbol);
            if (resolved != null) {
                log.info("MOVE_SL symbol fallback: {} 無持倉，改用 DB OPEN trade: {}", symbol, resolved);
                signal = TradeSignal.builder()
                        .symbol(resolved)
                        .signalType(signal.getSignalType())
                        .newStopLoss(signal.getNewStopLoss())
                        .newTakeProfit(signal.getNewTakeProfit())
                        .build();
                symbol = resolved;
                try {
                    positionAmt = getCurrentPositionAmount(symbol);
                } catch (RuntimeException e) {
                    return List.of(OrderResult.fail("查詢持倉失敗: " + e.getMessage()));
                }
            }
            if (positionAmt == 0) {
                return List.of(OrderResult.fail("無持倉，無法修改 TP/SL"));
            }
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

        // 3. 掛新的 STOP_MARKET
        // 支援成本保護：newStopLoss=null 時查 DB 開倉價當作 SL
        Double slValue = signal.getNewStopLoss();
        if (slValue != null && slValue > 0) {
            // 訊號明確帶了 SL 價格
            log.info("移動止損: {} 舊SL={} 新SL={} 持倉={}", symbol, oldSl, slValue, positionAmt);
        } else {
            // 成本保護：「做保本處理」「止損上移至成本附近」→ 用開倉價
            Double entryPrice = tradeRecordService.getEntryPrice(symbol);
            if (entryPrice != null && entryPrice > 0) {
                slValue = entryPrice;
                log.info("成本保護: {} 舊SL={} 用開倉價做SL={} 持倉={}", symbol, oldSl, slValue, positionAmt);
            } else {
                log.warn("成本保護但無法取得開倉價: {} 舊SL={}", symbol, oldSl);
                // fallback: 用舊 SL 重掛，至少不裸奔
                if (oldSl > 0) {
                    slValue = oldSl;
                    log.info("成本保護 fallback: 用舊 SL={} 重掛", slValue);
                }
            }
        }

        if (slValue != null && slValue > 0) {
            double newSl = slValue;
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
        // 優先使用 newTakeProfit（MOVE_SL 專用），fallback 到 takeProfits（相容舊路徑）
        Double tpValue = signal.getNewTakeProfit();
        if (tpValue == null && signal.getTakeProfits() != null && !signal.getTakeProfits().isEmpty()) {
            tpValue = signal.getTakeProfits().get(0);
        }
        if (tpValue != null && tpValue > 0) {
            double newTp = tpValue;
            log.info("更新止盈: {} 新TP={} 持倉={}", symbol, newTp, positionAmt);

            OrderResult tpOrder = placeTakeProfit(symbol, closeSide, newTp, absPosition);
            results.add(tpOrder);

            if (!tpOrder.isSuccess()) {
                log.warn("新止盈單失敗: {}", tpOrder.getErrorMessage());
                discordWebhookService.sendNotification(
                        "⚠️ 新止盈單失敗（需手動設定）",
                        String.format("%s\n新TP設定失敗: %s\n請手動設定 TP",
                                symbol, tpOrder.getErrorMessage()),
                        DiscordWebhookService.COLOR_YELLOW);
            }
        }

        if (results.isEmpty()) {
            return List.of(OrderResult.fail("TP-SL 修改訊號缺少新的 TP 或 SL"));
        }

        return results;
    }

    // ==================== 內部方法 ====================

    /**
     * Symbol fallback：當訊號指定的 symbol 無持倉時，查 DB 找其他 OPEN trade
     * 場景：陳哥發「止盈50%做成本保護」沒提幣名 → AI 預設 BTCUSDT → 但實際持有 ETH
     *
     * @param originalSymbol 訊號解析出的 symbol
     * @return 替代的 symbol（如果 DB 剛好只有一筆 OPEN），或 null（無法自動判斷）
     */
    private String resolveSymbolFallback(String originalSymbol) {
        try {
            var openTrades = tradeRecordService.findAllOpenTrades();
            if (openTrades.size() == 1) {
                String dbSymbol = openTrades.get(0).getSymbol();
                if (!dbSymbol.equals(originalSymbol)) {
                    log.info("Symbol fallback: 訊號={} 但 DB 唯一 OPEN trade={}", originalSymbol, dbSymbol);
                    discordWebhookService.sendNotification(
                            "🔄 Symbol 自動修正",
                            String.format("訊號幣種: %s（無持倉）\n自動修正為: %s（DB 中唯一 OPEN trade）",
                                    originalSymbol, dbSymbol),
                            DiscordWebhookService.COLOR_BLUE);
                    return dbSymbol;
                }
            } else if (openTrades.size() > 1) {
                log.warn("Symbol fallback: {} 無持倉，但 DB 有 {} 筆 OPEN trade，無法自動判斷",
                        originalSymbol, openTrades.size());
            }
        } catch (Exception e) {
            log.warn("Symbol fallback 查詢失敗: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 以損定倉計算下單數量（含名目價值 cap）
     * qty = min( riskAmount / |entry - SL|,  maxPositionUsdt / entryPrice )
     *
     * @param balance    帳戶可用餘額 (USDT)
     * @param entryPrice 入場價
     * @param stopLoss   止損價
     */
    public double calculatePositionSize(double balance, double entryPrice, double stopLoss) {
        double riskDistance = Math.abs(entryPrice - stopLoss);
        if (riskDistance == 0) {
            throw new IllegalArgumentException("入場價與止損價不可相同");
        }
        double riskAmount = balance * riskConfig.getRiskPercent();
        double quantity = riskAmount / riskDistance;

        // 名目價值 cap
        double maxNotional = riskConfig.getMaxPositionUsdt();
        if (maxNotional > 0) {
            double cappedQty = maxNotional / entryPrice;
            quantity = Math.min(quantity, cappedQty);
        }
        return quantity;
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

    /**
     * 帶 idempotent key 的下單重試（僅用於 SL/TP）
     * 用 newClientOrderId 確保 Binance 不會重複成交
     * 只有 IOException（網路斷線/timeout）才重試，收到 HTTP 回應（含 4xx/5xx）不重試
     */
    private String sendSignedPostWithRetry(String endpoint, Map<String, String> params) {
        String clientOrderId = params.get("newClientOrderId");
        IOException lastException = null;

        for (int attempt = 0; attempt <= ORDER_MAX_RETRIES; attempt++) {
            try {
                String queryString = buildSignedQueryString(params);
                String url = binanceConfig.getBaseUrl() + endpoint;
                RequestBody body = RequestBody.create(
                        queryString, MediaType.parse("application/x-www-form-urlencoded"));
                Request request = new Request.Builder()
                        .url(url).post(body)
                        .addHeader("X-MBX-APIKEY", binanceConfig.getApiKey())
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
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
        discordWebhookService.sendNotification(
                "🔴 Binance 下單重試全部失敗",
                String.format("下單重試 %d 次全部失敗！\nclientOrderId: %s\n錯誤: %s\n請立即檢查網路連線",
                        ORDER_MAX_RETRIES + 1, clientOrderId,
                        lastException != null ? lastException.getMessage() : "unknown"),
                DiscordWebhookService.COLOR_RED);
        throw new RuntimeException("Binance order request failed after " + (ORDER_MAX_RETRIES + 1) + " retries",
                lastException);
    }

    /**
     * 產生 Binance newClientOrderId（冪等性 key）
     * 格式: {prefix}-{timestamp}-{random4hex}
     * 例如: SL-1707123456789-a3f2
     * Binance 限制: 最多 36 字元, [a-zA-Z0-9_-]
     */
    private String generateClientOrderId(String prefix) {
        String ts = String.valueOf(System.currentTimeMillis());
        String rand = Integer.toHexString((int) (Math.random() * 0xFFFF));
        return String.format("%s-%s-%s", prefix, ts, rand);
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
            discordWebhookService.sendNotification(
                    "🔴 Binance API 連線中斷",
                    String.format("API 無法連線，止損單可能無法執行！\n請求: %s %s\n錯誤: %s\n請立即檢查網路連線與 Binance API 狀態",
                            request.method(), request.url().encodedPath(), e.getMessage()),
                    DiscordWebhookService.COLOR_RED);
            throw new RuntimeException("Binance API request failed", e);
        }
    }
}
