package com.trader.trading.service.martingale;

import com.google.gson.JsonObject;
import com.trader.trading.service.LayerFillTracker;
import com.trader.trading.service.MartingaleSessionManager;
import com.trader.trading.service.UserDataEventObserver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Martingale 專用的 User Data Stream 旁路監聽器
 * 處理兩類事件：
 * 1. ORDER_TRADE_UPDATE — ENTRY 層成交後觸發 TP 動態更新；偵測非 ENTRY 的平倉成交（fallback）
 * 2. ALGO_UPDATE — 偵測 TP Algo 觸發，清理 session 和殘留掛單
 */
@Slf4j
@Component
public class MartingaleFillListener implements UserDataEventObserver {

    private final LayerFillTracker layerFillTracker;
    private final MartingaleTpManager tpManager;
    private final MartingaleSessionManager sessionManager;
    private final MartingaleStateStore stateStore;

    public MartingaleFillListener(LayerFillTracker layerFillTracker,
                                   MartingaleTpManager tpManager,
                                   MartingaleSessionManager sessionManager,
                                   MartingaleStateStore stateStore) {
        this.layerFillTracker = layerFillTracker;
        this.tpManager = tpManager;
        this.sessionManager = sessionManager;
        this.stateStore = stateStore;
    }

    @Override
    public void onEvent(JsonObject event) {
        if (event == null) {
            return;
        }
        String eventType = event.has("e") ? event.get("e").getAsString() : "";

        switch (eventType) {
            case "ORDER_TRADE_UPDATE" -> handleOrderTradeUpdate(event);
            case "ALGO_UPDATE" -> handleAlgoUpdate(event);
        }
    }

    /**
     * 處理 ORDER_TRADE_UPDATE：
     * - 已註冊的 ENTRY 單成交 → 更新 fill tracker + 動態 TP
     * - 非 ENTRY 的 MARKET FILLED 且 symbol 有 active session → fallback TP 成交偵測
     */
    private void handleOrderTradeUpdate(JsonObject event) {
        JsonObject order = event.getAsJsonObject("o");
        if (order == null) {
            return;
        }

        String executionType = order.has("x") ? order.get("x").getAsString() : "";
        if (!"TRADE".equals(executionType)) {
            return;
        }

        String orderId = order.has("i") ? String.valueOf(order.get("i").getAsLong()) : null;
        double lastQty = order.has("l") ? order.get("l").getAsDouble() : 0.0;
        double lastPrice = order.has("L") ? order.get("L").getAsDouble() : 0.0;

        if (orderId == null || lastQty <= 0 || lastPrice <= 0) {
            return;
        }

        // 只有已註冊的 ENTRY 單才會在 orderRefs 中，recordFillByOrderId 對未註冊的 orderId 直接跳過
        boolean recorded = layerFillTracker.recordFillByOrderId(orderId, lastQty, lastPrice);

        String status = order.has("X") ? order.get("X").getAsString() : "";
        if ("FILLED".equals(status) || "CANCELED".equals(status) || "EXPIRED".equals(status) || "REJECTED".equals(status)) {
            layerFillTracker.clearOrder(orderId);
        }

        // ENTRY 層有新成交 → 觸發 TP 動態更新
        if (recorded) {
            String symbol = order.has("s") ? order.get("s").getAsString() : null;
            if (symbol != null) {
                tpManager.updateTakeProfit(symbol);

                // ENTRY 單完全成交 → 更新 session 的 filledLayers
                if ("FILLED".equals(status)) {
                    sessionManager.getActiveSession(symbol)
                            .ifPresent(s -> {
                                s.markFilledLayer();
                                if (stateStore != null) stateStore.persistSession(s);
                            });
                }
                if (stateStore != null) stateStore.persistFill(symbol);
            }
            return;
        }

        // Fallback：非 ENTRY 的 FILLED MARKET 訂單 → 可能是 TP 觸發的市價平倉
        if ("FILLED".equals(status)) {
            String orderType = order.has("o") ? order.get("o").getAsString() : "";
            if ("MARKET".equals(orderType)) {
                String symbol = order.has("s") ? order.get("s").getAsString() : null;
                if (symbol != null && sessionManager.getActiveSession(symbol).isPresent()) {
                    log.info("Martingale fallback 偵測到非 ENTRY 的 MARKET FILLED: symbol={} orderId={}", symbol, orderId);
                    tpManager.handleTpFilled(symbol);
                }
            }
        }
    }

    /**
     * 處理 ALGO_UPDATE：
     * 當 TP Algo 觸發（algoStatus=TRIGGERED）且 algoId 匹配 session 的 currentTpOrderId 時，
     * 觸發 session 清理和殘留掛單取消。
     * 這是 TP 成交偵測的主要路徑（比 ORDER_TRADE_UPDATE fallback 更早觸發）。
     */
    private void handleAlgoUpdate(JsonObject event) {
        JsonObject order = event.getAsJsonObject("o");
        if (order == null) {
            return;
        }

        String algoStatus = order.has("X") ? order.get("X").getAsString() : "";
        if (!"TRIGGERED".equals(algoStatus)) {
            return;
        }

        String orderType = order.has("o") ? order.get("o").getAsString() : "";
        if (!"TAKE_PROFIT_MARKET".equals(orderType)) {
            return;
        }

        String algoId = order.has("aid") ? String.valueOf(order.get("aid").getAsLong()) : null;
        String symbol = order.has("s") ? order.get("s").getAsString() : null;
        if (algoId == null || symbol == null) {
            return;
        }

        // 比對 algoId 與 session 的 currentTpOrderId
        sessionManager.getActiveSession(symbol).ifPresent(session -> {
            String tpOrderId = session.getCurrentTpOrderId();
            if (algoId.equals(tpOrderId)) {
                log.info("Martingale TP Algo 觸發偵測: symbol={} algoId={}", symbol, algoId);
                tpManager.handleTpFilled(symbol);
            }
        });
    }
}
