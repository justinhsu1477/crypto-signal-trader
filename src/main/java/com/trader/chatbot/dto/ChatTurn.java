package com.trader.chatbot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Gemini 多輪對話的單一 turn
 *
 * role: "user" 或 "model"（Gemini 用 "model" 而非 "assistant"）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatTurn {
    private String role;
    private String content;
}
