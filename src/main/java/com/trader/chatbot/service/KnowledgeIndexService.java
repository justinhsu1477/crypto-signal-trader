package com.trader.chatbot.service;

import com.trader.advisor.service.GeminiService;
import com.trader.chatbot.dto.KnowledgeSection;
import com.trader.chatbot.entity.KnowledgeChunk;
import com.trader.chatbot.repository.KnowledgeChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 知識庫向量索引管理
 *
 * 職責：
 * 1. 啟動時自動從 knowledge_base.md 同步 chunks 到 DB
 * 2. 為沒有 embedding 的 chunks 生成向量
 * 3. 提供手動重建索引的 API
 *
 * 防重複策略：以 source + title 為唯一識別，存在則跳過
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIndexService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeChunkRepository chunkRepository;
    private final GeminiService geminiService;

    /**
     * 啟動時自動同步 knowledge_base.md → DB + embedding
     */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        try {
            log.info("知識庫向量索引：啟動同步...");
            int synced = syncFromMarkdown();
            int embedded = generateMissingEmbeddings();
            log.info("知識庫向量索引完成：同步 {} 段, 生成 {} 個 embedding", synced, embedded);
        } catch (Exception e) {
            log.warn("知識庫向量索引啟動失敗（非致命，keyword matching 仍可用）: {}", e.getMessage());
        }
    }

    /**
     * 從 knowledge_base.md 同步到 DB
     *
     * @return 新增的 chunk 數量
     */
    public int syncFromMarkdown() {
        List<KnowledgeSection> sections = knowledgeBaseService.getAllSections();
        int newCount = 0;

        for (KnowledgeSection section : sections) {
            // 用 source + title 做去重
            boolean exists = chunkRepository.findBySourceAndEnabledTrue("faq").stream()
                    .anyMatch(c -> c.getTitle().equals(section.getTitle()));

            if (!exists) {
                KnowledgeChunk chunk = KnowledgeChunk.builder()
                        .source("faq")
                        .title(section.getTitle())
                        .content(section.getContent())
                        .metadata("{\"tags\": \"" + String.join(",", section.getTags()) + "\"}")
                        .enabled(true)
                        .build();
                chunkRepository.save(chunk);
                newCount++;
                log.info("知識庫同步新增: {}", section.getTitle());
            }
        }

        return newCount;
    }

    /**
     * 為沒有 embedding 的 chunks 生成向量
     *
     * @return 成功生成的數量
     */
    public int generateMissingEmbeddings() {
        List<KnowledgeChunk> chunksWithoutEmbedding = chunkRepository.findByEnabledTrue().stream()
                .filter(c -> c.getEmbedding() == null)
                .toList();

        if (chunksWithoutEmbedding.isEmpty()) {
            log.info("所有 chunks 已有 embedding，無需生成");
            return 0;
        }

        int successCount = 0;
        for (KnowledgeChunk chunk : chunksWithoutEmbedding) {
            try {
                // 用 title + content 一起做 embedding，提升語意精度
                String textForEmbedding = chunk.getTitle() + "\n" + chunk.getContent();
                Optional<float[]> embedding = geminiService.getEmbedding(textForEmbedding);

                if (embedding.isPresent()) {
                    String vectorStr = GeminiService.vectorToString(embedding.get());
                    chunkRepository.updateEmbedding(chunk.getId(), vectorStr);
                    successCount++;
                    log.info("Embedding 生成成功: {} ({}維)", chunk.getTitle(), embedding.get().length);
                } else {
                    log.warn("Embedding 生成失敗: {}", chunk.getTitle());
                }

                // Rate limit 保護：每次間隔 200ms
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Embedding 生成中斷");
                break;
            } catch (Exception e) {
                log.warn("Embedding 生成異常: {} - {}", chunk.getTitle(), e.getMessage());
            }
        }

        return successCount;
    }

    /**
     * 手動重建全部索引（Admin API 用）
     * 流程：清空所有 embedding → 重新生成
     */
    public String rebuildAllEmbeddings() {
        List<KnowledgeChunk> allChunks = chunkRepository.findByEnabledTrue();
        allChunks.forEach(c -> chunkRepository.updateEmbedding(c.getId(), null));

        int synced = syncFromMarkdown();
        int embedded = generateMissingEmbeddings();

        return String.format("重建完成：同步 %d 段, 生成 %d 個 embedding（共 %d 段知識）",
                synced, embedded, chunkRepository.countByEnabledTrue());
    }

    /**
     * 手動新增知識 chunk（Admin API 用）
     */
    public KnowledgeChunk addChunk(String source, String title, String content) {
        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .source(source)
                .title(title)
                .content(content)
                .enabled(true)
                .build();
        chunkRepository.save(chunk);

        // 非同步生成 embedding（使用 native SQL 繞過 Hibernate vector 型別問題）
        try {
            String textForEmbedding = title + "\n" + content;
            geminiService.getEmbedding(textForEmbedding).ifPresent(vec -> {
                String vectorStr = GeminiService.vectorToString(vec);
                chunkRepository.updateEmbedding(chunk.getId(), vectorStr);
                log.info("新增知識 embedding 完成: {}", title);
            });
        } catch (Exception e) {
            log.warn("新增知識 embedding 失敗（chunk 已存，可稍後重建）: {}", e.getMessage());
        }

        return chunk;
    }

    /**
     * 取得索引統計
     */
    public String getIndexStats() {
        long total = chunkRepository.countByEnabledTrue();
        long withEmbedding = chunkRepository.countByEmbeddingIsNotNullAndEnabledTrue();
        return String.format("知識庫索引統計：\n- 總 chunks: %d\n- 已建立 embedding: %d\n- 未建立: %d",
                total, withEmbedding, total - withEmbedding);
    }
}
