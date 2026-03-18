package com.trader.chatbot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

/**
 * AI 客服回覆結果 — 包含回覆文字 + assistant 對話 ID（用於 feedback 追蹤）
 */
@Data
@Builder
@AllArgsConstructor
public class ChatbotResponse {

    private final String text;
    private final Long conversationId;
}
