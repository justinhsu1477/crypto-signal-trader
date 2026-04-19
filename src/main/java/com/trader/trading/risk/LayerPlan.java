package com.trader.trading.risk;

public record LayerPlan(int layer, double price, double quantity) {
    public double notional() {
        return price * quantity;
    }
}
