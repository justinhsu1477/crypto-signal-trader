package com.trader.chatbot.controller;

import com.trader.advisor.service.GeminiService;
import com.trader.chatbot.entity.ChatConversation;
import com.trader.chatbot.repository.ChatConversationRepository;
import com.trader.chatbot.service.KnowledgeIndexService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DisplayName("AdminChatbotController — Admin API")
class AdminChatbotControllerTest {

    @Mock private ChatConversationRepository conversationRepository;
    @Mock private GeminiService geminiService;
    @Mock private KnowledgeIndexService knowledgeIndexService;

    private AdminChatbotController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AdminChatbotController(conversationRepository, geminiService, knowledgeIndexService);
    }

    @Test
    @DisplayName("listConversations — 回傳分頁")
    void listConversations() {
        ChatConversation conv = ChatConversation.builder()
                .userId("u1").role("user").content("你好").createdAt(LocalDateTime.now()).build();
        Page<ChatConversation> page = new PageImpl<>(List.of(conv));
        when(conversationRepository.findLatestUserMessages(any())).thenReturn(page);

        ResponseEntity<Page<ChatConversation>> response = controller.listConversations(0, 20);

        assertThat(response.getBody().getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("getConversation — 回傳完整對話")
    void getConversation() {
        List<ChatConversation> conversations = List.of(
                ChatConversation.builder().role("user").content("你好").build(),
                ChatConversation.builder().role("assistant").content("回覆").build()
        );
        when(conversationRepository.findBySessionIdOrderByCreatedAtAsc("session1"))
                .thenReturn(conversations);

        ResponseEntity<List<ChatConversation>> response = controller.getConversation("session1");

        assertThat(response.getBody()).hasSize(2);
    }

    @Test
    @DisplayName("getStats — 回傳統計")
    void getStats() {
        when(conversationRepository.countDistinctSessions()).thenReturn(10L);
        when(conversationRepository.count()).thenReturn(50L);
        when(conversationRepository.countByIntentType()).thenReturn(
                List.of(new Object[]{"ACCOUNT_STATUS", 20L}, new Object[]{"TRADE_QUERY", 15L})
        );

        ResponseEntity<Map<String, Object>> response = controller.getStats();

        Map<String, Object> stats = response.getBody();
        assertThat(stats.get("totalSessions")).isEqualTo(10L);
        assertThat(stats.get("totalMessages")).isEqualTo(50L);
        @SuppressWarnings("unchecked")
        Map<String, Long> intents = (Map<String, Long>) stats.get("intentDistribution");
        assertThat(intents).containsEntry("ACCOUNT_STATUS", 20L);
    }

    @Nested
    @DisplayName("Embedding API")
    class EmbeddingTests {

        @Test
        @DisplayName("getEmbedding — 回傳 768 維向量")
        void getEmbedding_success() {
            float[] mockVector = new float[768];
            mockVector[0] = 0.123f;
            mockVector[767] = -0.456f;
            when(geminiService.getEmbedding("測試文字")).thenReturn(Optional.of(mockVector));

            ResponseEntity<Map<String, Object>> response = controller.getEmbedding("測試文字");

            Map<String, Object> body = response.getBody();
            assertThat(body.get("text")).isEqualTo("測試文字");
            assertThat(body.get("dimensions")).isEqualTo(768);
            assertThat(body.get("model")).isEqualTo("text-embedding-004");
            assertThat(body.get("vector")).isNotNull();
        }

        @Test
        @DisplayName("getEmbedding — API 失敗回 500")
        void getEmbedding_failure() {
            when(geminiService.getEmbedding(anyString())).thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> response = controller.getEmbedding("測試");

            assertThat(response.getStatusCode().value()).isEqualTo(500);
        }

        @Test
        @DisplayName("compareSimilarity — 回傳相似度分數")
        void compareSimilarity_success() {
            float[] vec1 = new float[768];
            float[] vec2 = new float[768];
            // 相同向量 → similarity = 1.0
            for (int i = 0; i < 768; i++) {
                vec1[i] = (float) Math.random();
                vec2[i] = vec1[i];
            }
            when(geminiService.getEmbedding("文字A")).thenReturn(Optional.of(vec1));
            when(geminiService.getEmbedding("文字B")).thenReturn(Optional.of(vec2));

            ResponseEntity<Map<String, Object>> response = controller.compareSimilarity("文字A", "文字B");

            Map<String, Object> body = response.getBody();
            assertThat(body.get("text1")).isEqualTo("文字A");
            assertThat(body.get("text2")).isEqualTo("文字B");
            assertThat((double) body.get("cosineSimilarity")).isEqualTo(1.0);
            assertThat(body.get("interpretation")).isEqualTo("幾乎相同語意");
        }

        @Test
        @DisplayName("knowledgeStats — 回傳索引統計")
        void knowledgeStats() {
            when(knowledgeIndexService.getIndexStats()).thenReturn("統計結果");

            ResponseEntity<String> response = controller.knowledgeStats();

            assertThat(response.getBody()).isEqualTo("統計結果");
        }
    }
}
