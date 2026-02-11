package com.trader.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "binance.risk")
public class RiskConfig {

    private double maxPositionUsdt = 100;
    private int maxLeverage = 10;
    private int maxDailyOrders = 10;
    private int defaultLeverage = 5;
    private double defaultSlPercent = 3.0;  // 預設止損百分比
    private double defaultTpPercent = 3.0;  // 預設止盈百分比

    // 以損定倉參數
    private double fixedLossPerTrade = 500.0;  // 單筆固定虧損金額 (USDT)
    private int maxPositions = 1;              // 最大同時持倉數
    private int fixedLeverage = 20;            // 固定槓桿

    /**
     * 允許的交易對清單（設定檔驅動）
     * application.yml 範例:
     *   allowed-symbols:
     *     - BTCUSDT
     *     - ETHUSDT
     */
    private List<String> allowedSymbols = List.of("BTCUSDT");

    /**
     * 檢查交易對是否在白名單中
     */
    public boolean isSymbolAllowed(String symbol) {
        return allowedSymbols != null && allowedSymbols.contains(symbol);
    }
}
