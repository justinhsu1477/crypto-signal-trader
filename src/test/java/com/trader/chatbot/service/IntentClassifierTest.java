package com.trader.chatbot.service;

import com.trader.shared.llm.LlmClient;
import com.trader.chatbot.config.ChatbotConfig;
import com.trader.shared.config.AiConfig;
import com.trader.chatbot.service.IntentClassifier.Intent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("IntentClassifier — 意圖分類")
class IntentClassifierTest {

    @Mock private LlmClient geminiService;
    @Mock private ChatbotConfig chatbotConfig;
    @Mock private AiConfig aiConfig;

    private IntentClassifier classifier;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        classifier = new IntentClassifier(geminiService, chatbotConfig, aiConfig);
        // 預設 AI 分類關閉（keyword-only 測試）
        when(chatbotConfig.isAiClassificationEnabled()).thenReturn(false);
    }

    @Nested
    @DisplayName("Keyword 匹配")
    class KeywordTests {

        @Test
        @DisplayName("帳號/餘額關鍵字 → ACCOUNT_STATUS")
        void accountStatusKeywords() {
            assertThat(classifier.classify("我的餘額多少")).isEqualTo(Intent.ACCOUNT_STATUS);
            assertThat(classifier.classify("帳號狀態")).isEqualTo(Intent.ACCOUNT_STATUS);
            assertThat(classifier.classify("我的訂閱方案")).isEqualTo(Intent.ACCOUNT_STATUS);
            assertThat(classifier.classify("balance")).isEqualTo(Intent.ACCOUNT_STATUS);
        }

        @Test
        @DisplayName("交易/損益關鍵字 → TRADE_QUERY")
        void tradeQueryKeywords() {
            assertThat(classifier.classify("最近交易紀錄")).isEqualTo(Intent.TRADE_QUERY);
            assertThat(classifier.classify("上次交易")).isEqualTo(Intent.TRADE_QUERY);
            assertThat(classifier.classify("我賺了多少")).isEqualTo(Intent.TRADE_QUERY);
            assertThat(classifier.classify("勝率多少")).isEqualTo(Intent.TRADE_QUERY);
        }

        @Test
        @DisplayName("訊號/跟單關鍵字 → SIGNAL_EXPLAIN")
        void signalExplainKeywords() {
            assertThat(classifier.classify("為什麼沒跟單")).isEqualTo(Intent.SIGNAL_EXPLAIN);
            assertThat(classifier.classify("這筆止損了")).isEqualTo(Intent.SIGNAL_EXPLAIN);
            assertThat(classifier.classify("訊號怎麼回事")).isEqualTo(Intent.SIGNAL_EXPLAIN);
        }

        @Test
        @DisplayName("操作指引關鍵字 → OPERATION_GUIDE")
        void operationGuideKeywords() {
            assertThat(classifier.classify("怎麼設定通知")).isEqualTo(Intent.OPERATION_GUIDE);
            assertThat(classifier.classify("如何綁定 api key")).isEqualTo(Intent.OPERATION_GUIDE);
            assertThat(classifier.classify("教我設定")).isEqualTo(Intent.OPERATION_GUIDE);
        }

        @Test
        @DisplayName("異常回報關鍵字 → ANOMALY_REPORT")
        void anomalyReportKeywords() {
            assertThat(classifier.classify("系統有問題")).isEqualTo(Intent.ANOMALY_REPORT);
            assertThat(classifier.classify("出現錯誤")).isEqualTo(Intent.ANOMALY_REPORT);
            assertThat(classifier.classify("功能壞掉了")).isEqualTo(Intent.ANOMALY_REPORT);
        }

        @Test
        @DisplayName("市場行情關鍵字 → MARKET_DATA")
        void marketDataKeywords() {
            assertThat(classifier.classify("BTC 多少錢")).isEqualTo(Intent.MARKET_DATA);
            assertThat(classifier.classify("比特幣現在行情")).isEqualTo(Intent.MARKET_DATA);
            assertThat(classifier.classify("目前市場趨勢")).isEqualTo(Intent.MARKET_DATA);
            assertThat(classifier.classify("funding rate")).isEqualTo(Intent.MARKET_DATA);
            assertThat(classifier.classify("我的持倉")).isEqualTo(Intent.MARKET_DATA);
            assertThat(classifier.classify("恐懼指數")).isEqualTo(Intent.MARKET_DATA);
            assertThat(classifier.classify("今天日報")).isEqualTo(Intent.MARKET_DATA);
        }

        @Test
        @DisplayName("無關鍵字 → GENERAL")
        void generalFallback() {
            assertThat(classifier.classify("你好")).isEqualTo(Intent.GENERAL);
            assertThat(classifier.classify("哈囉")).isEqualTo(Intent.GENERAL);
            assertThat(classifier.classify("今天天氣好")).isEqualTo(Intent.GENERAL);
        }

        @Test
        @DisplayName("null/空白 → GENERAL")
        void nullOrBlank() {
            assertThat(classifier.classify(null)).isEqualTo(Intent.GENERAL);
            assertThat(classifier.classify("")).isEqualTo(Intent.GENERAL);
            assertThat(classifier.classify("   ")).isEqualTo(Intent.GENERAL);
        }

        @Test
        @DisplayName("異常優先於訊號（priority）")
        void anomalyPriorityOverSignal() {
            assertThat(classifier.classify("訊號有問題")).isEqualTo(Intent.ANOMALY_REPORT);
        }
    }

    @Nested
    @DisplayName("AI 分類 fallback")
    class AIClassificationTests {

        @BeforeEach
        void enableAI() {
            when(chatbotConfig.isAiClassificationEnabled()).thenReturn(true);
            when(aiConfig.getDefaultModel()).thenReturn("gemini-2.5-flash-lite");
        }

        @Test
        @DisplayName("keyword 匹配到 → 不呼叫 AI")
        void keywordMatch_skipsAI() {
            assertThat(classifier.classify("BTC 多少錢")).isEqualTo(Intent.MARKET_DATA);
            verifyNoInteractions(geminiService);
        }

        @Test
        @DisplayName("keyword GENERAL + AI 回傳 TRADE_QUERY → 覆蓋為 TRADE_QUERY")
        void aiFallback_overridesGeneral() {
            when(geminiService.generateContentWithHistory(
                    anyString(), anyList(), anyString(), anyInt(), anyDouble(), any()))
                    .thenReturn(Optional.of("TRADE_QUERY"));

            // 用不含任何 keyword 的訊息測試 AI fallback
            Intent result = classifier.classify("飛揚老師的紀錄");

            assertThat(result).isEqualTo(Intent.TRADE_QUERY);
            verify(geminiService).generateContentWithHistory(
                    anyString(), anyList(), anyString(), eq(20), eq(0.1), eq("gemini-2.5-flash-lite"));
        }

        @Test
        @DisplayName("AI 回傳無效字串 → fallback GENERAL")
        void aiInvalidResponse_fallbackGeneral() {
            when(geminiService.generateContentWithHistory(
                    anyString(), anyList(), anyString(), anyInt(), anyDouble(), any()))
                    .thenReturn(Optional.of("UNKNOWN_INTENT"));

            Intent result = classifier.classify("你好嗎朋友");

            assertThat(result).isEqualTo(Intent.GENERAL);
        }

        @Test
        @DisplayName("AI API 失敗 → fallback GENERAL")
        void aiApiFails_fallbackGeneral() {
            when(geminiService.generateContentWithHistory(
                    anyString(), anyList(), anyString(), anyInt(), anyDouble(), any()))
                    .thenReturn(Optional.empty());

            Intent result = classifier.classify("隨便說點什麼");

            assertThat(result).isEqualTo(Intent.GENERAL);
        }

        @Test
        @DisplayName("AI 拋異常 → fallback GENERAL")
        void aiThrows_fallbackGeneral() {
            when(geminiService.generateContentWithHistory(
                    anyString(), anyList(), anyString(), anyInt(), anyDouble(), any()))
                    .thenThrow(new RuntimeException("API timeout"));

            Intent result = classifier.classify("隨便說點什麼");

            assertThat(result).isEqualTo(Intent.GENERAL);
        }

        @Test
        @DisplayName("AI 回傳帶空白和小寫 → 正確解析")
        void aiResponseWithWhitespace() {
            when(geminiService.generateContentWithHistory(
                    anyString(), anyList(), anyString(), anyInt(), anyDouble(), any()))
                    .thenReturn(Optional.of("  account_status  "));

            Intent result = classifier.classify("我現在什麼狀態");

            assertThat(result).isEqualTo(Intent.ACCOUNT_STATUS);
        }

        @Test
        @DisplayName("AI 分類開關關閉 → 不呼叫 AI")
        void aiDisabled_skipsAI() {
            when(chatbotConfig.isAiClassificationEnabled()).thenReturn(false);

            // 用不含任何 keyword 的訊息
            Intent result = classifier.classify("飛揚老師的紀錄");

            assertThat(result).isEqualTo(Intent.GENERAL);
            verifyNoInteractions(geminiService);
        }
    }
}
