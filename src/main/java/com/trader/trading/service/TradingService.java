package com.trader.trading.service;

import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.model.Order;
import com.trader.trading.strategy.StrategyFactory;
import com.trader.trading.strategy.StrategyType;
import com.trader.trading.strategy.TradingStrategy;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TradingService {

    private final StrategyFactory strategyFactory;
    private final OrderExecutor orderExecutor;

    public TradingService(StrategyFactory strategyFactory, OrderExecutor orderExecutor) {
        this.strategyFactory = strategyFactory;
        this.orderExecutor = orderExecutor;
    }

    /**
     * Strategy-driven execution with backward-compatible default (SIGNAL).
     */
    public List<OrderResult> execute(TradeSignal signal, StrategyType strategyType) {
        TradingStrategy strategy = strategyFactory.getStrategy(strategyType);
        List<Order> orders = strategy.execute(signal);
        return orderExecutor.execute(signal, strategyType, orders);
    }

    /**
     * Backward-compatible default execution (SIGNAL strategy).
     */
    public List<OrderResult> execute(TradeSignal signal) {
        return execute(signal, StrategyType.SIGNAL);
    }
}
