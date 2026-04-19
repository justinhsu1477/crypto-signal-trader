package com.trader.trading.strategy;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.model.Order;

import java.util.List;

public interface TradingStrategy {
    List<Order> execute(TradeSignal signal);
}
