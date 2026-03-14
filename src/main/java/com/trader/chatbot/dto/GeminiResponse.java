package com.trader.chatbot.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Optional;

/**
 * Gemini API 回覆的統一結構
 *
 * 支援兩種回覆模式：
 * 1. 純文字回覆（text）
 * 2. Function Call 請求（functionCall）
 */
@Data
@Builder
public class GeminiResponse {
    /** 純文字回覆 */
    private final String text;
    /** Function Call 請求（當 Gemini 判斷需要呼叫工具時） */
    private final GeminiFunctionCall functionCall;
    /** 原始回覆 JSON（用於 function call 後的第二輪對話） */
    private final String rawResponseBody;

    public boolean hasFunctionCall() {
        return functionCall != null;
    }

    public boolean hasText() {
        return text != null && !text.isBlank();
    }

    public Optional<String> getText() {
        return Optional.ofNullable(text).filter(t -> !t.isBlank());
    }
}
