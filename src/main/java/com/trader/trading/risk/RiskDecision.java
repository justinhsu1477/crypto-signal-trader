package com.trader.trading.risk;

public record RiskDecision(boolean allowed, String reason, int allowedLayers) {

    public static RiskDecision allow(int allowedLayers) {
        return new RiskDecision(true, null, allowedLayers);
    }

    public static RiskDecision reject(String reason) {
        return new RiskDecision(false, reason, 0);
    }
}
