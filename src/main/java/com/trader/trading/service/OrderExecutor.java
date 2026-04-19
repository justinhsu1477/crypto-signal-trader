package com.trader.trading.service;

import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.model.Order;
import com.trader.trading.service.martingale.MartingaleNotifier;
import com.trader.trading.service.martingale.MartingaleStateStore;
import com.trader.trading.strategy.StrategyType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class OrderExecutor {

    private final BinanceFuturesService binanceFuturesService;
    private final MartingaleSessionManager sessionManager;
    private final LayerFillTracker layerFillTracker;
    private final MartingaleNotifier notifier;
    private final MartingaleStateStore stateStore;

    public OrderExecutor(BinanceFuturesService binanceFuturesService,
                         MartingaleSessionManager sessionManager,
                         LayerFillTracker layerFillTracker,
                         MartingaleNotifier notifier,
                         MartingaleStateStore stateStore) {
        this.binanceFuturesService = binanceFuturesService;
        this.sessionManager = sessionManager;
        this.layerFillTracker = layerFillTracker;
        this.notifier = notifier;
        this.stateStore = stateStore;
    }

    public List<OrderResult> execute(TradeSignal signal, StrategyType strategyType, List<Order> orders) {
        StrategyType type = (strategyType != null) ? strategyType : StrategyType.SIGNAL;
        return switch (type) {
            case SIGNAL -> executeSignalFlow(signal);
            case MARTINGALE -> executeMartingaleOrders(signal, orders);
        };
    }

    private List<OrderResult> executeSignalFlow(TradeSignal signal) {
        if (signal == null || signal.getSignalType() == null) {
            return List.of(OrderResult.fail("signal-or-type-null"));
        }

        return switch (signal.getSignalType()) {
            case ENTRY -> binanceFuturesService.executeSignal(signal);
            case CLOSE -> binanceFuturesService.executeClose(signal);
            case MOVE_SL -> binanceFuturesService.executeMoveSL(signal);
            case CANCEL, INFO -> List.of(OrderResult.fail("unsupported-signal-type"));
        };
    }

    private List<OrderResult> executeMartingaleOrders(TradeSignal signal, List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of(OrderResult.fail("no-orders"));
        }

        List<OrderResult> results = new ArrayList<>(orders.size());
        int entryTotal = 0;
        int entrySuccess = 0;
        String trackedSymbol = null;

        for (Order order : orders) {
            String symbol = resolveSymbol(signal, order);
            TradeSignal.Side side = resolveSide(signal, order);
            if (symbol == null || side == null) {
                results.add(OrderResult.fail("missing-symbol-or-side"));
                continue;
            }

            // 防禦 boxed Double NPE：ENTRY/TP 需要 qty+price，CLOSE 只需 qty
            if (order.getType() != Order.OrderType.CLOSE) {
                if (order.getQuantity() == null || order.getPrice() == null) {
                    log.warn("Order qty 或 price 為 null，跳過: symbol={} type={}", symbol, order.getType());
                    results.add(OrderResult.fail("null-qty-or-price"));
                    continue;
                }
            } else if (order.getQuantity() == null) {
                log.warn("CLOSE order qty 為 null，跳過: symbol={}", symbol);
                results.add(OrderResult.fail("null-qty"));
                continue;
            }

            switch (order.getType()) {
                case ENTRY -> {
                    entryTotal++;
                    trackedSymbol = symbol;
                    // minQty ���查：數量低於交易所最小下單量則跳過此層
                    double minQty = binanceFuturesService.getMinQty(symbol);
                    if (minQty > 0 && order.getQuantity() < minQty) {
                        log.warn("Martingale ENTRY 數量低於 minQty，跳過: symbol={} layer={} qty={} minQty={}",
                                symbol, order.getLayer(), order.getQuantity(), minQty);
                        results.add(OrderResult.fail("qty-below-minQty"));
                        break;
                    }
                    String entrySide = side == TradeSignal.Side.SHORT ? "SELL" : "BUY";
                    OrderResult result = binanceFuturesService.placeLimitOrder(
                            symbol,
                            entrySide,
                            order.getPrice(),
                            order.getQuantity()
                    );
                    results.add(result);
                    if (result != null && result.isSuccess() && result.getOrderId() != null) {
                        layerFillTracker.registerOrder(result.getOrderId(), symbol, order.getLayer());
                        entrySuccess++;
                    }
                    // 不在此處 eager 記錄 fill：LIMIT 單不一定立刻成交，
                    // 實際成交由 WebSocket MartingaleFillListener 處理，避免雙倍計算
                }
                case TAKE_PROFIT -> {
                    String closeSide = side == TradeSignal.Side.SHORT ? "BUY" : "SELL";
                    OrderResult result = binanceFuturesService.placeTakeProfit(
                            symbol,
                            closeSide,
                            order.getPrice(),
                            order.getQuantity()
                    );
                    results.add(result);
                    if (result != null && result.isSuccess() && result.getOrderId() != null) {
                        sessionManager.getActiveSession(symbol)
                                .ifPresent(s -> s.setCurrentTpOrderId(result.getOrderId()));
                    }
                }
                case CLOSE -> {
                    binanceFuturesService.cancelAllOrders(symbol);
                    sessionManager.markExiting(symbol);
                    String closeSide = side == TradeSignal.Side.SHORT ? "BUY" : "SELL";
                    OrderResult result = binanceFuturesService.placeMarketOrder(
                            symbol,
                            closeSide,
                            order.getQuantity()
                    );
                    results.add(result);
                    if (result != null && result.isSuccess()) {
                        // 驗證倉位是否完全平倉
                        double remaining = Math.abs(binanceFuturesService.getCurrentPositionAmount(symbol));
                        if (remaining > 0) {
                            log.warn("CLOSE 部分成交，剩餘倉位重試: symbol={} remaining={}", symbol, remaining);
                            binanceFuturesService.placeMarketOrder(symbol, closeSide, remaining);
                            remaining = Math.abs(binanceFuturesService.getCurrentPositionAmount(symbol));
                        }
                        if (remaining > 0) {
                            log.error("CLOSE 重試後仍有剩餘倉位（幽靈倉位），保留 EXITING: symbol={} remaining={}", symbol, remaining);
                            notifier.notifyGhostPosition(symbol, remaining);
                        } else {
                            stateStore.removeSession(symbol);
                            sessionManager.endSession(symbol);
                            layerFillTracker.clearSymbol(symbol);
                        }
                    } else {
                        // 市價平倉失敗：保留 EXITING 狀態，由 CleanupTask 重試
                        log.error("Martingale CLOSE 市價平倉失敗，保留 EXITING 狀態: symbol={} err={}",
                                symbol, result != null ? result.getErrorMessage() : "null");
                    }
                }
                case MOVE_SL -> results.add(OrderResult.fail("unsupported-order-type"));
            }
        }

        // 送單結果檢查：全部 ENTRY 失敗 → 清理 session
        if (entryTotal > 0 && entrySuccess == 0 && trackedSymbol != null) {
            log.error("Martingale 全部 ENTRY 送單失敗，清理 session: symbol={}", trackedSymbol);
            sessionManager.markExiting(trackedSymbol);
            stateStore.removeSession(trackedSymbol);
            sessionManager.endSession(trackedSymbol);
            layerFillTracker.clearSymbol(trackedSymbol);
            notifier.notifyAllEntryFailed(trackedSymbol);
        } else if (entryTotal > 0 && entrySuccess < entryTotal) {
            log.warn("Martingale 部分 ENTRY 送單失敗: symbol={} success={}/{} — TP 將由動態更新修正",
                    trackedSymbol, entrySuccess, entryTotal);
        }

        return results;
    }

    private String resolveSymbol(TradeSignal signal, Order order) {
        if (order != null && order.getSymbol() != null) {
            return order.getSymbol();
        }
        return signal != null ? signal.getSymbol() : null;
    }

    private TradeSignal.Side resolveSide(TradeSignal signal, Order order) {
        if (order != null && order.getSide() != null) {
            return order.getSide();
        }
        return signal != null ? signal.getSide() : null;
    }
}
