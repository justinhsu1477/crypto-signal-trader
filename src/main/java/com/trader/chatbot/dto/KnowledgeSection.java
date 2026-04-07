package com.trader.chatbot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * FAQ 知識庫段落
 *
 * 從 knowledge_base.md 解析而來，每段有標題、標籤和內容。
 * 標籤用於 keyword matching，決定哪些段落注入 LLM context。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeSection {
    private String title;
    private Set<String> tags;
    private String content;
}
