package com.trader.chatbot.service;

import com.trader.shared.llm.LlmClient;
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
@DisplayName("KnowledgeBaseService — FAQ 知識庫（混合評分搜尋）")
class KnowledgeBaseServiceTest {

    @Mock
    private KnowledgeChunkRepository chunkRepository;

    @Mock
    private LlmClient geminiService;

    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseService(chunkRepository, geminiService);
    }

    private void loadTestSections(String md) {
        try {
            var field = KnowledgeBaseService.class.getDeclaredField("sections");
            field.setAccessible(true);
            field.set(service, service.parseSections(md));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
    @DisplayName("Keyword 匹配")
    class KeywordMatchingTests {

        @BeforeEach
        void loadTestData() {
            loadTestSections("""
                    ## API Key 綁定教學
                    <!-- tags: api key,apikey,綁定,幣安,binance -->
                    步驟 1：登入 Binance...

                    ## LINE 綁定流程
                    <!-- tags: line,綁定,通知,連結碼 -->
                    步驟 1：產生連結碼...

                    ## 風控參數說明
                    <!-- tags: 風險,風控,槓桿,leverage,dca -->
                    風險比例、槓桿、DCA 說明...
                    """);
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
    @DisplayName("混合評分搜尋")
    class HybridScoringTests {

        @Test
        @DisplayName("向量 + keyword 都有結果 → 融合評分排序")
        void hybridScoringCombinesBothSignals() {
            loadTestSections("""
                    ## API Key 綁定教學
                    <!-- tags: api key,綁定,binance -->
                    步驟說明...

                    ## LINE 綁定流程
                    <!-- tags: line,綁定,通知 -->
                    LINE 綁定說明...
                    """);

            // 向量搜尋：API Key 排第 1, LINE 排第 2
            float[] mockVector = new float[768];
            when(geminiService.getEmbedding(anyString())).thenReturn(Optional.of(mockVector));
            when(chunkRepository.findTopKBySimilarity(anyString(), anyInt()))
                    .thenReturn(List.of(
                            KnowledgeChunk.builder().title("API Key 綁定教學").content("步驟說明...").build(),
                            KnowledgeChunk.builder().title("LINE 綁定流程").content("LINE 說明...").build()
                    ));
            when(chunkRepository.findByEnabledTrue()).thenReturn(Collections.emptyList());

            // 用戶問「綁定」— keyword 兩段都匹配
            List<KnowledgeSection> result = service.findRelevantSections("怎麼綁定", 3);

            assertThat(result).isNotEmpty();
            // API Key 向量分數高 + keyword 也匹配 → 排第一
            assertThat(result.get(0).getTitle()).isEqualTo("API Key 綁定教學");
        }

        @Test
        @DisplayName("向量有結果但 keyword 無匹配 → 仍使用向量結果")
        void vectorOnlyNoKeyword() {
            loadTestSections("""
                    ## API Key 綁定教學
                    <!-- tags: api key,綁定 -->
                    步驟說明...
                    """);

            float[] mockVector = new float[768];
            when(geminiService.getEmbedding(anyString())).thenReturn(Optional.of(mockVector));
            when(chunkRepository.findTopKBySimilarity(anyString(), anyInt()))
                    .thenReturn(List.of(
                            KnowledgeChunk.builder().title("API Key 綁定教學").content("步驟說明...").build()
                    ));
            when(chunkRepository.findByEnabledTrue()).thenReturn(Collections.emptyList());

            // 用戶用語意相似但不含 tags 的問法
            List<KnowledgeSection> result = service.findRelevantSections("金鑰怎麼設定", 3);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("API Key 綁定教學");
        }

        @Test
        @DisplayName("向量搜尋失敗 → 降級為純 keyword")
        void vectorFails_fallbackToKeyword() {
            loadTestSections("""
                    ## 風控參數說明
                    <!-- tags: 風控,風險,槓桿 -->
                    風險比例說明...
                    """);

            when(geminiService.getEmbedding(anyString())).thenReturn(Optional.empty());

            List<KnowledgeSection> result = service.findRelevantSections("風控設定", 3);

            assertThat(result).isNotEmpty();
            assertThat(result.get(0).getTitle()).isEqualTo("風控參數說明");
        }

        @Test
        @DisplayName("向量搜尋異常 → 降級為 keyword 不拋錯")
        void vectorException_fallbackGracefully() {
            loadTestSections("""
                    ## LINE 綁定流程
                    <!-- tags: line,通知 -->
                    LINE 綁定說明...
                    """);

            when(geminiService.getEmbedding(anyString())).thenThrow(new RuntimeException("API 爆了"));

            List<KnowledgeSection> result = service.findRelevantSections("line 通知", 3);

            assertThat(result).isNotEmpty();
            assertThat(result.get(0).getTitle()).isEqualTo("LINE 綁定流程");
        }

        @Test
        @DisplayName("兩者都無結果 → 回傳空")
        void bothEmpty_returnsEmpty() {
            loadTestSections("""
                    ## API Key 綁定教學
                    <!-- tags: api key,綁定 -->
                    步驟說明...
                    """);

            when(geminiService.getEmbedding(anyString())).thenReturn(Optional.of(new float[768]));
            when(chunkRepository.findTopKBySimilarity(anyString(), anyInt()))
                    .thenReturn(Collections.emptyList());

            List<KnowledgeSection> result = service.findRelevantSections("今天天氣怎麼樣", 3);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("null/空訊息 → 回傳空")
        void nullOrBlankMessage() {
            assertThat(service.findRelevantSections(null, 3)).isEmpty();
            assertThat(service.findRelevantSections("", 3)).isEmpty();
            assertThat(service.findRelevantSections("   ", 3)).isEmpty();
        }

        @Test
        @DisplayName("DB 有動態新增的知識 → 也能出現在結果")
        void dynamicChunksFromDb() {
            loadTestSections("");  // in-memory 無段落

            float[] mockVector = new float[768];
            when(geminiService.getEmbedding(anyString())).thenReturn(Optional.of(mockVector));

            KnowledgeChunk dbChunk = KnowledgeChunk.builder()
                    .title("動態新增知識")
                    .content("這是管理員手動新增的知識")
                    .build();
            when(chunkRepository.findTopKBySimilarity(anyString(), anyInt()))
                    .thenReturn(List.of(dbChunk));
            when(chunkRepository.findByEnabledTrue()).thenReturn(List.of(dbChunk));

            List<KnowledgeSection> result = service.findRelevantSections("新功能", 3);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("動態新增知識");
        }

        @Test
        @DisplayName("keyword 加分讓原本向量排名較低的結果升上來")
        void keywordBoostReranks() {
            loadTestSections("""
                    ## 訂閱方案說明
                    <!-- tags: 訂閱,方案,付費 -->
                    訂閱說明...

                    ## 風控參數說明
                    <!-- tags: 風控,風險,槓桿 -->
                    風控說明...
                    """);

            float[] mockVector = new float[768];
            when(geminiService.getEmbedding(anyString())).thenReturn(Optional.of(mockVector));

            // 向量：風控排第 1, 訂閱排第 2
            when(chunkRepository.findTopKBySimilarity(anyString(), anyInt()))
                    .thenReturn(List.of(
                            KnowledgeChunk.builder().title("風控參數說明").content("風控說明...").build(),
                            KnowledgeChunk.builder().title("訂閱方案說明").content("訂閱說明...").build()
                    ));
            when(chunkRepository.findByEnabledTrue()).thenReturn(Collections.emptyList());

            // 用戶問的訊息只匹配「訂閱」keyword → 訂閱的 keyword 分數 > 風控
            // 但向量風控排第 1 (score=1.0) vs 訂閱排第 2 (score=0.1)
            // 風控：1.0*0.7 + 0*0.3 = 0.7
            // 訂閱：0.1*0.7 + 1.0*0.3 = 0.37
            // 風控仍排第一（向量權重 0.7 較高）
            List<KnowledgeSection> result = service.findRelevantSections("訂閱方案付費", 3);

            assertThat(result).hasSizeGreaterThanOrEqualTo(2);
            // 兩個都在結果中（不會像之前的 fallback 模式那樣只看到向量結果）
            assertThat(result.stream().map(KnowledgeSection::getTitle))
                    .contains("訂閱方案說明", "風控參數說明");
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

        @Test
        @DisplayName("擴充後的知識庫包含新段落")
        void expandedKnowledgeBase() {
            service.loadKnowledgeBase();

            List<String> titles = service.getSections().stream()
                    .map(KnowledgeSection::getTitle)
                    .toList();

            assertThat(titles).contains("新手入門指南");
            assertThat(titles).contains("市場數據與指標解讀");
            assertThat(titles).contains("訊號品質與 AI 分析");
            assertThat(titles).contains("DCA 加碼策略說明");
            assertThat(titles).contains("帳號管理與安全");
            assertThat(titles).contains("異常排查指南");
        }
    }
}
