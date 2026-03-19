package com.trader.chatbot.service;

import com.trader.advisor.service.GeminiService;
import com.trader.chatbot.dto.KnowledgeSection;
import com.trader.chatbot.entity.KnowledgeChunk;
import com.trader.chatbot.repository.KnowledgeChunkRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("KnowledgeBaseService — FAQ 知識庫（混合搜尋）")
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    @Mock
    private GeminiService geminiService;

    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseService(chunkRepository, geminiService);
    }

    @Nested
    @DisplayName("Markdown 解析")
    class ParseTests {

        @Test
        @DisplayName("解析帶 tags 的段落")
        void parseSectionsWithTags() {
            String md = """
                    # Title

                    ## API Key 綁定
                    <!-- tags: api key,綁定,binance -->
                    如何綁定 API Key...

                    ## LINE 通知
                    <!-- tags: line,通知,連結碼 -->
                    如何綁定 LINE...
                    """;

            List<KnowledgeSection> sections = service.parseSections(md);

            assertThat(sections).hasSize(2);
            assertThat(sections.get(0).getTitle()).isEqualTo("API Key 綁定");
            assertThat(sections.get(0).getTags()).containsExactlyInAnyOrder("api key", "綁定", "binance");
            assertThat(sections.get(0).getContent()).contains("如何綁定 API Key");
            assertThat(sections.get(1).getTitle()).isEqualTo("LINE 通知");
        }

        @Test
        @DisplayName("無 tags 的段落被忽略")
        void sectionWithoutTagsIgnored() {
            String md = """
                    ## No Tags Section
                    This has no tags comment.
                    """;

            List<KnowledgeSection> sections = service.parseSections(md);

            assertThat(sections).isEmpty();
        }

        @Test
        @DisplayName("空內容不產生段落")
        void emptySectionIgnored() {
            String md = """
                    ## Empty
                    <!-- tags: test -->
                    """;

            List<KnowledgeSection> sections = service.parseSections(md);

            assertThat(sections).isEmpty();
        }

        @Test
        @DisplayName("一級標題不被當作段落")
        void h1IgnoredAsSection() {
            String md = """
                    # Main Title
                    Some intro text.
                    """;

            List<KnowledgeSection> sections = service.parseSections(md);

            assertThat(sections).isEmpty();
        }
    }

    @Nested
    @DisplayName("Keyword 匹配（fallback）")
    class KeywordMatchingTests {

        @BeforeEach
        void loadTestData() {
            String md = """
                    ## API Key 綁定教學
                    <!-- tags: api key,apikey,綁定,幣安,binance -->
                    步驟 1：登入 Binance...

                    ## LINE 綁定流程
                    <!-- tags: line,綁定,通知,連結碼 -->
                    步驟 1：產生連結碼...

                    ## 風控參數說明
                    <!-- tags: 風險,風控,槓桿,leverage,dca -->
                    風險比例、槓桿、DCA 說明...
                    """;
            try {
                var field = KnowledgeBaseService.class.getDeclaredField("sections");
                field.setAccessible(true);
                field.set(service, service.parseSections(md));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("匹配單一 tag → 回傳對應段落")
        void matchSingleTag() {
            List<KnowledgeSection> result = service.findByKeywordMatching("怎麼綁定 api key", 3);

            assertThat(result).isNotEmpty();
            assertThat(result.get(0).getTitle()).isEqualTo("API Key 綁定教學");
        }

        @Test
        @DisplayName("匹配多個 tag → 分數高的排前面")
        void higherScoreFirst() {
            List<KnowledgeSection> result = service.findByKeywordMatching("綁定 api key", 3);

            assertThat(result).hasSizeGreaterThanOrEqualTo(1);
            assertThat(result.get(0).getTitle()).isEqualTo("API Key 綁定教學");
        }

        @Test
        @DisplayName("無匹配 → 回傳空")
        void noMatch() {
            List<KnowledgeSection> result = service.findByKeywordMatching("今天天氣如何", 3);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("null/空訊息 → 回傳空")
        void nullMessage() {
            assertThat(service.findByKeywordMatching(null, 3)).isEmpty();
            assertThat(service.findByKeywordMatching("", 3)).isEmpty();
        }

        @Test
        @DisplayName("maxSections 限制回傳數量")
        void maxSectionsLimit() {
            List<KnowledgeSection> result = service.findByKeywordMatching("綁定", 1);

            assertThat(result).hasSize(1);
        }
    }

    @Nested
    @DisplayName("混合搜尋策略")
    class HybridSearchTests {

        @Test
        @DisplayName("向量搜尋有結果 → 使用向量結果")
        void vectorSearchSuccess() {
            float[] mockVector = new float[768];
            when(geminiService.getEmbedding(anyString())).thenReturn(Optional.of(mockVector));

            KnowledgeChunk chunk = KnowledgeChunk.builder()
                    .title("API Key 綁定教學")
                    .content("如何綁定 API Key...")
                    .build();
            when(chunkRepository.findTopKBySimilarityWithThreshold(anyString(), anyInt(), anyDouble()))
                    .thenReturn(List.of(chunk));

            List<KnowledgeSection> result = service.findRelevantSections("金鑰怎麼設定", 3);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("API Key 綁定教學");
            verify(geminiService).getEmbedding("金鑰怎麼設定");
        }

        @Test
        @DisplayName("向量搜尋無結果 → fallback 到 keyword")
        void vectorSearchEmpty_fallbackToKeyword() {
            // 設定 sections 讓 keyword matching 有結果
            String md = """
                    ## API Key 綁定教學
                    <!-- tags: api key,綁定 -->
                    步驟說明...
                    """;
            try {
                var field = KnowledgeBaseService.class.getDeclaredField("sections");
                field.setAccessible(true);
                field.set(service, service.parseSections(md));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // 向量搜尋回空
            float[] mockVector = new float[768];
            when(geminiService.getEmbedding(anyString())).thenReturn(Optional.of(mockVector));
            when(chunkRepository.findTopKBySimilarityWithThreshold(anyString(), anyInt(), anyDouble()))
                    .thenReturn(Collections.emptyList());

            List<KnowledgeSection> result = service.findRelevantSections("api key 綁定", 3);

            assertThat(result).isNotEmpty();
            assertThat(result.get(0).getTitle()).isEqualTo("API Key 綁定教學");
        }

        @Test
        @DisplayName("Embedding API 失敗 → fallback 到 keyword")
        void embeddingApiFails_fallbackToKeyword() {
            String md = """
                    ## 風控參數說明
                    <!-- tags: 風控,風險,槓桿 -->
                    風險比例說明...
                    """;
            try {
                var field = KnowledgeBaseService.class.getDeclaredField("sections");
                field.setAccessible(true);
                field.set(service, service.parseSections(md));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            when(geminiService.getEmbedding(anyString())).thenReturn(Optional.empty());

            List<KnowledgeSection> result = service.findRelevantSections("風控設定", 3);

            assertThat(result).isNotEmpty();
            assertThat(result.get(0).getTitle()).isEqualTo("風控參數說明");
        }

        @Test
        @DisplayName("向量搜尋異常 → fallback 到 keyword 不拋錯")
        void vectorSearchException_fallbackGracefully() {
            String md = """
                    ## LINE 綁定流程
                    <!-- tags: line,通知 -->
                    LINE 綁定說明...
                    """;
            try {
                var field = KnowledgeBaseService.class.getDeclaredField("sections");
                field.setAccessible(true);
                field.set(service, service.parseSections(md));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            when(geminiService.getEmbedding(anyString())).thenThrow(new RuntimeException("API 爆了"));

            List<KnowledgeSection> result = service.findRelevantSections("line 通知", 3);

            assertThat(result).isNotEmpty();
            assertThat(result.get(0).getTitle()).isEqualTo("LINE 綁定流程");
        }
    }

    @Nested
    @DisplayName("啟動載入")
    class LoadTests {

        @Test
        @DisplayName("loadKnowledgeBase 載入 classpath 的 knowledge_base.md")
        void loadFromClasspath() {
            service.loadKnowledgeBase();

            assertThat(service.getSections()).isNotEmpty();
            assertThat(service.getSections().stream()
                    .anyMatch(s -> s.getTitle().contains("API Key"))).isTrue();
        }
    }
}
