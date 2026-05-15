package com.trader.advisor.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * AI 信號評分的風險等級。
 *
 * 由 Gemini 回應的 JSON 欄位 {@code riskLevel} 映射而來。LLM 不可靠，
 * 故提供 lenient 解析（大小寫不敏感 + 容錯 fallback）。
 */
@Getter
@RequiredArgsConstructor
public enum RiskLevel {

    LOW("低風險", "✅"),
    MEDIUM("中風險", "⚠️"),
    HIGH("高風險", "🔴");

    private final String display;
    private final String emoji;

    /**
     * 從 Gemini 回傳的字串解析；不認得時用 confidence 推導，永不回傳 null。
     *
     * @param raw        原始字串（可為 null、可含空白、可大小寫不一）
     * @param confidence Gemini 給的 0-100 信心分數，作為 fallback 推導依據
     */
    public static RiskLevel fromGeminiOrInfer(String raw, int confidence) {
        if (raw != null) {
            try {
                return RiskLevel.valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // fall through to confidence-based inference
            }
        }
        if (confidence >= 70) return LOW;
        if (confidence >= 40) return MEDIUM;
        return HIGH;
    }
}
