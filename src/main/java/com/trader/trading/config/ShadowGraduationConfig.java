package com.trader.trading.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * SHADOW 畢業門檻配置
 * 對應 application.yml 中的 shadow-graduation 區塊
 *
 * 用於評估 SHADOW 頻道的模擬交易績效是否達到可轉正（AUTO）的標準。
 * 評估四項指標：最低筆數、最低勝率、最低盈虧比、最大連敗上限。
 */
@Configuration
@ConfigurationProperties(prefix = "shadow-graduation")
@Getter
@Setter
public class ShadowGraduationConfig {

    /** 最低模擬交易筆數（統計顯著性門檻） */
    private int minTrades = 30;

    /** 最低勝率（百分比） */
    private double minWinRate = 55.0;

    /** 最低盈虧比（Profit Factor = grossWins / grossLosses） */
    private double minProfitFactor = 1.3;

    /** 最大連敗上限（超過此值視為不合格） */
    private int maxConsecutiveLosses = 5;
}
