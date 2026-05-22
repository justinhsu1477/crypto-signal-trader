package com.trader.shared.llm;

import com.google.gson.JsonObject;
import com.trader.chatbot.dto.ChatTurn;
import com.trader.chatbot.dto.GeminiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Spring AI 實作的 {@link LlmClient}（Phase 2 接線）。
 *
 * <p>對齊 lndata/gen-bi 的 ChatClient 慣例。透過 Spring AI auto-config 取得的
 * {@code ChatClient.Builder} 建立統一入口，將來換 provider（Anthropic / OpenAI / Ollama）
 * 只要改 application.yml 的 starter 設定，本類不動。
 *
 * <h2>支援範圍（Phase 2）</h2>
 * <ul>
 *   <li>✅ {@link #generateContent} — 簡單單輪</li>
 *   <li>✅ {@link #generateContentWithHistory} — 多輪對話</li>
 *   <li>❌ {@link #generateContentWithTools} — Phase 3 用 {@code @Tool} annotation 重做</li>
 *   <li>❌ {@link #sendFunctionResult} / {@link #sendFunctionResultForChaining} — Phase 3</li>
 *   <li>❌ {@link #getEmbedding} / {@link #getBatchEmbeddings} — Phase 5 加 embedding starter</li>
 * </ul>
 *
 * <p>不支援的方法拋 {@link UnsupportedOperationException}；正常運行路徑由 {@link RoutingLlmClient}
 * 自動 fallback 到 {@link com.trader.advisor.service.GeminiService}，呼叫端不會踩到。
 *
 * @see RoutingLlmClient
 */
@Slf4j
@Service("springAiLlmClient")
public class SpringAiLlmClient implements LlmClient {

    private final ChatClient chatClient;

    public SpringAiLlmClient(ChatClient.Builder chatClientBuilder) {
        // 不在這層設 defaultSystem — system prompt 由 caller 每次傳入，
        // 避免「全 chatbot 共用一個 system prompt」這種隱性耦合。
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public Optional<String> generateContent(String systemPrompt, String userContent) {
        try {
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt();
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                spec = spec.system(systemPrompt);
            }
            String response = spec
                    .user(userContent != null ? userContent : " ")  // Spring AI 拒絕空字串
                    .call()
                    .content();
            return Optional.ofNullable(response).filter(s -> !s.isBlank());
        } catch (Exception e) {
            log.warn("Spring AI generateContent failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> generateContentWithHistory(String systemPrompt,
                                                       List<ChatTurn> history,
                                                       String userMessage,
                                                       int maxTokens,
                                                       double temperature,
                                                       String model) {
        try {
            List<Message> historyMessages = toSpringAiMessages(history);

            OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                    .temperature(temperature)
                    .maxTokens(maxTokens);
            if (model != null && !model.isBlank()) {
                optionsBuilder.model(model);
            }

            ChatClient.ChatClientRequestSpec spec = chatClient.prompt()
                    .options(optionsBuilder.build());

            if (systemPrompt != null && !systemPrompt.isBlank()) {
                spec = spec.system(systemPrompt);
            }
            if (!historyMessages.isEmpty()) {
                spec = spec.messages(historyMessages);
            }

            String response = spec
                    .user(userMessage != null ? userMessage : " ")  // Spring AI 拒絕空字串
                    .call()
                    .content();

            return Optional.ofNullable(response).filter(s -> !s.isBlank());
        } catch (Exception e) {
            log.warn("Spring AI generateContentWithHistory failed (model={}): {}", model, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public Optional<GeminiResponse> generateContentWithTools(String systemPrompt,
                                                             List<ChatTurn> history,
                                                             String userMessage,
                                                             int maxTokens,
                                                             double temperature,
                                                             String model,
                                                             JsonObject tools) {
        throw new UnsupportedOperationException(
                "Spring AI function calling 尚未接通（Phase 3）。"
                        + "如果你看到這個錯誤，表示 RoutingLlmClient 沒有把此呼叫導向 GeminiService。");
    }

    @Override
    public Optional<String> sendFunctionResult(String systemPrompt,
                                               List<ChatTurn> history,
                                               String userMessage,
                                               String functionName,
                                               JsonObject functionCallArgs,
                                               String functionResult,
                                               int maxTokens,
                                               double temperature,
                                               String model,
                                               JsonObject tools) {
        throw new UnsupportedOperationException(
                "Spring AI sendFunctionResult 尚未接通（Phase 3）。");
    }

    @Override
    public Optional<GeminiResponse> sendFunctionResultForChaining(String systemPrompt,
                                                                  List<ChatTurn> history,
                                                                  String userMessage,
                                                                  String functionName,
                                                                  JsonObject functionCallArgs,
                                                                  String functionResult,
                                                                  int maxTokens,
                                                                  double temperature,
                                                                  String model,
                                                                  JsonObject tools) {
        throw new UnsupportedOperationException(
                "Spring AI sendFunctionResultForChaining 尚未接通（Phase 3）。");
    }

    @Override
    public Optional<float[]> getEmbedding(String text) {
        throw new UnsupportedOperationException(
                "Spring AI embedding 尚未接通（Phase 5：加 spring-ai-starter-model-openai-embedding）。");
    }

    @Override
    public Optional<List<float[]>> getBatchEmbeddings(List<String> texts) {
        throw new UnsupportedOperationException(
                "Spring AI batch embedding 尚未接通（Phase 5）。");
    }

    /**
     * ChatTurn → Spring AI {@link Message}。
     *
     * <p>ChatTurn.role 用 Gemini 慣例（"user" / "model" / "system"）；
     * Spring AI 用 "assistant" 取代 "model"，所以這裡要轉。
     */
    private static List<Message> toSpringAiMessages(List<ChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        List<Message> messages = new ArrayList<>(history.size());
        for (ChatTurn turn : history) {
            if (turn == null || turn.getContent() == null) continue;
            String role = turn.getRole();
            messages.add(switch (role == null ? "user" : role.toLowerCase()) {
                case "assistant", "model" -> new AssistantMessage(turn.getContent());
                case "system" -> new SystemMessage(turn.getContent());
                default -> new UserMessage(turn.getContent());
            });
        }
        return messages;
    }
}
