package com.trader.advisor.dto;

import lombok.Builder;
import lombok.Data;

/**
 * AI 信號評分結果 DTO
 *
 * 由 SignalScoringService 呼叫 Gemini 後解析而來。
 * 用於廣播跟單通知和 Trade 記錄。
 */
@Data
@Builder
public class SignalScore {

    private int confidence;      // 0-100 信心分數
    private RiskLevel riskLevel; // LOW / MEDIUM / HIGH（enum，顯示文字與 emoji 自帶）
    private String reasoning;    // 繁中簡短理由（≤50字）
    private long latencyMs;      // Gemini 回應耗時（毫秒）

    /** 風險等級的繁中顯示 — 委派給 enum，保留方法名以維持既有 caller 不動。 */
    public String getRiskLevelDisplay() {
        return riskLevel == null ? "" : riskLevel.getDisplay();
    }

    /** 風險等級的 emoji — 委派給 enum。 */
    public String getRiskEmoji() {
        return riskLevel == null ? "❓" : riskLevel.getEmoji();
    }
}
