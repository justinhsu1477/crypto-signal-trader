package com.trader.chatbot.service;

import com.trader.advisor.service.GeminiService;
import com.trader.chatbot.dto.KnowledgeSection;
import com.trader.chatbot.entity.KnowledgeChunk;
import com.trader.chatbot.repository.KnowledgeChunkRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * FAQ 知識庫服務（混合搜尋策略）
 *
 * 搜尋優先順序：
 * 1. 向量語意搜尋（pgvector）— 語意匹配，用戶換說法也能找到
 * 2. Keyword tag matching — 零延遲 fallback，embedding 不可用時退而求其次
 *
 * 啟動時載入 knowledge_base.md，解析為段落列表。
 * 向量索引由 KnowledgeIndexService 管理。
 */
@Slf4j
@Service
public class KnowledgeBaseService {

    private static final String KNOWLEDGE_BASE_PATH = "knowledge_base.md";
    private static final Pattern TAG_PATTERN = Pattern.compile("<!--\\s*tags:\\s*(.+?)\\s*-->");
    private static final double SIMILARITY_THRESHOLD = 0.5;  // cosine distance 門檻（越小越嚴格）

    private final KnowledgeChunkRepository chunkRepository;
    private final GeminiService geminiService;

    private List<KnowledgeSection> sections = Collections.emptyList();

    public KnowledgeBaseService(KnowledgeChunkRepository chunkRepository, GeminiService geminiService) {
        this.chunkRepository = chunkRepository;
        this.geminiService = geminiService;
    }

    @PostConstruct
    void loadKnowledgeBase() {
        try {
            ClassPathResource resource = new ClassPathResource(KNOWLEDGE_BASE_PATH);
            String content;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                content = reader.lines().collect(Collectors.joining("\n"));
            }
            sections = parseSections(content);
            log.info("FAQ 知識庫載入完成：{} 段", sections.size());
        } catch (Exception e) {
            log.warn("FAQ 知識庫載入失敗: {}", e.getMessage());
            sections = Collections.emptyList();
        }
    }

    /**
     * 根據用戶訊息找出相關的知識段落（混合搜尋）
     *
     * 策略：向量語意搜尋優先 → keyword fallback
     *
     * @param message     用戶訊息
     * @param maxSections 最多回傳段落數
     * @return 匹配的段落列表
     */
    public List<KnowledgeSection> findRelevantSections(String message, int maxSections) {
        if (message == null || message.isBlank()) {
            return Collections.emptyList();
        }

        // 1. 嘗試向量語意搜尋
        List<KnowledgeSection> vectorResults = findByVectorSearch(message, maxSections);
        if (!vectorResults.isEmpty()) {
            log.debug("知識庫搜尋：向量匹配 {} 段", vectorResults.size());
            return vectorResults;
        }

        // 2. Fallback: keyword tag matching
        log.debug("知識庫搜尋：向量無結果，fallback 到 keyword matching");
        return findByKeywordMatching(message, maxSections);
    }

    /**
     * 向量語意搜尋（pgvector）
     */
    private List<KnowledgeSection> findByVectorSearch(String message, int maxSections) {
        try {
            Optional<float[]> embedding = geminiService.getEmbedding(message);
            if (embedding.isEmpty()) {
                return Collections.emptyList();
            }

            String queryVector = GeminiService.vectorToString(embedding.get());
            List<KnowledgeChunk> chunks = chunkRepository.findTopKBySimilarityWithThreshold(
                    queryVector, maxSections, SIMILARITY_THRESHOLD);

            return chunks.stream()
                    .map(c -> KnowledgeSection.builder()
                            .title(c.getTitle())
                            .tags(Collections.emptySet())
                            .content(c.getContent())
                            .build())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("向量搜尋失敗（fallback 到 keyword）: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Keyword tag matching（零延遲 fallback）
     */
    List<KnowledgeSection> findByKeywordMatching(String message, int maxSections) {
        if (message == null || message.isBlank() || sections.isEmpty()) {
            return Collections.emptyList();
        }

        String lower = message.toLowerCase();

        List<ScoredSection> scored = new ArrayList<>();
        for (KnowledgeSection section : sections) {
            int score = 0;
            for (String tag : section.getTags()) {
                if (lower.contains(tag.toLowerCase())) {
                    score++;
                }
            }
            if (score > 0) {
                scored.add(new ScoredSection(section, score));
            }
        }

        scored.sort((a, b) -> Integer.compare(b.score, a.score));

        return scored.stream()
                .limit(maxSections)
                .map(s -> s.section)
                .collect(Collectors.toList());
    }

    /**
     * 解析 markdown 為段落列表
     * 以 ## 二級標題分段，每段開頭的 <!-- tags: --> 為標籤
     */
    List<KnowledgeSection> parseSections(String content) {
        List<KnowledgeSection> result = new ArrayList<>();
        // split 的 flags 參數是 int，不是 Pattern 常數；用 (?m) inline flag
        String[] parts = content.split("(?m)(?=^## )");

        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || (trimmed.startsWith("# ") && !trimmed.startsWith("## "))) {
                continue; // 跳過一級標題或空段
            }

            // 提取標題
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline < 0) continue;
            String title = trimmed.substring(0, firstNewline).replaceFirst("^##\\s*", "").trim();

            String body = trimmed.substring(firstNewline + 1).trim();

            // 提取 tags
            Set<String> tags = Collections.emptySet();
            Matcher matcher = TAG_PATTERN.matcher(body);
            if (matcher.find()) {
                tags = Arrays.stream(matcher.group(1).split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toSet());
                // 移除 tag 行
                body = body.substring(matcher.end()).trim();
            }

            if (!tags.isEmpty() && !body.isEmpty()) {
                result.add(KnowledgeSection.builder()
                        .title(title)
                        .tags(tags)
                        .content(body)
                        .build());
            }
        }

        return result;
    }

    List<KnowledgeSection> getSections() {
        return Collections.unmodifiableList(sections);
    }

    /**
     * 取得所有已載入的 FAQ 段落（KnowledgeIndexService 同步用）
     */
    public List<KnowledgeSection> getAllSections() {
        return Collections.unmodifiableList(sections);
    }

    private record ScoredSection(KnowledgeSection section, int score) {}
}
