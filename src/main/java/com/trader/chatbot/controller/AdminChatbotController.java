package com.trader.chatbot.controller;

import com.trader.advisor.service.GeminiService;
import com.trader.chatbot.entity.ChatConversation;
import com.trader.chatbot.repository.ChatConversationRepository;
import com.trader.chatbot.service.KnowledgeIndexService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Admin 客服監控 API
 */
@RestController
@RequestMapping("/api/admin/chatbot")
@RequiredArgsConstructor
public class AdminChatbotController {

    private final ChatConversationRepository conversationRepository;
    private final GeminiService geminiService;
    private final KnowledgeIndexService knowledgeIndexService;

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

        // Feedback 統計
        long positive = conversationRepository.countByFeedbackRating(1);
        long negative = conversationRepository.countByFeedbackRating(-1);
        long feedbackTotal = conversationRepository.countByFeedbackRatingIsNotNull();
        double satisfactionRate = feedbackTotal > 0 ? (double) positive / feedbackTotal * 100 : 0;

        Map<String, Object> feedback = new HashMap<>();
        feedback.put("positive", positive);
        feedback.put("negative", negative);
        feedback.put("total", feedbackTotal);
        feedback.put("satisfactionRate", Math.round(satisfactionRate * 10.0) / 10.0);
        stats.put("feedback", feedback);

        // 每個意圖的 feedback 分佈（哪些意圖常被 👎）
        List<Object[]> feedbackByIntent = conversationRepository.countFeedbackByIntent();
        Map<String, Map<String, Long>> intentFeedback = new HashMap<>();
        for (Object[] row : feedbackByIntent) {
            String intent = (String) row[0];
            Integer rating = (Integer) row[1];
            long count = (Long) row[2];
            intentFeedback.computeIfAbsent(intent != null ? intent : "UNKNOWN", k -> new HashMap<>())
                    .put(rating == 1 ? "positive" : "negative", count);
        }
        stats.put("intentFeedback", intentFeedback);

        return ResponseEntity.ok(stats);
    }

    /**
     * Embedding 試玩 API — 輸入文字，回傳 768 維向量 + 維度資訊
     *
     * GET /api/admin/chatbot/embedding?text=蔡璧鴻很靠北
     */
    @GetMapping("/embedding")
    public ResponseEntity<Map<String, Object>> getEmbedding(@RequestParam String text) {
        Optional<float[]> embedding = geminiService.getEmbedding(text);
        if (embedding.isEmpty()) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Embedding API 呼叫失敗"));
        }

        float[] vector = embedding.get();
        Map<String, Object> result = new HashMap<>();
        result.put("text", text);
        result.put("dimensions", vector.length);
        result.put("model", "text-embedding-004");
        result.put("vector", vector);
        result.put("vectorString", GeminiService.vectorToString(vector));
        return ResponseEntity.ok(result);
    }

    /**
     * 兩段文字的語意相似度比較
     *
     * GET /api/admin/chatbot/similarity?text1=xxx&text2=yyy
     */
    @GetMapping("/similarity")
    public ResponseEntity<Map<String, Object>> compareSimilarity(
            @RequestParam String text1, @RequestParam String text2) {
        Optional<float[]> emb1 = geminiService.getEmbedding(text1);
        Optional<float[]> emb2 = geminiService.getEmbedding(text2);

        if (emb1.isEmpty() || emb2.isEmpty()) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Embedding API 呼叫失敗"));
        }

        double similarity = cosineSimilarity(emb1.get(), emb2.get());

        Map<String, Object> result = new HashMap<>();
        result.put("text1", text1);
        result.put("text2", text2);
        result.put("cosineSimilarity", Math.round(similarity * 10000.0) / 10000.0);
        result.put("interpretation", interpretSimilarity(similarity));
        return ResponseEntity.ok(result);
    }

    /**
     * 知識庫索引統計 + 重建
     */
    @GetMapping("/knowledge/stats")
    public ResponseEntity<String> knowledgeStats() {
        return ResponseEntity.ok(knowledgeIndexService.getIndexStats());
    }

    @PostMapping("/knowledge/rebuild")
    public ResponseEntity<String> rebuildKnowledge() {
        return ResponseEntity.ok(knowledgeIndexService.rebuildAllEmbeddings());
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dotProduct = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private String interpretSimilarity(double similarity) {
        if (similarity >= 0.9) return "幾乎相同語意";
        if (similarity >= 0.7) return "高度相似";
        if (similarity >= 0.5) return "中度相似";
        if (similarity >= 0.3) return "低度相似";
        return "幾乎無關";
    }
}
