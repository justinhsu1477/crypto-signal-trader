package com.trader.chatbot.service;

import com.trader.chatbot.service.IntentClassifier.Intent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IntentClassifier — 意圖分類")
class IntentClassifierTest {

    private final IntentClassifier classifier = new IntentClassifier();

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
        // 「問題」屬於 ANOMALY，「訊號」屬於 SIGNAL，ANOMALY 優先
        assertThat(classifier.classify("訊號有問題")).isEqualTo(Intent.ANOMALY_REPORT);
    }
}
