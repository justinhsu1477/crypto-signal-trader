package com.trader.trading.service;

import com.trader.trading.model.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

@Component
public class LayerFillTracker {

    private static class FillState {
        double totalQty;
        double weightedNotional;
    }

    private static class OrderRef {
        final String symbol;
        final Integer layer;

        OrderRef(String symbol, Integer layer) {
            this.symbol = symbol;
            this.layer = layer;
        }
    }

    private final ConcurrentHashMap<String, FillState> fills = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OrderRef> orderRefs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Instant> lastFillAt = new ConcurrentHashMap<>();
    /** 冪等去重：key = "symbol:orderId:tradeId"，防止 WebSocket 重送造成 double count */
    private final Set<String> processedFills = ConcurrentHashMap.newKeySet();

    public void registerOrder(String orderId, String symbol, Integer layer) {
        if (orderId == null || orderId.isBlank() || symbol == null || symbol.isBlank()) {
            return;
        }
        orderRefs.put(orderId, new OrderRef(symbol, layer));
    }

    public void recordFill(String symbol, Order order, double filledQty, double avgPrice) {
        if (symbol == null || order == null || filledQty <= 0 || avgPrice <= 0) {
            return;
        }
        recordFill(symbol, order.getLayer(), filledQty, avgPrice);
    }

    public boolean recordFillByOrderId(String orderId, double filledQty, double avgPrice, String tradeId) {
        if (orderId == null || filledQty <= 0 || avgPrice <= 0) {
            return false;
        }
        OrderRef ref = orderRefs.get(orderId);
        if (ref == null) {
            return false;
        }
        // 冪等性：同一 (orderId, tradeId) 只處理一次，防止 WebSocket 重送
        if (tradeId != null && !tradeId.isBlank()) {
            String dedupeKey = ref.symbol + ":" + orderId + ":" + tradeId;
            if (!processedFills.add(dedupeKey)) {
                return false;
            }
        }
        recordFill(ref.symbol, ref.layer, filledQty, avgPrice);
        return true;
    }

    public double getFilledQty(String symbol, Integer layer) {
        FillState state = fills.get(buildKey(symbol, layer));
        return state == null ? 0.0 : state.totalQty;
    }

    public double getWeightedAvgPrice(String symbol, Integer layer) {
        FillState state = fills.get(buildKey(symbol, layer));
        if (state == null || state.totalQty <= 0) {
            return 0.0;
        }
        return state.weightedNotional / state.totalQty;
    }

    public AggregatedFill getAggregatedFill(String symbol) {
        double totalQty = 0.0;
        double totalNotional = 0.0;
        for (Map.Entry<String, FillState> entry : fills.entrySet()) {
            if (!entry.getKey().startsWith(symbol + ":")) {
                continue;
            }
            FillState state = entry.getValue();
            synchronized (state) {
                totalQty += state.totalQty;
                totalNotional += state.weightedNotional;
            }
        }
        double avgPrice = totalQty > 0 ? totalNotional / totalQty : 0.0;
        return new AggregatedFill(totalQty, avgPrice);
    }

    public Instant getLastFillAt(String symbol) {
        return symbol == null ? null : lastFillAt.get(symbol);
    }

    /**
     * 直接寫入聚合成交資料（用於從 Redis 恢復狀態）。
     * 使用 layer=0 作為聚合 key。
     */
    public void recordFillDirect(String symbol, double totalQty, double avgPrice) {
        if (symbol == null || totalQty <= 0 || avgPrice <= 0) return;
        recordFill(symbol, 0, totalQty, avgPrice);
    }

    public void clearSymbol(String symbol) {
        if (symbol == null) {
            return;
        }
        fills.keySet().removeIf(k -> k.startsWith(symbol + ":"));
        orderRefs.entrySet().removeIf(e -> symbol.equals(e.getValue().symbol));
        processedFills.removeIf(k -> k.startsWith(symbol + ":"));
        lastFillAt.remove(symbol);
    }

    public void clearOrder(String orderId) {
        if (orderId == null) {
            return;
        }
        orderRefs.remove(orderId);
    }

    private void recordFill(String symbol, Integer layer, double filledQty, double avgPrice) {
        if (symbol == null || filledQty <= 0 || avgPrice <= 0) {
            return;
        }
        String key = buildKey(symbol, layer);
        FillState state = fills.computeIfAbsent(key, k -> new FillState());
        synchronized (state) {
            state.totalQty += filledQty;
            state.weightedNotional += filledQty * avgPrice;
        }
        lastFillAt.put(symbol, Instant.now());
    }

    private String buildKey(String symbol, Integer layer) {
        return symbol + ":" + (layer != null ? layer : 0);
    }

    public record AggregatedFill(double totalQty, double avgPrice) {}
}
