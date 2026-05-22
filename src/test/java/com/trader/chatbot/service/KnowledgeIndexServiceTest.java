package com.trader.chatbot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * KnowledgeIndexService.buildTagsMetadata 的單元測試。
 *
 * <p>只測 helper 的 JSON 安全性 — 完整的 INSERT 流程驗證在
 * {@link com.trader.integration.KnowledgeChunkInsertIntegrationTest}。
 */
class KnowledgeIndexServiceTest {

    private final KnowledgeIndexService service = new KnowledgeIndexService(
            mock(KnowledgeBaseService.class),
            mock(com.trader.chatbot.repository.KnowledgeChunkRepository.class),
            mock(com.trader.shared.llm.LlmClient.class),
            new ObjectMapper()
    );

    @Test
    void buildTagsMetadata_normal_tags_produces_valid_json() {
        String json = service.buildTagsMetadata(List.of("binance", "api", "trade"));
        assertThat(json).isEqualTo("{\"tags\":\"binance,api,trade\"}");
    }

    @Test
    void buildTagsMetadata_empty_collection_produces_empty_tags() {
        String json = service.buildTagsMetadata(List.of());
        assertThat(json).isEqualTo("{\"tags\":\"\"}");
    }

    @Test
    void buildTagsMetadata_null_collection_does_not_throw() {
        String json = service.buildTagsMetadata(null);
        assertThat(json).isEqualTo("{\"tags\":\"\"}");
    }

    @Test
    void buildTagsMetadata_tags_with_quote_escapes_correctly() {
        // 既有實作（字串串接）會產出非法 JSON: {"tags": "chenge "VIP" group"}
        // ObjectMapper 會自動 escape:        {"tags":"chenge \"VIP\" group"}
        String json = service.buildTagsMetadata(List.of("chenge \"VIP\" group"));
        assertThat(json).contains("\\\"VIP\\\"");
        // 反向驗證：跑 JSON parser 不應拋
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                new ObjectMapper().readTree(json));
    }

    @Test
    void buildTagsMetadata_tags_with_backslash_escapes_correctly() {
        String json = service.buildTagsMetadata(List.of("path\\to\\file"));
        // ObjectMapper 自動把 \ 轉 \\
        assertThat(json).contains("\\\\");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                new ObjectMapper().readTree(json));
    }

    @Test
    void buildTagsMetadata_accepts_Set_input() {
        // KnowledgeSection.getTags() 回 Set<String>，驗 Collection 簽名能接
        Set<String> tags = Set.of("a", "b");
        String json = service.buildTagsMetadata(tags);
        // Set 順序不保證，contains 任一順序
        assertThat(json).startsWith("{\"tags\":\"").endsWith("\"}");
        assertThat(json).contains("a").contains("b");
    }
}
