package com.trader.trading.dto.signalsource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * SHADOW 頻道畢業評估結果
 *
 * 對每個啟用的 SHADOW + paperTradingEnabled 頻道，
 * 比較模擬交易績效與畢業門檻，產生逐項 pass/fail 及總體狀態。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShadowGraduationResult {

    /** 畢業狀態：READY（可畢業）/ APPROACHING（接近中）/ NOT_READY（觀察中） */
    public enum GraduationStatus {
        READY, APPROACHING, NOT_READY
    }

    // === 來源資訊 ===
    private Long sourceId;
    private String name;
    private String displayName;

    // === 模擬交易指標 ===
    private long paperTradeCount;
    private double paperWinRate;
    private double paperProfitFactor;
    private int paperMaxConsecutiveLosses;
    private double paperTotalPnl;

    // === 逐項評估結果 ===
    private boolean tradesPass;
    private boolean winRatePass;
    private boolean profitFactorPass;
    private boolean consecutiveLossesPass;

    /** 通過的指標數（0~4） */
    private int passedCriteria;

    /** 總體狀態 */
    private GraduationStatus status;
}
