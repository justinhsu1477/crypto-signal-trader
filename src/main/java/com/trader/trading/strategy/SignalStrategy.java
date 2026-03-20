package com.trader.trading.strategy;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.model.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SignalStrategy implements TradingStrategy {

    @Override
    public List<Order> execute(TradeSignal signal) {
        if (signal == null || signal.getSignalType() == null) {
            return List.of();
        }

        Order.OrderType type = switch (signal.getSignalType()) {
            case ENTRY -> Order.OrderType.ENTRY;
            case CLOSE -> Order.OrderType.CLOSE;
            case MOVE_SL -> Order.OrderType.MOVE_SL;
            case CANCEL, INFO -> null;
        };

        if (type == null) {
            return List.of();
        }

        return List.of(Order.builder()
                .symbol(signal.getSymbol())
                .side(signal.getSide())
                .type(type)
                .price(resolvePrice(signal))
                .quantity(null)
                .layer(null)
                .build());
    }

    private Double resolvePrice(TradeSignal signal) {
        return switch (signal.getSignalType()) {
            case ENTRY -> signal.getEntryPriceLow();
            case MOVE_SL -> signal.getNewStopLoss();
            case CLOSE -> null;
            case CANCEL, INFO -> null;
        };
    }
}
