package com.trader.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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
    private String allowedSymbol = "BTCUSDT";  // 僅允許的交易對
}
