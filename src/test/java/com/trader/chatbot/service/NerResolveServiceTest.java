package com.trader.chatbot.service;

import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.repository.SignalSourceConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("NerResolveService — 訊號來源實體解析")
class NerResolveServiceTest {

    private SignalSourceConfigRepository sourceRepo;
    private NerResolveService service;

    @BeforeEach
    void setUp() {
        sourceRepo = mock(SignalSourceConfigRepository.class);
        service = new NerResolveService(sourceRepo);
    }

    private SignalSourceConfig source(Long id, String name, String displayName, boolean enabled) {
        return SignalSourceConfig.builder()
                .id(id).name(name).displayName(displayName).enabled(enabled)
                .tradeMode(SignalSourceConfig.TradeMode.AUTO)
                .routingMode(SignalSourceConfig.RoutingMode.GLOBAL)
                .build();
    }

    @Nested
    @DisplayName("resolveSources — 字串匹配")
    class ResolveTests {

        @Test
        @DisplayName("精確匹配 name → 單一候選")
        void exactNameMatch() {
            when(sourceRepo.findAll()).thenReturn(List.of(
                    source(1L, "chenge", null, true),
                    source(2L, "feiyang", null, true)));

            var result = service.resolveSources("陳哥最近勝率如何 chenge 表現");

            assertThat(result.getSourceNames()).containsExactly("chenge");
            assertThat(result.hasAmbiguousSources()).isFalse();
        }

        @Test
        @DisplayName("模糊匹配（source name 被 query 包含）→ 多候選")
        void fuzzyMatchAmbiguous() {
            when(sourceRepo.findAll()).thenReturn(List.of(
                    source(1L, "feiyang", null, true),
                    source(2L, "feiyang-vip", null, true),
                    source(3L, "chenge", null, true)));

            // query 裡「feiyang」同時出現在兩個 source name 裡
            var result = service.resolveSources("我想看 feiyang 最近表現");

            assertThat(result.getSourceNames()).containsExactlyInAnyOrder("feiyang", "feiyang-vip");
            assertThat(result.hasAmbiguousSources()).isTrue();
        }

        @Test
        @DisplayName("displayName 匹配")
        void displayNameMatch() {
            when(sourceRepo.findAll()).thenReturn(List.of(
                    source(1L, "src-internal-1", "比特幣飛揚VIP", true)));

            var result = service.resolveSources("請查 比特幣飛揚VIP 的績效");

            assertThat(result.getSourceNames()).containsExactly("src-internal-1");
        }

        @Test
        @DisplayName("不匹配 → 空結果")
        void noMatch() {
            when(sourceRepo.findAll()).thenReturn(List.of(
                    source(1L, "chenge", null, true)));

            var result = service.resolveSources("一般問候，無提到任何來源");

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("停用的 source 不參與匹配")
        void disabledSourceIgnored() {
            when(sourceRepo.findAll()).thenReturn(List.of(
                    source(1L, "chenge", null, false)));  // disabled

            var result = service.resolveSources("chenge 如何");

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("空 query → 不呼叫 DB，回空")
        void blankQuerySkipsDb() {
            var result = service.resolveSources("   ");
            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("null query → 回空")
        void nullQuery() {
            var result = service.resolveSources(null);
            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("短 query 反向匹配 — 「陳哥」query 短於 source name 但 source name 含它")
        void shortQuerySubstringOfSourceName() {
            when(sourceRepo.findAll()).thenReturn(List.of(
                    source(1L, "陳哥VIP群", null, true)));

            // query 完全等於 「陳哥」，source name 含「陳哥」→ match
            var result = service.resolveSources("陳哥");

            assertThat(result.getSourceNames()).containsExactly("陳哥VIP群");
        }

        @Test
        @DisplayName("不分大小寫")
        void caseInsensitive() {
            when(sourceRepo.findAll()).thenReturn(List.of(
                    source(1L, "ChenGe", null, true)));

            var result = service.resolveSources("CHENGE 最近");

            assertThat(result.getSourceNames()).containsExactly("ChenGe");
        }
    }

    @Nested
    @DisplayName("formatForPrompt — LLM prompt 片段")
    class FormatTests {

        @Test
        @DisplayName("空結果 → 回空字串（不污染 prompt）")
        void emptyResultYieldsEmptyString() {
            String prompt = service.formatForPrompt(NerResolveService.NerResult.empty());
            assertThat(prompt).isEmpty();
        }

        @Test
        @DisplayName("有結果 → 包含 name / mode / 使用 hint")
        void nonEmptyResultFormatted() {
            var result = new NerResolveService.NerResult(List.of(
                    source(1L, "chenge", "陳哥公共", true)));

            String prompt = service.formatForPrompt(result);

            assertThat(prompt).contains("chenge");
            assertThat(prompt).contains("陳哥公共");
            assertThat(prompt).contains("AUTO");
            assertThat(prompt).contains("必須使用此清單內的 name");
        }

        @Test
        @DisplayName("多個結果 — 全部列出")
        void multipleSourcesListed() {
            var result = new NerResolveService.NerResult(List.of(
                    source(1L, "feiyang", null, true),
                    source(2L, "feiyang-vip", null, true)));

            String prompt = service.formatForPrompt(result);

            assertThat(prompt).contains("feiyang");
            assertThat(prompt).contains("feiyang-vip");
        }
    }
}
