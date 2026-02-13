package com.trader.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@Getter
@ConfigurationProperties(prefix = "binance.risk")
public class RiskConfig {

    private final double maxPositionUsdt;
    private final double maxDailyLossUsdt;
    private final boolean dedupEnabled;
    private final double riskPercent;
    private final int maxPositions;
    private final int fixedLeverage;
    private final List<String> allowedSymbols;

    public RiskConfig(
            @DefaultValue("50000") double maxPositionUsdt,
            @DefaultValue("2000") double maxDailyLossUsdt,
            @DefaultValue("true") boolean dedupEnabled,
            @DefaultValue("0.20") double riskPercent,
            @DefaultValue("1") int maxPositions,
            @DefaultValue("20") int fixedLeverage,
            @DefaultValue("BTCUSDT") List<String> allowedSymbols
    ) {
        this.maxPositionUsdt = maxPositionUsdt;
        this.maxDailyLossUsdt = maxDailyLossUsdt;
        this.dedupEnabled = dedupEnabled;
        this.riskPercent = riskPercent;
        this.maxPositions = maxPositions;
        this.fixedLeverage = fixedLeverage;
        this.allowedSymbols = allowedSymbols != null ? List.copyOf(allowedSymbols) : List.of("BTCUSDT");
    }

    /**
     * 檢查交易對是否在白名單中
     */
    public boolean isSymbolAllowed(String symbol) {
        return allowedSymbols != null && allowedSymbols.contains(symbol);
    }
}
