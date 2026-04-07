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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * FAQ 知識庫服務（混合評分搜尋策略）
 *
 * 搜尋策略：Hybrid Scoring（向量語意 + keyword 加權融合）
 * - 向量分數：pgvector cosine similarity，基於位置排名（1.0 → 0.1）
 * - Keyword 分數：tag 匹配數 / 最大匹配數，歸一化為 0~1
 * - 綜合分數：vectorScore * 0.7 + keywordScore * 0.3
 *
 * 任一維度有結果都會被考慮，兩維度同時命中的排更前面。
 * 向量搜尋失敗時自動降級為純 keyword 匹配。
 *
 * 啟動時載入 knowledge_base.md，解析為段落列表。
 * 向量索引由 KnowledgeIndexService 管理。
 */
@Slf4j
@Service
public class KnowledgeBaseService {

    private static final String KNOWLEDGE_BASE_PATH = "knowledge_base.md";
    private static final Pattern TAG_PATTERN = Pattern.compile("<!--\\s*tags:\\s*(.+?)\\s*-->");
    private static final int VECTOR_CANDIDATE_SIZE = 10;  // 向量粗篩候選數量
    private static final double VECTOR_WEIGHT = 0.7;
    private static final double KEYWORD_WEIGHT = 0.3;

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
     * 根據用戶訊息找出相關的知識段落（混合評分搜尋）
     *
     * 策略：向量語意 + keyword 加權融合
     * - 向量搜尋成功：兩維度融合評分
     * - 向量搜尋失敗：降級為純 keyword 匹配
     *
     * @param message     用戶訊息
     * @param maxSections 最多回傳段落數
     * @return 匹配的段落列表
     */
    public List<KnowledgeSection> findRelevantSections(String message, int maxSections) {
        if (message == null || message.isBlank()) {
            return Collections.emptyList();
        }

        // 1. 向量粗篩（取 VECTOR_CANDIDATE_SIZE 個候選）
        Map<String, Double> vectorScores = getVectorScores(message);

        // 2. Keyword 評分
        Map<String, Double> keywordScores = getKeywordScores(message);

        // 3. 如果兩者都沒結果 → 空
        if (vectorScores.isEmpty() && keywordScores.isEmpty()) {
            return Collections.emptyList();
        }

        // 4. 融合評分：兩維度加權合併
        Map<String, Double> combinedScores = new java.util.LinkedHashMap<>();
        Set<String> allTitles = new java.util.LinkedHashSet<>();
        allTitles.addAll(vectorScores.keySet());
        allTitles.addAll(keywordScores.keySet());

        for (String title : allTitles) {
            double vs = vectorScores.getOrDefault(title, 0.0);
            double ks = keywordScores.getOrDefault(title, 0.0);
            // 只有向量有結果時用混合權重，否則純 keyword
            double combined = vectorScores.isEmpty()
                    ? ks
                    : vs * VECTOR_WEIGHT + ks * KEYWORD_WEIGHT;
            combinedScores.put(title, combined);
        }

        // 5. 排序 + 取 top N
        List<String> rankedTitles = combinedScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(maxSections)
                .map(Map.Entry::getKey)
                .toList();

        // 6. 組裝結果（優先從 DB chunks 取，fallback 到 in-memory sections）
        Map<String, KnowledgeSection> sectionMap = buildSectionMap();

        List<KnowledgeSection> results = rankedTitles.stream()
                .filter(sectionMap::containsKey)
                .map(sectionMap::get)
                .toList();

        log.debug("知識庫搜尋：向量候選={}, keyword候選={}, 融合結果={}",
                vectorScores.size(), keywordScores.size(), results.size());
        return results;
    }

    /**
     * 向量搜尋 → title→score 映射（位置排名分數 1.0→0.1）
     */
    private Map<String, Double> getVectorScores(String message) {
        try {
            Optional<float[]> embedding = geminiService.getEmbedding(message);
            if (embedding.isEmpty()) {
                return Collections.emptyMap();
            }

            String queryVector = GeminiService.vectorToString(embedding.get());
            List<KnowledgeChunk> chunks = chunkRepository.findTopKBySimilarity(
                    queryVector, VECTOR_CANDIDATE_SIZE);

            if (chunks.isEmpty()) {
                return Collections.emptyMap();
            }

            // 位置排名分數：第 1 名 = 1.0, 第 2 名 = 0.9, ..., 第 10 名 = 0.1
            Map<String, Double> scores = new java.util.LinkedHashMap<>();
            for (int i = 0; i < chunks.size(); i++) {
                double score = 1.0 - (i * 0.9 / Math.max(1, chunks.size() - 1));
                scores.put(chunks.get(i).getTitle(), score);
            }
            return scores;
        } catch (Exception e) {
            log.warn("向量搜尋失敗（降級為純 keyword）: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Keyword tag matching → title→score 映射（歸一化 0~1）
     */
    private Map<String, Double> getKeywordScores(String message) {
        if (sections.isEmpty()) {
            return Collections.emptyMap();
        }

        String lower = message.toLowerCase();
        Map<String, Double> scores = new java.util.LinkedHashMap<>();
        int maxMatchCount = 0;

        // 先算原始匹配數
        List<int[]> rawScores = new ArrayList<>();
        for (int idx = 0; idx < sections.size(); idx++) {
            KnowledgeSection section = sections.get(idx);
            int matchCount = 0;
            for (String tag : section.getTags()) {
                if (lower.contains(tag.toLowerCase())) {
                    matchCount++;
                }
            }
            rawScores.add(new int[]{idx, matchCount});
            maxMatchCount = Math.max(maxMatchCount, matchCount);
        }

        if (maxMatchCount == 0) {
            return Collections.emptyMap();
        }

        // 歸一化為 0~1
        for (int[] raw : rawScores) {
            if (raw[1] > 0) {
                scores.put(sections.get(raw[0]).getTitle(), (double) raw[1] / maxMatchCount);
            }
        }
        return scores;
    }

    /**
     * Keyword tag matching（純 keyword 搜尋，供外部或測試直接呼叫）
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
     * 建立 title → KnowledgeSection 映射（DB chunks + in-memory sections 合併）
     */
    private Map<String, KnowledgeSection> buildSectionMap() {
        Map<String, KnowledgeSection> map = new java.util.LinkedHashMap<>();

        // in-memory sections（含 tags）
        for (KnowledgeSection section : sections) {
            map.put(section.getTitle(), section);
        }

        // DB chunks（可能有 in-memory 沒有的動態新增知識）
        try {
            for (KnowledgeChunk chunk : chunkRepository.findByEnabledTrue()) {
                map.putIfAbsent(chunk.getTitle(),
                        KnowledgeSection.builder()
                                .title(chunk.getTitle())
                                .tags(Collections.emptySet())
                                .content(chunk.getContent())
                                .build());
            }
        } catch (Exception e) {
            log.warn("DB chunks 載入失敗: {}", e.getMessage());
        }

        return map;
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
