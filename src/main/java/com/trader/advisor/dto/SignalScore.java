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
    private String riskLevel;    // LOW / MEDIUM / HIGH
    private String reasoning;    // 繁中簡短理由（≤50字）
    private long latencyMs;      // Gemini 回應耗時（毫秒）

    /**
     * 風險等級的繁中顯示
     */
    public String getRiskLevelDisplay() {
        return switch (riskLevel) {
            case "LOW" -> "低風險";
            case "MEDIUM" -> "中風險";
            case "HIGH" -> "高風險";
            default -> riskLevel;
        };
    }

    /**
     * 風險等級的 emoji
     */
    public String getRiskEmoji() {
        return switch (riskLevel) {
            case "LOW" -> "✅";
            case "MEDIUM" -> "⚠️";
            case "HIGH" -> "🔴";
            default -> "❓";
        };
    }
}
