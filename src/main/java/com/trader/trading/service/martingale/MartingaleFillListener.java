package com.trader.trading.service.martingale;

import com.google.gson.JsonObject;
import com.trader.trading.service.LayerFillTracker;
import com.trader.trading.service.UserDataEventObserver;
import org.springframework.stereotype.Component;

/**
 * Martingale 專用的 User Data Stream 旁路監聽器
 * 僅處理 ORDER_TRADE_UPDATE 的實際成交事件。
 */
@Component
public class MartingaleFillListener implements UserDataEventObserver {

    private final LayerFillTracker layerFillTracker;

    public MartingaleFillListener(LayerFillTracker layerFillTracker) {
        this.layerFillTracker = layerFillTracker;
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

        layerFillTracker.recordFillByOrderId(orderId, lastQty, lastPrice);

        String status = order.has("X") ? order.get("X").getAsString() : "";
        if ("FILLED".equals(status) || "CANCELED".equals(status) || "EXPIRED".equals(status) || "REJECTED".equals(status)) {
            layerFillTracker.clearOrder(orderId);
        }
    }
}
