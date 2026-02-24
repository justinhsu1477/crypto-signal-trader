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
        double dailyLossPercent,
        double maxPositionPercent,
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

    /**
     * 計算有效每日虧損上限 = min(SOD餘額 × dailyLossPercent, 絕對上限)
     * dailyLossPercent = 0 → 退回純絕對值（向後相容）
     * maxDailyLossUsdt = 0 → 純百分比模式
     */
    public double effectiveDailyLossLimit(double sodBalance) {
        if (dailyLossPercent > 0 && sodBalance > 0) {
            double dynamic = sodBalance * dailyLossPercent;
            return maxDailyLossUsdt > 0 ? Math.min(dynamic, maxDailyLossUsdt) : dynamic;
        }
        return maxDailyLossUsdt;
    }

    /**
     * 計算有效單筆倉位名目上限 = min(即時餘額 × maxPositionPercent, 絕對上限)
     * maxPositionPercent = 0 → 退回純絕對值（向後相容）
     * maxPositionUsdt = 0 → 純百分比模式
     */
    public double effectiveMaxPosition(double currentBalance) {
        if (maxPositionPercent > 0 && currentBalance > 0) {
            double dynamic = currentBalance * maxPositionPercent;
            return maxPositionUsdt > 0 ? Math.min(dynamic, maxPositionUsdt) : dynamic;
        }
        return maxPositionUsdt;
    }
}
