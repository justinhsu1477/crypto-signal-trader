package com.trader.trading.dto;

import java.util.List;

/**
 * 已解析的交易參數 — 不管來源是全局 RiskConfig 或 per-user UserTradeSettings，
 * BinanceFuturesService / DashboardService 只看這個 record。
 *
 * 每個欄位都已確定有值（不為 null），呼叫端不需要再做 fallback 判斷。
 */
public record EffectiveTradeConfig(
        double riskPercent,
        double maxPositionUsdt,
        double maxDailyLossUsdt,
        int maxDcaPerSymbol,
        double dcaRiskMultiplier,
        int fixedLeverage,
        List<String> allowedSymbols,
        boolean dedupEnabled,
        String defaultSymbol
) {

    /**
     * 檢查交易對是否在白名單中
     */
    public boolean isSymbolAllowed(String symbol) {
        return allowedSymbols != null && allowedSymbols.contains(symbol);
    }
}
