package com.trader.chatbot.service;

import com.trader.chatbot.dto.ChatTurn;
import com.trader.shared.config.AiConfig;
import com.trader.shared.llm.LlmClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("QueryRewriteService — short / follow-up 重寫")
class QueryRewriteServiceTest {

    private LlmClient llmClient;
    private AiConfig aiConfig;
    private ChatbotPromptService promptService;
    private QueryRewriteService service;

    @BeforeEach
    void setUp() {
        llmClient = mock(LlmClient.class);
        aiConfig = mock(AiConfig.class);
        promptService = mock(ChatbotPromptService.class);
        when(aiConfig.getDefaultModel()).thenReturn("gemini-2.5-flash-lite");
        when(promptService.getActivePrompt(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1));
        ChatbotMetrics chatbotMetrics = new ChatbotMetrics(
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        service = new QueryRewriteService(llmClient, aiConfig, promptService, chatbotMetrics);
    }

    @Nested
    @DisplayName("shouldRewrite heuristic")
    class ShouldRewriteTests {

        @Test
        @DisplayName("歷史為空 → 不重寫（節省 LLM 成本）")
        void emptyHistoryNoRewrite() {
            assertThat(service.shouldRewrite("那 90 天呢", Collections.emptyList())).isFalse();
        }

        @Test
        @DisplayName("歷史為 null → 不重寫")
        void nullHistoryNoRewrite() {
            assertThat(service.shouldRewrite("那 90 天呢", null)).isFalse();
        }

        @Test
        @DisplayName("長 query 無代名詞 → 不重寫")
        void longStandaloneNoRewrite() {
            List<ChatTurn> hist = List.of(ChatTurn.builder().role("user").content("前一句").build());
            assertThat(service.shouldRewrite("請問陳哥最近 30 天的勝率是多少", hist)).isFalse();
        }

        @Test
        @DisplayName("短 query + 有歷史 → 重寫")
        void shortQueryWithHistoryShouldRewrite() {
            List<ChatTurn> hist = List.of(ChatTurn.builder().role("user").content("陳哥勝率").build());
            assertThat(service.shouldRewrite("90 天呢", hist)).isTrue();
        }

        @Test
        @DisplayName("長 query 但含代名詞 → 重寫")
        void longButHasPronounShouldRewrite() {
            List<ChatTurn> hist = List.of(ChatTurn.builder().role("user").content("陳哥").build());
            assertThat(service.shouldRewrite("那他 30 天勝率怎樣？好不好", hist)).isTrue();
        }
    }

    @Nested
    @DisplayName("rewrite 實際行為")
    class RewriteBehavior {

        @Test
        @DisplayName("歷史為空 → 不呼叫 LLM，原樣回傳")
        void noHistoryPassthrough() {
            String result = service.rewrite("最近勝率", Collections.emptyList());

            assertThat(result).isEqualTo("最近勝率");
            verify(llmClient, never()).generateContentWithHistory(
                    anyString(), any(), anyString(), anyInt(), anyDouble(), any());
        }

        @Test
        @DisplayName("LLM 成功重寫 → 回傳重寫後的 query")
        void llmRewritesSuccessfully() {
            List<ChatTurn> hist = List.of(
                    ChatTurn.builder().role("user").content("陳哥勝率").build(),
                    ChatTurn.builder().role("model").content("最近 7 天 60%").build());
            when(llmClient.generateContentWithHistory(anyString(), any(), anyString(), anyInt(), anyDouble(), any()))
                    .thenReturn(Optional.of("陳哥最近 90 天勝率如何"));

            String result = service.rewrite("90 天呢", hist);

            assertThat(result).isEqualTo("陳哥最近 90 天勝率如何");
        }

        @Test
        @DisplayName("LLM 回 empty → fallback 原 query")
        void llmEmptyFallback() {
            List<ChatTurn> hist = List.of(ChatTurn.builder().role("user").content("x").build());
            when(llmClient.generateContentWithHistory(anyString(), any(), anyString(), anyInt(), anyDouble(), any()))
                    .thenReturn(Optional.empty());

            String result = service.rewrite("那 30 天呢", hist);

            assertThat(result).isEqualTo("那 30 天呢");
        }

        @Test
        @DisplayName("LLM 拋異常 → fallback 原 query（不阻塞主流程）")
        void llmExceptionFallback() {
            List<ChatTurn> hist = List.of(ChatTurn.builder().role("user").content("x").build());
            when(llmClient.generateContentWithHistory(anyString(), any(), anyString(), anyInt(), anyDouble(), any()))
                    .thenThrow(new RuntimeException("LLM API down"));

            String result = service.rewrite("那 30 天呢", hist);

            assertThat(result).isEqualTo("那 30 天呢");
        }

        @Test
        @DisplayName("LLM 回覆帶「改寫：」前綴 → 去除")
        void stripsRewritePrefix() {
            List<ChatTurn> hist = List.of(ChatTurn.builder().role("user").content("x").build());
            when(llmClient.generateContentWithHistory(anyString(), any(), anyString(), anyInt(), anyDouble(), any()))
                    .thenReturn(Optional.of("改寫：陳哥最近 90 天勝率"));

            String result = service.rewrite("90 天呢", hist);

            assertThat(result).isEqualTo("陳哥最近 90 天勝率");
        }

        @Test
        @DisplayName("LLM 回覆被引號包起來 → 去除")
        void stripsQuotes() {
            List<ChatTurn> hist = List.of(ChatTurn.builder().role("user").content("x").build());
            when(llmClient.generateContentWithHistory(anyString(), any(), anyString(), anyInt(), anyDouble(), any()))
                    .thenReturn(Optional.of("「陳哥最近 90 天勝率」"));

            String result = service.rewrite("90 天呢", hist);

            assertThat(result).isEqualTo("陳哥最近 90 天勝率");
        }

        @Test
        @DisplayName("LLM 回覆異常長（疑似 hallucination）→ fallback 原 query")
        void tooLongFallback() {
            List<ChatTurn> hist = List.of(ChatTurn.builder().role("user").content("x").build());
            String tooLong = "a".repeat(250);
            when(llmClient.generateContentWithHistory(anyString(), any(), anyString(), anyInt(), anyDouble(), any()))
                    .thenReturn(Optional.of(tooLong));

            String result = service.rewrite("90 天呢", hist);

            assertThat(result).isEqualTo("90 天呢");
        }

        @Test
        @DisplayName("null query → 原樣回傳 null")
        void nullQuery() {
            assertThat(service.rewrite(null, List.of())).isNull();
        }

        @Test
        @DisplayName("空白 query → 原樣回傳")
        void blankQuery() {
            assertThat(service.rewrite("   ", List.of())).isEqualTo("   ");
        }
    }

    @Nested
    @DisplayName("歷史截斷")
    class HistoryLookback {

        @Test
        @DisplayName("只取最近 6 輪歷史塞入 prompt")
        void keepsLastSixTurns() {
            List<ChatTurn> hist = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                hist.add(ChatTurn.builder().role(i % 2 == 0 ? "user" : "model")
                        .content("訊息 " + i).build());
            }
            when(llmClient.generateContentWithHistory(anyString(), any(), anyString(), anyInt(), anyDouble(), any()))
                    .thenReturn(Optional.of("重寫結果"));

            service.rewrite("那個呢", hist);

            ArgumentCaptor<String> userMsgCaptor = ArgumentCaptor.forClass(String.class);
            verify(llmClient).generateContentWithHistory(anyString(), any(), userMsgCaptor.capture(),
                    anyInt(), anyDouble(), any());
            String sentPrompt = userMsgCaptor.getValue();
            // 最前的訊息應該是第 4 輪（第 5 個起算，index 4..9）
            assertThat(sentPrompt).contains("訊息 4");
            assertThat(sentPrompt).contains("訊息 9");
            // 早期訊息不應該出現
            assertThat(sentPrompt).doesNotContain("訊息 0");
            assertThat(sentPrompt).doesNotContain("訊息 1");
        }
    }
}
