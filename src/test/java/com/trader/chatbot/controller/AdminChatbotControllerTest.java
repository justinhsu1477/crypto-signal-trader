package com.trader.chatbot.controller;

import com.trader.chatbot.entity.ChatConversation;
import com.trader.chatbot.repository.ChatConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("AdminChatbotController — Admin API")
class AdminChatbotControllerTest {

    @Mock private ChatConversationRepository conversationRepository;

    private AdminChatbotController controller;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        controller = new AdminChatbotController(conversationRepository);
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
}
