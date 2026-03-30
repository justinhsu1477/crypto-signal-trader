package com.trader.papertrade.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 模擬交易（Paper Trading）配置
 */
@Getter
@ConfigurationProperties(prefix = "paper-trading")
public class PaperTradingConfig {

    /** 模擬倉位名目金額（USDT） */
    private final double notionalUsdt;

    /** 模擬槓桿倍數 */
    private final int leverage;

    /** TP/SL 監控間隔（毫秒） */
    private final long monitorIntervalMs;

    /** 入場價與市價最大允許偏離比例（預設 0.10 = 10%） */
    private final double maxPriceDeviationPercent;

    public PaperTradingConfig(
            @DefaultValue("1000") double notionalUsdt,
            @DefaultValue("10") int leverage,
            @DefaultValue("90000") long monitorIntervalMs,
            @DefaultValue("0.10") double maxPriceDeviationPercent) {
        this.notionalUsdt = notionalUsdt;
        this.leverage = leverage;
        this.monitorIntervalMs = monitorIntervalMs;
        this.maxPriceDeviationPercent = maxPriceDeviationPercent;
    }
}
