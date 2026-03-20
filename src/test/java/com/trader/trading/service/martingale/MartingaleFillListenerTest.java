package com.trader.trading.service.martingale;

import com.google.gson.JsonObject;
import com.trader.trading.service.LayerFillTracker;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MartingaleFillListenerTest {

    /** 測試用 no-op TpManager，避免 JDK 23 Mockito mock 限制 */
    private final MartingaleTpManager noOpTpManager = null;

    private MartingaleFillListener createListener(LayerFillTracker tracker) {
        // tpManager 為 null 時，FillListener 中觸發 TP 更新會 NPE
        // 因此用 test-friendly 的方式：直接用 tracker 驗證 fill，TP 觸發另行測試
        // 這裡改為不依賴 tpManager 的測試路徑：未註冊的 orderId 不會觸發 TP
        return new MartingaleFillListener(tracker, noOpTpManager);
    }

    @Test
    void accumulatesPartialFillsByOrderId() {
        LayerFillTracker tracker = new LayerFillTracker();
        tracker.registerOrder("1001", "BTCUSDT", 2);

        // 因為 recorded=true 會呼叫 tpManager，我們需要一個可用的 tpManager
        // 直接建構一個 stub
        MartingaleFillListener listener = new MartingaleFillListener(tracker, new NoOpTpManager());

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

        // NEW execution type → recorded=false → tpManager 不被呼叫，null 安全
        MartingaleFillListener listener = new MartingaleFillListener(tracker, noOpTpManager);

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

    /** 測試用 stub — 繼承 MartingaleTpManager 但 override updateTakeProfit 為 no-op */
    private static class NoOpTpManager extends MartingaleTpManager {
        NoOpTpManager() {
            super(null, null, null, null, null);
        }

        @Override
        public void updateTakeProfit(String symbol) {
            // no-op for testing
        }
    }
}
