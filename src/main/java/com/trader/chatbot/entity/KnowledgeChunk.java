package com.trader.chatbot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

import com.trader.shared.config.AppConstants;

/**
 * RAG 向量知識庫 — 儲存文本 chunks 及其 embedding 向量
 *
 * 用途：
 * - FAQ 教學文件
 * - 交易策略說明
 * - 更新日誌 (changelog)
 * - 系統公告
 *
 * embedding 欄位由 Gemini text-embedding-004 生成，768 維向量
 * 使用 pgvector 的 cosine distance 做語意搜尋
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "knowledge_chunks")
public class KnowledgeChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, length = 100)
    private String source;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * pgvector 的 vector(768) 在 JPA 中以 String 形式存取
     * 格式：[0.1, 0.2, 0.3, ...]
     */
    @Column(columnDefinition = "vector(768)")
    private String embedding;

    @Column(columnDefinition = "JSONB")
    @Builder.Default
    private String metadata = "{}";

    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now(AppConstants.ZONE_ID);
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }
}
