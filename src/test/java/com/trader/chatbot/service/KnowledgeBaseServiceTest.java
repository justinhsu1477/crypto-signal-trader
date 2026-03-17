package com.trader.chatbot.service;

import com.trader.chatbot.dto.KnowledgeSection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KnowledgeBaseService — FAQ 知識庫")
class KnowledgeBaseServiceTest {

    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseService();
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
    @DisplayName("關鍵字匹配")
    class MatchingTests {

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
            service.parseSections(md).forEach(s -> {}); // trigger parse
            // Use reflection-free approach: directly test parseSections + findRelevantSections
            service = new KnowledgeBaseService() {
                {
                    // Manually inject parsed sections
                    try {
                        var field = KnowledgeBaseService.class.getDeclaredField("sections");
                        field.setAccessible(true);
                        field.set(this, parseSections(md));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            };
        }

        @Test
        @DisplayName("匹配單一 tag → 回傳對應段落")
        void matchSingleTag() {
            List<KnowledgeSection> result = service.findRelevantSections("怎麼綁定 api key", 3);

            assertThat(result).isNotEmpty();
            assertThat(result.get(0).getTitle()).isEqualTo("API Key 綁定教學");
        }

        @Test
        @DisplayName("匹配多個 tag → 分數高的排前面")
        void higherScoreFirst() {
            // "綁定" 匹配 API Key 和 LINE 兩個段落，但 "api key" 只匹配第一段
            List<KnowledgeSection> result = service.findRelevantSections("綁定 api key", 3);

            assertThat(result).hasSizeGreaterThanOrEqualTo(1);
            assertThat(result.get(0).getTitle()).isEqualTo("API Key 綁定教學"); // 2 tags matched
        }

        @Test
        @DisplayName("「綁定」同時匹配多段 → 都回傳")
        void matchMultipleSections() {
            List<KnowledgeSection> result = service.findRelevantSections("如何綁定", 3);

            assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("無匹配 → 回傳空")
        void noMatch() {
            List<KnowledgeSection> result = service.findRelevantSections("今天天氣如何", 3);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("null/空訊息 → 回傳空")
        void nullMessage() {
            assertThat(service.findRelevantSections(null, 3)).isEmpty();
            assertThat(service.findRelevantSections("", 3)).isEmpty();
            assertThat(service.findRelevantSections("   ", 3)).isEmpty();
        }

        @Test
        @DisplayName("maxSections 限制回傳數量")
        void maxSectionsLimit() {
            List<KnowledgeSection> result = service.findRelevantSections("綁定", 1);

            assertThat(result).hasSize(1);
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
            // 驗證至少有 FAQ 中的段落
            assertThat(service.getSections().stream()
                    .anyMatch(s -> s.getTitle().contains("API Key"))).isTrue();
        }
    }
}
