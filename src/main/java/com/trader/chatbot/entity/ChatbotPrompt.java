package com.trader.chatbot.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Chatbot 系統 Prompt 版本（W6a — Prompt 資料化）
 *
 * 獨立於 {@link com.trader.trading.entity.PromptVersion}（訊號解析 prompt 專用）。
 *
 * 同一個 name 可有多版本，但只能有一個 active（由 DB 部分索引保證）。
 * Admin 可以熱改 prompt 不需重部署。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "chatbot_prompts",
        uniqueConstraints = @UniqueConstraint(name = "uk_chatbot_prompt_name_version",
                columnNames = {"name", "version"}))
public class ChatbotPrompt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Prompt 識別名稱（如 system_user / system_admin / intent_classifier） */
    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false)
    private Integer version;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(length = 500)
    private String description;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
