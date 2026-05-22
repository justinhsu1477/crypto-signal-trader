package com.trader.integration;

import com.trader.chatbot.entity.KnowledgeChunk;
import com.trader.chatbot.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Integration test 抓「Hibernate JDBC binding 跟 DB column type 不一致」這類
 * runtime bug — {@link SchemaValidationIntegrationTest} 只看 DDL 級別 type，
 * 抓不到 String ↔ JSONB 這種 setString() 拒收的問題。
 *
 * <p>本測試對所有有特殊 PG 型別（JSONB / vector / array）的 entity 跑 actual
 * INSERT + SELECT round-trip，任何 PreparedStatement 拒收都會在這裡曝光。
 *
 * <p>2026-05-22 修 KnowledgeChunk.metadata 的契機：
 * {@code @Column(columnDefinition = "JSONB")} 但 Hibernate 預設 setString，
 * PostgreSQL 拒收 character varying 直接 cast 到 jsonb → INSERT 永遠失敗 →
 * Chatbot RAG 從來沒運作過。修法是加 {@code @JdbcTypeCode(SqlTypes.JSON)}。
 */
@DisplayName("Entity Insert Integration — 抓 Hibernate JDBC binding vs DB type 不一致")
class KnowledgeChunkInsertIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private KnowledgeChunkRepository chunkRepository;

    @Test
    @DisplayName("KnowledgeChunk.metadata JSONB — INSERT + SELECT round-trip 不拋例外")
    void knowledgeChunk_metadataJsonb_roundTrip() {
        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .source("test")
                .title("test-jsonb-metadata")
                .content("test content")
                .metadata("{\"tags\":\"binance,api\",\"version\":\"v1\"}")
                .enabled(true)
                .build();

        // 1. INSERT 不該拋例外（修前會拋：column "metadata" is of type jsonb but expression is of type character varying）
        assertThatCode(() -> chunkRepository.save(chunk))
                .as("KnowledgeChunk insert with JSONB metadata should not throw")
                .doesNotThrowAnyException();

        // 2. SELECT round-trip — 拿回來內容相同
        Optional<KnowledgeChunk> loaded = chunkRepository.findById(chunk.getId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getMetadata())
                .as("metadata round-trip 應保留原始 JSON 內容")
                .contains("\"tags\"")
                .contains("binance,api")
                .contains("\"version\"");
    }

    @Test
    @DisplayName("KnowledgeChunk.metadata — 預設 '{}' 也能正常 INSERT")
    void knowledgeChunk_emptyMetadata_inserts() {
        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .source("test")
                .title("test-empty-metadata")
                .content("content")
                .enabled(true)
                .build();   // 沒設 metadata，走 @Builder.Default "{}"

        assertThatCode(() -> chunkRepository.save(chunk))
                .doesNotThrowAnyException();

        Optional<KnowledgeChunk> loaded = chunkRepository.findById(chunk.getId());
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getMetadata()).isEqualTo("{}");
    }

    @Test
    @DisplayName("KnowledgeChunk.metadata — 含特殊字元（quote / 反斜線）的合法 JSON 也能存")
    void knowledgeChunk_metadataWithEscapedChars_inserts() {
        // Jackson 產出的 JSON 會 escape 內嵌的 "
        String json = "{\"tags\":\"chenge \\\"VIP\\\" group\",\"note\":\"line1\\nline2\"}";

        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .source("test")
                .title("test-escaped-metadata")
                .content("content")
                .metadata(json)
                .enabled(true)
                .build();

        assertThatCode(() -> chunkRepository.save(chunk))
                .as("含 escape char 的合法 JSON 應該正常存")
                .doesNotThrowAnyException();

        Optional<KnowledgeChunk> loaded = chunkRepository.findById(chunk.getId());
        assertThat(loaded).isPresent();
        // PG jsonb 會 normalize / re-format，但語意內容應一致
        assertThat(loaded.get().getMetadata())
                .contains("chenge")
                .contains("VIP")
                .contains("line1")
                .contains("line2");
    }
}
