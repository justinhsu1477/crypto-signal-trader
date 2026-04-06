package com.trader.trading.service.martingale;

import com.google.gson.JsonObject;
import com.trader.trading.service.LayerFillTracker;
import com.trader.trading.service.MartingaleSessionManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MartingaleFillListenerTest {

    /** 測試用 no-op TpManager，避免 JDK 23 Mockito mock 限制 */
    private final MartingaleTpManager noOpTpManager = null;

    private MartingaleFillListener createListener(LayerFillTracker tracker) {
        // tpManager 為 null 時，FillListener 中觸發 TP 更新會 NPE
        // 因此用 test-friendly 的方式：直接用 tracker 驗證 fill，TP 觸發另行測試
        // 這裡改為不依賴 tpManager 的測試路徑：未註冊的 orderId 不會觸發 TP
        return new MartingaleFillListener(tracker, noOpTpManager, null, null);
    }

    @Test
    void accumulatesPartialFillsByOrderId() {
        LayerFillTracker tracker = new LayerFillTracker();
        tracker.registerOrder("1001", "BTCUSDT", 2);

        // 因為 recorded=true 會呼叫 tpManager，我們需要一個可用的 tpManager
        // 直接建構���個 stub
        MartingaleFillListener listener = new MartingaleFillListener(tracker, new NoOpTpManager(), new MartingaleSessionManager(), null);

        listener.onEvent(buildOrderTradeUpdate("BTCUSDT", "1001", "TRADE", "PARTIALLY_FILLED", 0.01, 60000, "T1"));
        listener.onEvent(buildOrderTradeUpdate("BTCUSDT", "1001", "TRADE", "PARTIALLY_FILLED", 0.02, 59000, "T2"));

        double totalQty = tracker.getFilledQty("BTCUSDT", 2);
        double avgPrice = tracker.getWeightedAvgPrice("BTCUSDT", 2);

        assertThat(totalQty).isEqualTo(0.03);
        assertThat(avgPrice).isEqualTo(((0.01 * 60000) + (0.02 * 59000)) / 0.03);
        assertThat(tracker.getLastFillAt("BTCUSDT")).isNotNull();
    }

    @Test
    void duplicateFillEventsAreIdempotent() {
        LayerFillTracker tracker = new LayerFillTracker();
        tracker.registerOrder("2001", "BTCUSDT", 1);

        MartingaleFillListener listener = new MartingaleFillListener(tracker, new NoOpTpManager(), new MartingaleSessionManager(), null);

        // 同一筆 fill 送兩次（WebSocket 重送）
        listener.onEvent(buildOrderTradeUpdate("BTCUSDT", "2001", "TRADE", "PARTIALLY_FILLED", 0.01, 60000, "T100"));
        listener.onEvent(buildOrderTradeUpdate("BTCUSDT", "2001", "TRADE", "PARTIALLY_FILLED", 0.01, 60000, "T100"));

        // 只應計算一次
        assertThat(tracker.getFilledQty("BTCUSDT", 1)).isEqualTo(0.01);
        assertThat(tracker.getWeightedAvgPrice("BTCUSDT", 1)).isEqualTo(60000.0);
    }

    @Test
    void ignoresNonTradeExecutions() {
        LayerFillTracker tracker = new LayerFillTracker();
        tracker.registerOrder("1002", "BTCUSDT", 1);

        // NEW execution type → recorded=false → tpManager 不被呼叫，null 安全
        MartingaleFillListener listener = new MartingaleFillListener(tracker, noOpTpManager, null, null);

        listener.onEvent(buildOrderTradeUpdate("BTCUSDT", "1002", "NEW", "NEW", 0.01, 60000, "T1"));

        assertThat(tracker.getFilledQty("BTCUSDT", 1)).isEqualTo(0.0);
        assertThat(tracker.getLastFillAt("BTCUSDT")).isNull();
    }

    private JsonObject buildOrderTradeUpdate(String symbol,
                                             String orderId,
                                             String executionType,
                                             String status,
                                             double lastQty,
                                             double lastPrice,
                                             String tradeId) {
        JsonObject order = new JsonObject();
        order.addProperty("s", symbol);
        order.addProperty("i", Long.parseLong(orderId));
        order.addProperty("t", Long.parseLong(tradeId.replace("T", "")));
        order.addProperty("x", executionType);
        order.addProperty("X", status);
        order.addProperty("l", lastQty);
        order.addProperty("L", lastPrice);

        JsonObject event = new JsonObject();
        event.addProperty("e", "ORDER_TRADE_UPDATE");
        event.add("o", order);
        return event;
    }

    @Test
    void detectsTpAlgoTriggeredAndCallsHandleTpFilled() {
        LayerFillTracker tracker = new LayerFillTracker();
        MartingaleSessionManager mgr = new MartingaleSessionManager();
        TrackingTpManager tpMgr = new TrackingTpManager();

        // 建立 active session 並設定 TP algoId
        mgr.startSession("ETHUSDT", com.trader.shared.model.TradeSignal.Side.LONG, 3, 3000.0);
        mgr.getActiveSession("ETHUSDT").ifPresent(s -> s.setCurrentTpOrderId("9999"));

        MartingaleFillListener listener = new MartingaleFillListener(tracker, tpMgr, mgr, null);

        // 模擬 ALGO_UPDATE: TP TRIGGERED
        JsonObject algoOrder = new JsonObject();
        algoOrder.addProperty("s", "ETHUSDT");
        algoOrder.addProperty("X", "TRIGGERED");
        algoOrder.addProperty("o", "TAKE_PROFIT_MARKET");
        algoOrder.addProperty("aid", 9999L);

        JsonObject event = new JsonObject();
        event.addProperty("e", "ALGO_UPDATE");
        event.add("o", algoOrder);

        listener.onEvent(event);

        assertThat(tpMgr.tpFilledSymbol).isEqualTo("ETHUSDT");
    }

    @Test
    void fallbackDetectsMarketFilledAsPositionClose() {
        LayerFillTracker tracker = new LayerFillTracker();
        MartingaleSessionManager mgr = new MartingaleSessionManager();
        TrackingTpManager tpMgr = new TrackingTpManager();

        mgr.startSession("BTCUSDT", com.trader.shared.model.TradeSignal.Side.LONG, 5, 60000.0);

        MartingaleFillListener listener = new MartingaleFillListener(tracker, tpMgr, mgr, null);

        // 模擬一個非 ENTRY 的 MARKET FILLED（TP 觸發的市價平倉）
        JsonObject order = new JsonObject();
        order.addProperty("s", "BTCUSDT");
        order.addProperty("i", 5555L);
        order.addProperty("x", "TRADE");
        order.addProperty("X", "FILLED");
        order.addProperty("o", "MARKET");
        order.addProperty("l", 0.1);
        order.addProperty("L", 61000.0);

        JsonObject event = new JsonObject();
        event.addProperty("e", "ORDER_TRADE_UPDATE");
        event.add("o", order);

        listener.onEvent(event);

        assertThat(tpMgr.tpFilledSymbol).isEqualTo("BTCUSDT");
    }

    /** 測試用 stub — 繼承 MartingaleTpManager 但 override 為 no-op */
    private static class NoOpTpManager extends MartingaleTpManager {
        NoOpTpManager() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void updateTakeProfit(String symbol) {
            // no-op for testing
        }

        @Override
        public void handleTpFilled(String symbol) {
            // no-op for testing
        }
    }

    /** 追蹤呼叫的 stub — 記錄 handleTpFilled 被呼叫的 symbol */
    private static class TrackingTpManager extends MartingaleTpManager {
        String tpFilledSymbol = null;

        TrackingTpManager() {
            super(null, null, null, null, null, null, null);
        }

        @Override
        public void updateTakeProfit(String symbol) {
            // no-op
        }

        @Override
        public void handleTpFilled(String symbol) {
            this.tpFilledSymbol = symbol;
        }
    }
}
