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

    /**
     * 市價單滑點模擬 — 套用在 market exit (STOP_LOSS / SIGNAL_CLOSE) 的 fill 上。
     *
     * <p>真實 Binance market order 平均 0.03-0.08% slippage（取決於波動 + 倉位大小）。
     * 預設 0.0005 = 0.05%（中性估計）。TP limit exit 不套用。
     *
     * <p>方向：LONG 平倉 (SELL) → fill 低於 expected；SHORT 平倉 (BUY) → fill 高於 expected。
     * 兩者都對持倉方不利，slippage 等於從 PnL 扣 (slippage_pct × notional)。
     */
    private final double marketSlippagePct;

    public PaperTradingConfig(
            @DefaultValue("1000") double notionalUsdt,
            @DefaultValue("10") int leverage,
            @DefaultValue("90000") long monitorIntervalMs,
            @DefaultValue("0.10") double maxPriceDeviationPercent,
            @DefaultValue("0.0005") double marketSlippagePct) {
        this.notionalUsdt = notionalUsdt;
        this.leverage = leverage;
        this.monitorIntervalMs = monitorIntervalMs;
        this.maxPriceDeviationPercent = maxPriceDeviationPercent;
        this.marketSlippagePct = marketSlippagePct;
    }
}
