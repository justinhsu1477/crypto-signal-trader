package com.trader.shared.llm;

import com.google.gson.JsonObject;
import com.trader.chatbot.dto.ChatTurn;
import com.trader.chatbot.dto.GeminiResponse;

import java.util.List;
import java.util.Optional;

/**
 * LLM 客戶端抽象介面（Port）
 *
 * 目的：解耦上層商業邏輯與底層 LLM 提供商（Gemini / OpenAI / Anthropic / ...）。
 * 目前唯一實作：{@link com.trader.advisor.service.GeminiService}。
 *
 * 設計原則：
 * - 方法命名 provider-neutral
 * - 回傳 {@link Optional} 代表失敗 graceful degradation，不拋 checked exception
 * - DTO 暫時沿用 {@link GeminiResponse}、{@link ChatTurn}（漸進遷移）
 *
 * 引入動機：
 * - Gemini 2.0 Flash 2026-06-01 deprecated，未來要換模型
 * - 測試端可用 Mock 取代真實 HTTP call
 * - 將來加 OpenAI / Anthropic impl 只需實作本介面
 *
 * 注意：所有方法都應是「無副作用」的純 LLM 呼叫 —
 * session / history 管理、rate limiting、context gathering 由呼叫端處理。
 */
public interface LlmClient {

    /**
     * 單輪 generation（system prompt + user content）
     * 使用預設 model / tokens / temperature。
     */
    Optional<String> generateContent(String systemPrompt, String userContent);

    /**
     * 多輪 generation（含對話歷史）
     *
     * @param history     對話歷史（可為空）
     * @param userMessage 當前使用者訊息
     * @param maxTokens   最大回覆 token 數
     * @param temperature 溫度（0.0-1.0）
     * @param model       模型名稱；null/blank 時用 default
     */
    Optional<String> generateContentWithHistory(String systemPrompt,
                                                List<ChatTurn> history,
                                                String userMessage,
                                                int maxTokens,
                                                double temperature,
                                                String model);

    /**
     * 多輪 generation + Function/Tool Calling
     *
     * @param tools 工具 schema（functionDeclarations JSON）
     * @return 可能為 text 或 functionCall，由呼叫端檢查 {@code hasFunctionCall()}
     */
    Optional<GeminiResponse> generateContentWithTools(String systemPrompt,
                                                      List<ChatTurn> history,
                                                      String userMessage,
                                                      int maxTokens,
                                                      double temperature,
                                                      String model,
                                                      JsonObject tools);

    /**
     * 送 tool 執行結果回 LLM（單輪結束型）
     * 預期 LLM 直接給文字回覆。
     */
    Optional<String> sendFunctionResult(String systemPrompt,
                                        List<ChatTurn> history,
                                        String userMessage,
                                        String functionName,
                                        JsonObject functionCallArgs,
                                        String functionResult,
                                        int maxTokens,
                                        double temperature,
                                        String model,
                                        JsonObject tools);

    /**
     * 送 tool 執行結果回 LLM（Multi-tool Chaining 用）
     * LLM 可能再次 functionCall，呼叫端需判斷。
     */
    Optional<GeminiResponse> sendFunctionResultForChaining(String systemPrompt,
                                                          List<ChatTurn> history,
                                                          String userMessage,
                                                          String functionName,
                                                          JsonObject functionCallArgs,
                                                          String functionResult,
                                                          int maxTokens,
                                                          double temperature,
                                                          String model,
                                                          JsonObject tools);

    /**
     * 將文字轉為 embedding 向量（用於 RAG / 知識庫）
     */
    Optional<float[]> getEmbedding(String text);

    /**
     * 批次 embedding — 任一失敗則整批 empty
     */
    Optional<List<float[]>> getBatchEmbeddings(List<String> texts);
}
