package com.trader.shared.llm;

import com.google.gson.JsonObject;
import com.trader.chatbot.dto.ChatTurn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SpringAiLlmClient 單元測試。
 *
 * <p>用 fake {@link ChatModel} 做底層，不 mock ChatClient fluent API（會痛苦）。
 * 真實的 ChatClient build 起來，呼叫 fake ChatModel 回 canned response。
 *
 * <p>沒測網路、沒測 OpenAI auth — 那些是 Spring AI auto-config 本身的責任。
 */
class SpringAiLlmClientTest {

    private AtomicReference<org.springframework.ai.chat.prompt.Prompt> lastPrompt;
    private SpringAiLlmClient client;

    @BeforeEach
    void setUp() {
        lastPrompt = new AtomicReference<>();
        ChatModel fakeModel = prompt -> {
            lastPrompt.set(prompt);
            Generation g = new Generation(new AssistantMessage("canned-response"),
                    ChatGenerationMetadata.NULL);
            return new ChatResponse(List.of(g));
        };
        client = new SpringAiLlmClient(ChatClient.builder(fakeModel));
    }

    // ─── generateContent ─────────────────────────────────────────────────────

    @Test
    void generateContent_returns_assistant_text() {
        Optional<String> result = client.generateContent("system rule", "user question");

        assertThat(result).contains("canned-response");
        // 驗證 system + user prompt 都被送過去
        String allText = lastPrompt.get().getInstructions().stream()
                .map(m -> m.getText())
                .reduce("", (a, b) -> a + "|" + b);
        assertThat(allText).contains("system rule").contains("user question");
    }

    @Test
    void generateContent_null_systemPrompt_uses_empty_string() {
        // 不該 NPE
        Optional<String> result = client.generateContent(null, "user question");
        assertThat(result).contains("canned-response");
    }

    @Test
    void generateContent_underlying_exception_returns_empty() {
        ChatModel throwingModel = prompt -> { throw new RuntimeException("simulated 500"); };
        SpringAiLlmClient badClient = new SpringAiLlmClient(ChatClient.builder(throwingModel));

        Optional<String> result = badClient.generateContent("s", "u");

        assertThat(result).isEmpty();   // graceful degradation
    }

    @Test
    void generateContent_blank_response_returns_empty() {
        ChatModel blankModel = prompt -> {
            Generation g = new Generation(new AssistantMessage("   "), ChatGenerationMetadata.NULL);
            return new ChatResponse(List.of(g));
        };
        SpringAiLlmClient blankClient = new SpringAiLlmClient(ChatClient.builder(blankModel));

        assertThat(blankClient.generateContent("s", "u")).isEmpty();
    }

    // ─── generateContentWithHistory ──────────────────────────────────────────

    @Test
    void generateContentWithHistory_maps_chatTurn_to_spring_messages() {
        List<ChatTurn> history = List.of(
                new ChatTurn("user", "first question"),
                new ChatTurn("model", "first answer"),    // ChatTurn 用 "model" → 要轉 AssistantMessage
                new ChatTurn("user", "follow-up")
        );

        Optional<String> result = client.generateContentWithHistory(
                "system", history, "current message", 1024, 0.5, "gemini-2.5-flash");

        assertThat(result).contains("canned-response");
        // history 訊息應該都進到 prompt
        String allText = lastPrompt.get().getInstructions().stream()
                .map(m -> m.getText())
                .reduce("", (a, b) -> a + "|" + b);
        assertThat(allText)
                .contains("system")
                .contains("first question")
                .contains("first answer")
                .contains("follow-up")
                .contains("current message");
    }

    @Test
    void generateContentWithHistory_empty_history_still_works() {
        Optional<String> result = client.generateContentWithHistory(
                "sys", List.of(), "user msg", 512, 0.3, null);

        assertThat(result).contains("canned-response");
    }

    @Test
    void generateContentWithHistory_underlying_exception_returns_empty() {
        ChatModel throwing = prompt -> { throw new RuntimeException("boom"); };
        SpringAiLlmClient bad = new SpringAiLlmClient(ChatClient.builder(throwing));

        Optional<String> result = bad.generateContentWithHistory(
                "s", List.of(), "u", 100, 0.5, "m");

        assertThat(result).isEmpty();
    }

    // ─── 未支援的方法應該明確拒絕（不要靜默回 empty） ─────────────────────────

    @Test
    void generateContentWithTools_unsupported() {
        assertThatThrownBy(() -> client.generateContentWithTools(
                "s", List.of(), "u", 100, 0.5, "m", new JsonObject()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Phase 3");
    }

    @Test
    void sendFunctionResult_unsupported() {
        assertThatThrownBy(() -> client.sendFunctionResult(
                "s", List.of(), "u", "fn", new JsonObject(), "result", 100, 0.5, "m", new JsonObject()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void sendFunctionResultForChaining_unsupported() {
        assertThatThrownBy(() -> client.sendFunctionResultForChaining(
                "s", List.of(), "u", "fn", new JsonObject(), "result", 100, 0.5, "m", new JsonObject()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void getEmbedding_unsupported() {
        assertThatThrownBy(() -> client.getEmbedding("text"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("Phase 5");
    }

    @Test
    void getBatchEmbeddings_unsupported() {
        assertThatThrownBy(() -> client.getBatchEmbeddings(List.of("a", "b")))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
