package com.trader.trading.service.martingale;

import com.google.gson.JsonObject;
import com.trader.trading.service.LayerFillTracker;
import com.trader.trading.service.MartingaleSessionManager;
import com.trader.trading.service.UserDataEventObserver;
import org.springframework.stereotype.Component;

/**
 * Martingale 專用的 User Data Stream 旁路監聽器
 * 僅處理 ORDER_TRADE_UPDATE 的實際成交事件。
 * ENTRY 層成交後觸發 TP 動態更新。
 */
@Component
public class MartingaleFillListener implements UserDataEventObserver {

    private final LayerFillTracker layerFillTracker;
    private final MartingaleTpManager tpManager;
    private final MartingaleSessionManager sessionManager;

    public MartingaleFillListener(LayerFillTracker layerFillTracker,
                                   MartingaleTpManager tpManager,
                                   MartingaleSessionManager sessionManager) {
        this.layerFillTracker = layerFillTracker;
        this.tpManager = tpManager;
        this.sessionManager = sessionManager;
    }

    @Override
    public void onEvent(JsonObject event) {
        if (event == null) {
            return;
        }
        String eventType = event.has("e") ? event.get("e").getAsString() : "";
        if (!"ORDER_TRADE_UPDATE".equals(eventType)) {
            return;
        }
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
                            .ifPresent(s -> s.markFilledLayer());
                }
            }
        }
    }
}
