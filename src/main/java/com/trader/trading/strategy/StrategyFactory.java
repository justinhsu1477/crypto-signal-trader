package com.trader.trading.strategy;

import org.springframework.stereotype.Component;

@Component
public class StrategyFactory {

    private final SignalStrategy signalStrategy;
    private final MartingaleStrategy martingaleStrategy;

    public StrategyFactory(SignalStrategy signalStrategy, MartingaleStrategy martingaleStrategy) {
        this.signalStrategy = signalStrategy;
        this.martingaleStrategy = martingaleStrategy;
    }

    public TradingStrategy getStrategy(StrategyType strategyType) {
        if (strategyType == null) {
            return signalStrategy; // backward-compatible default
        }

        return switch (strategyType) {
            case SIGNAL -> signalStrategy;
            case MARTINGALE -> martingaleStrategy;
        };
    }
}
