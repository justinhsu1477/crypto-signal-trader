package com.trader.trading.model;

import com.trader.shared.model.TradeSignal;

public record PositionInfo(
        String symbol,
        TradeSignal.Side side,
        double quantity,
        double avgEntryPrice,
        double unrealizedPnl
) {
    public boolean isOpen() {
        return quantity > 0;
    }
}
