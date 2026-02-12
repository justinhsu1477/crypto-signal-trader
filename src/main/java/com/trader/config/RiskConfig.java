package com.trader.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@Getter
@ConfigurationProperties(prefix = "binance.risk")
public class RiskConfig {

    private final double maxPositionUsdt;
    private final int maxLeverage;
    private final int maxDailyOrders;
    private final int defaultLeverage;
    private final double defaultSlPercent;
    private final double defaultTpPercent;
    private final boolean dedupEnabled;
    private final double fixedLossPerTrade;
    private final int maxPositions;
    private final int fixedLeverage;
    private final List<String> allowedSymbols;

    public RiskConfig(
            @DefaultValue("100") double maxPositionUsdt,
            @DefaultValue("10") int maxLeverage,
            @DefaultValue("10") int maxDailyOrders,
            @DefaultValue("5") int defaultLeverage,
            @DefaultValue("3.0") double defaultSlPercent,
            @DefaultValue("3.0") double defaultTpPercent,
            @DefaultValue("true") boolean dedupEnabled,
            @DefaultValue("500.0") double fixedLossPerTrade,
            @DefaultValue("1") int maxPositions,
            @DefaultValue("20") int fixedLeverage,
            @DefaultValue("BTCUSDT") List<String> allowedSymbols
    ) {
        this.maxPositionUsdt = maxPositionUsdt;
        this.maxLeverage = maxLeverage;
        this.maxDailyOrders = maxDailyOrders;
        this.defaultLeverage = defaultLeverage;
        this.defaultSlPercent = defaultSlPercent;
        this.defaultTpPercent = defaultTpPercent;
        this.dedupEnabled = dedupEnabled;
        this.fixedLossPerTrade = fixedLossPerTrade;
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
