package com.trader.trading.service.martingale;

import com.google.gson.JsonObject;
import com.trader.trading.service.LayerFillTracker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MartingaleFillListenerTest {

    @Test
    void accumulatesPartialFillsByOrderId() {
        LayerFillTracker tracker = new LayerFillTracker();
        tracker.registerOrder("1001", "BTCUSDT", 2);

        MartingaleFillListener listener = new MartingaleFillListener(tracker);

        listener.onEvent(buildOrderTradeUpdate("BTCUSDT", "1001", "TRADE", "PARTIALLY_FILLED", 0.01, 60000));
        listener.onEvent(buildOrderTradeUpdate("BTCUSDT", "1001", "TRADE", "PARTIALLY_FILLED", 0.02, 59000));

        double totalQty = tracker.getFilledQty("BTCUSDT", 2);
        double avgPrice = tracker.getWeightedAvgPrice("BTCUSDT", 2);

        assertThat(totalQty).isEqualTo(0.03);
        assertThat(avgPrice).isEqualTo(((0.01 * 60000) + (0.02 * 59000)) / 0.03);
        assertThat(tracker.getLastFillAt("BTCUSDT")).isNotNull();
    }

    @Test
    void ignoresNonTradeExecutions() {
        LayerFillTracker tracker = new LayerFillTracker();
        tracker.registerOrder("1002", "BTCUSDT", 1);

        MartingaleFillListener listener = new MartingaleFillListener(tracker);

        listener.onEvent(buildOrderTradeUpdate("BTCUSDT", "1002", "NEW", "NEW", 0.01, 60000));

        assertThat(tracker.getFilledQty("BTCUSDT", 1)).isEqualTo(0.0);
        assertThat(tracker.getLastFillAt("BTCUSDT")).isNull();
    }

    private JsonObject buildOrderTradeUpdate(String symbol,
                                             String orderId,
                                             String executionType,
                                             String status,
                                             double lastQty,
                                             double lastPrice) {
        JsonObject order = new JsonObject();
        order.addProperty("s", symbol);
        order.addProperty("i", Long.parseLong(orderId));
        order.addProperty("x", executionType);
        order.addProperty("X", status);
        order.addProperty("l", lastQty);
        order.addProperty("L", lastPrice);

        JsonObject event = new JsonObject();
        event.addProperty("e", "ORDER_TRADE_UPDATE");
        event.add("o", order);
        return event;
    }
}
