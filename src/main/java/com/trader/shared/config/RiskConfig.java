package com.trader.shared.config;

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
    private final int maxDcaPerSymbol;
    private final double dcaRiskMultiplier;
    private final int fixedLeverage;
    private final List<String> allowedSymbols;
    private final String defaultSymbol;

    public RiskConfig(
            @DefaultValue("50000") double maxPositionUsdt,
            @DefaultValue("2000") double maxDailyLossUsdt,
            @DefaultValue("true") boolean dedupEnabled,
            @DefaultValue("0.20") double riskPercent,
            @DefaultValue("3") int maxDcaPerSymbol,
            @DefaultValue("2.0") double dcaRiskMultiplier,
            @DefaultValue("20") int fixedLeverage,
            @DefaultValue("BTCUSDT") List<String> allowedSymbols,
            @DefaultValue("BTCUSDT") String defaultSymbol
    ) {
        this.maxPositionUsdt = maxPositionUsdt;
        this.maxDailyLossUsdt = maxDailyLossUsdt;
        this.dedupEnabled = dedupEnabled;
        this.riskPercent = riskPercent;
        this.maxDcaPerSymbol = maxDcaPerSymbol;
        this.dcaRiskMultiplier = dcaRiskMultiplier;
        this.fixedLeverage = fixedLeverage;
        this.allowedSymbols = allowedSymbols != null ? List.copyOf(allowedSymbols) : List.of("BTCUSDT");
        this.defaultSymbol = defaultSymbol != null ? defaultSymbol : "BTCUSDT";
    }

    /**
     * 檢查交易對是否在白名單中
     */
    public boolean isSymbolAllowed(String symbol) {
        return allowedSymbols != null && allowedSymbols.contains(symbol);
    }
}
