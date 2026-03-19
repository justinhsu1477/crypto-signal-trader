package com.trader.chatbot.repository;

import com.trader.chatbot.entity.KnowledgeChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * RAG 向量知識庫 Repository
 *
 * pgvector 語意搜尋：
 * - <=> 運算子 = cosine distance（0=完全相同, 2=完全相反）
 * - 1 - distance = similarity（0~1, 越高越相似）
 */
@Repository
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    /**
     * 向量語意搜尋 — 找最相似的 N 個 chunks
     *
     * @param queryEmbedding 查詢向量（字串格式 "[0.1, 0.2, ...]"）
     * @param topK           回傳筆數
     * @return 按相似度排序的 chunks
     */
    @Query(value = "SELECT * FROM knowledge_chunks " +
            "WHERE enabled = true AND embedding IS NOT NULL " +
            "ORDER BY embedding <=> CAST(:query AS vector) " +
            "LIMIT :topK",
            nativeQuery = true)
    List<KnowledgeChunk> findTopKBySimilarity(@Param("query") String queryEmbedding,
                                               @Param("topK") int topK);

    /**
     * 向量語意搜尋（帶相似度門檻）
     *
     * @param queryEmbedding 查詢向量
     * @param topK           回傳筆數
     * @param maxDistance     最大 cosine distance（建議 0.5，越小越嚴格）
     * @return 相似度足夠高的 chunks
     */
    @Query(value = "SELECT * FROM knowledge_chunks " +
            "WHERE enabled = true AND embedding IS NOT NULL " +
            "AND embedding <=> CAST(:query AS vector) < :maxDistance " +
            "ORDER BY embedding <=> CAST(:query AS vector) " +
            "LIMIT :topK",
            nativeQuery = true)
    List<KnowledgeChunk> findTopKBySimilarityWithThreshold(@Param("query") String queryEmbedding,
                                                            @Param("topK") int topK,
                                                            @Param("maxDistance") double maxDistance);

    List<KnowledgeChunk> findByEnabledTrue();

    List<KnowledgeChunk> findBySourceAndEnabledTrue(String source);

    long countByEnabledTrue();

    long countByEmbeddingIsNotNullAndEnabledTrue();
}
