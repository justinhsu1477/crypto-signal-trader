package com.trader.chatbot.controller;

import com.trader.chatbot.entity.ChatConversation;
import com.trader.chatbot.repository.ChatConversationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin 客服監控 API
 */
@RestController
@RequestMapping("/api/admin/chatbot")
@RequiredArgsConstructor
public class AdminChatbotController {

    private final ChatConversationRepository conversationRepository;

    /**
     * 最近用戶訊息列表（分頁）
     */
    @GetMapping("/conversations")
    public ResponseEntity<Page<ChatConversation>> listConversations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(
                conversationRepository.findLatestUserMessages(PageRequest.of(page, size)));
    }

    /**
     * 查看完整對話
     */
    @GetMapping("/conversations/{sessionId}")
    public ResponseEntity<List<ChatConversation>> getConversation(@PathVariable String sessionId) {
        return ResponseEntity.ok(
                conversationRepository.findBySessionIdOrderByCreatedAtAsc(sessionId));
    }

    /**
     * 統計資訊
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalSessions", conversationRepository.countDistinctSessions());
        stats.put("totalMessages", conversationRepository.count());

        // 意圖分佈
        List<Object[]> intentCounts = conversationRepository.countByIntentType();
        Map<String, Long> intentDistribution = new HashMap<>();
        for (Object[] row : intentCounts) {
            intentDistribution.put((String) row[0], (Long) row[1]);
        }
        stats.put("intentDistribution", intentDistribution);

        return ResponseEntity.ok(stats);
    }
}
