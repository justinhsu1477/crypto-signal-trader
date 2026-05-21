package com.trader.shared.llm;

import com.google.gson.JsonObject;
import com.trader.chatbot.dto.ChatTurn;
import com.trader.chatbot.dto.GeminiResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * RoutingLlmClient 路由決策的單元測試。
 *
 * <p>不測 Gemini / Spring AI 實作本身，只驗證「哪個方法走哪邊」的路由邏輯。
 */
class RoutingLlmClientTest {

    private final LlmClient gemini = mock(LlmClient.class);
    private final LlmClient springAi = mock(LlmClient.class);

    // ─── provider=gemini (預設) — 7 個方法全走 Gemini ─────────────────────────

    @Test
    void provider_gemini_routes_generateContent_to_gemini() {
        RoutingLlmClient router = new RoutingLlmClient(gemini, springAi, "gemini");
        when(gemini.generateContent("sys", "user")).thenReturn(Optional.of("ok"));

        Optional<String> result = router.generateContent("sys", "user");

        assertThat(result).contains("ok");
        verify(gemini).generateContent("sys", "user");
        verifyNoInteractions(springAi);
    }

    @Test
    void provider_gemini_routes_generateContentWithHistory_to_gemini() {
        RoutingLlmClient router = new RoutingLlmClient(gemini, springAi, "gemini");
        List<ChatTurn> history = List.of(new ChatTurn("user", "hi"));
        when(gemini.generateContentWithHistory("sys", history, "msg", 100, 0.5, "m"))
                .thenReturn(Optional.of("ok"));

        Optional<String> result = router.generateContentWithHistory("sys", history, "msg", 100, 0.5, "m");

        assertThat(result).contains("ok");
        verify(gemini).generateContentWithHistory("sys", history, "msg", 100, 0.5, "m");
        verifyNoInteractions(springAi);
    }

    // ─── provider=spring-ai — 簡單方法走 Spring AI ───────────────────────────

    @Test
    void provider_springAi_routes_generateContent_to_springAi() {
        RoutingLlmClient router = new RoutingLlmClient(gemini, springAi, "spring-ai");
        when(springAi.generateContent("sys", "user")).thenReturn(Optional.of("ok-springai"));

        Optional<String> result = router.generateContent("sys", "user");

        assertThat(result).contains("ok-springai");
        verify(springAi).generateContent("sys", "user");
        verify(gemini, never()).generateContent(any(), any());
    }

    @Test
    void provider_springAi_routes_generateContentWithHistory_to_springAi() {
        RoutingLlmClient router = new RoutingLlmClient(gemini, springAi, "spring-ai");
        List<ChatTurn> history = List.of(new ChatTurn("user", "hi"));
        when(springAi.generateContentWithHistory("sys", history, "msg", 100, 0.5, "m"))
                .thenReturn(Optional.of("ok"));

        Optional<String> result = router.generateContentWithHistory("sys", history, "msg", 100, 0.5, "m");

        assertThat(result).contains("ok");
        verify(springAi).generateContentWithHistory("sys", history, "msg", 100, 0.5, "m");
        verify(gemini, never()).generateContentWithHistory(any(), any(), any(), anyInt(), anyDouble(), any());
    }

    @Test
    void provider_case_insensitive() {
        // "Spring-AI" / "SPRING-AI" 都該認得
        when(springAi.generateContent("sys", "user")).thenReturn(Optional.of("ok"));

        new RoutingLlmClient(gemini, springAi, "Spring-AI").generateContent("sys", "user");
        new RoutingLlmClient(gemini, springAi, "SPRING-AI").generateContent("sys", "user");

        verify(springAi, org.mockito.Mockito.times(2)).generateContent("sys", "user");
    }

    @Test
    void unknown_provider_defaults_to_gemini() {
        // 拼錯 / 給 null / 給空 — 都走 Gemini 比較安全
        RoutingLlmClient router = new RoutingLlmClient(gemini, springAi, "openai");
        when(gemini.generateContent(any(), any())).thenReturn(Optional.of("ok"));

        router.generateContent("s", "u");

        verify(gemini).generateContent("s", "u");
        verifyNoInteractions(springAi);
    }

    // ─── tools / embedding 永遠走 Gemini，不管 provider ─────────────────────────

    @Test
    void tools_methods_always_go_to_gemini_even_with_springAi_provider() {
        RoutingLlmClient router = new RoutingLlmClient(gemini, springAi, "spring-ai");
        JsonObject tools = new JsonObject();
        GeminiResponse resp = GeminiResponse.builder().text("t").build();
        when(gemini.generateContentWithTools(eq("s"), any(), eq("u"), anyInt(), anyDouble(), any(), eq(tools)))
                .thenReturn(Optional.of(resp));

        Optional<GeminiResponse> result = router.generateContentWithTools(
                "s", List.of(), "u", 100, 0.5, null, tools);

        assertThat(result).isPresent();
        verify(gemini).generateContentWithTools(eq("s"), any(), eq("u"), anyInt(), anyDouble(), any(), eq(tools));
        verify(springAi, never()).generateContentWithTools(any(), any(), any(), anyInt(), anyDouble(), any(), any());
    }

    @Test
    void sendFunctionResult_always_to_gemini() {
        RoutingLlmClient router = new RoutingLlmClient(gemini, springAi, "spring-ai");
        JsonObject tools = new JsonObject();
        JsonObject args = new JsonObject();
        when(gemini.sendFunctionResult(any(), any(), any(), any(), any(), any(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(Optional.of("ok"));

        router.sendFunctionResult("s", List.of(), "u", "fn", args, "result", 100, 0.5, "m", tools);

        verify(gemini).sendFunctionResult(any(), any(), any(), any(), any(), any(), anyInt(), anyDouble(), any(), any());
        verify(springAi, never()).sendFunctionResult(any(), any(), any(), any(), any(), any(), anyInt(), anyDouble(), any(), any());
    }

    @Test
    void sendFunctionResultForChaining_always_to_gemini() {
        RoutingLlmClient router = new RoutingLlmClient(gemini, springAi, "spring-ai");
        when(gemini.sendFunctionResultForChaining(any(), any(), any(), any(), any(), any(), anyInt(), anyDouble(), any(), any()))
                .thenReturn(Optional.empty());

        router.sendFunctionResultForChaining("s", List.of(), "u", "fn", new JsonObject(), "result", 100, 0.5, "m", new JsonObject());

        verify(gemini).sendFunctionResultForChaining(any(), any(), any(), any(), any(), any(), anyInt(), anyDouble(), any(), any());
        verifyNoInteractions(springAi);
    }

    @Test
    void embedding_always_to_gemini() {
        RoutingLlmClient router = new RoutingLlmClient(gemini, springAi, "spring-ai");
        when(gemini.getEmbedding("text")).thenReturn(Optional.of(new float[]{1f, 2f, 3f}));
        when(gemini.getBatchEmbeddings(List.of("a", "b"))).thenReturn(Optional.of(List.of(new float[]{1f}, new float[]{2f})));

        router.getEmbedding("text");
        router.getBatchEmbeddings(List.of("a", "b"));

        verify(gemini).getEmbedding("text");
        verify(gemini).getBatchEmbeddings(List.of("a", "b"));
        verify(springAi, never()).getEmbedding(any());
        verify(springAi, never()).getBatchEmbeddings(any());
    }
}
