package com.trader.chatbot.dto;

import com.google.gson.JsonObject;
import lombok.Builder;
import lombok.Data;

/**
 * Gemini Function Calling 回傳的結構
 *
 * 當 Gemini 判斷需要呼叫 function 時，回傳 functionCall 而非 text。
 */
@Data
@Builder
public class GeminiFunctionCall {
    private final String functionName;
    private final JsonObject args;
}
