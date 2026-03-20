package com.trader.trading.service;

import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.model.Order;
import com.trader.trading.strategy.StrategyType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class OrderExecutor {

    private final BinanceFuturesService binanceFuturesService;
    private final MartingaleSessionManager sessionManager;
    private final LayerFillTracker layerFillTracker;

    public OrderExecutor(BinanceFuturesService binanceFuturesService,
                         MartingaleSessionManager sessionManager,
                         LayerFillTracker layerFillTracker) {
        this.binanceFuturesService = binanceFuturesService;
        this.sessionManager = sessionManager;
        this.layerFillTracker = layerFillTracker;
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
        for (Order order : orders) {
            String symbol = resolveSymbol(signal, order);
            TradeSignal.Side side = resolveSide(signal, order);
            if (symbol == null || side == null) {
                results.add(OrderResult.fail("missing-symbol-or-side"));
                continue;
            }

            switch (order.getType()) {
                case ENTRY -> {
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
                    }
                    if (result != null && result.isSuccess()
                            && result.getQuantity() > 0 && result.getPrice() > 0) {
                        layerFillTracker.recordFill(symbol, order, result.getQuantity(), result.getPrice());
                    }
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
                    String closeSide = side == TradeSignal.Side.SHORT ? "BUY" : "SELL";
                    OrderResult result = binanceFuturesService.placeMarketOrder(
                            symbol,
                            closeSide,
                            order.getQuantity()
                    );
                    results.add(result);
                    sessionManager.markExiting(symbol);
                    sessionManager.endSession(symbol);
                    layerFillTracker.clearSymbol(symbol);
                }
                case MOVE_SL -> results.add(OrderResult.fail("unsupported-order-type"));
            }
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
