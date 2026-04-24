package com.trader.chatbot.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ResponseGuard — chatbot 回覆過濾")
class ResponseGuardTest {

    private static final String FALLBACK = "抱歉，AI 客服暫時無法回應。";
    private final ResponseGuard guard = new ResponseGuard();

    @Nested
    @DisplayName("Raw JSON 偵測")
    class RawJsonDetection {

        @Test
        @DisplayName("陳哥 chatbot 實際案例 — JSON array 開頭 → 擋掉")
        void realChengeIncident() {
            String raw = "[{\"source_name\": \"chenge\", \"trade_mode\": \"AUTO\", \"is_active\": true}]";
            assertThat(guard.sanitize(raw, FALLBACK)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("純 JSON object → 擋掉")
        void pureJsonObject() {
            String raw = "{\"error\":\"db timeout\",\"code\":500}";
            assertThat(guard.sanitize(raw, FALLBACK)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("JSON array of strings → 擋掉")
        void jsonArrayOfStrings() {
            String raw = "[\"BTCUSDT\", \"ETHUSDT\"]";
            assertThat(guard.sanitize(raw, FALLBACK)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("markdown + 中文描述 + JSON snippet → 放行")
        void markdownWithJsonSnippet() {
            String text = """
                    ### 查詢結果
                    以下是您的持倉：
                    {"symbol": "BTCUSDT"}
                    """;
            assertThat(guard.sanitize(text, FALLBACK)).isEqualTo(text);
        }

        @Test
        @DisplayName("中文包起來的 JSON → 放行（LLM 已消化）")
        void wrappedJson() {
            String text = "您目前持倉：BTCUSDT LONG，詳細 {\"qty\": 0.1}";
            assertThat(guard.sanitize(text, FALLBACK)).isEqualTo(text);
        }
    }

    @Nested
    @DisplayName("錯誤訊息偵測")
    class ErrorMessageDetection {

        @ParameterizedTest
        @ValueSource(strings = {
                "ClassCastException: [Ljava.lang.Object; cannot be cast",
                "NullPointerException occurred",
                "java.sql.SQLException: connection refused"
        })
        @DisplayName("Java Exception 字串 → 擋掉")
        void javaExceptions(String errorText) {
            assertThat(guard.sanitize(errorText, FALLBACK)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("含 stack trace → 擋掉")
        void stackTrace() {
            String raw = "error\n\tat java.base/java.lang.String.substring(String.java:1)";
            assertThat(guard.sanitize(raw, FALLBACK)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("含 'Caused by:' → 擋掉")
        void causedBy() {
            String raw = "Caused by: java.lang.RuntimeException: oops";
            assertThat(guard.sanitize(raw, FALLBACK)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("MarketDataService 「取得...失敗」模式 → 擋掉")
        void marketDataFailedPattern() {
            String raw = "取得來源績效失敗: sourceName= error=ClassCastException";
            assertThat(guard.sanitize(raw, FALLBACK)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("「[績效資料載入失敗]」標記 → 擋掉")
        void loadFailedMarker() {
            String raw = "### 陳哥 績效\n- [績效資料載入失敗]\n";
            assertThat(guard.sanitize(raw, FALLBACK)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("正常「失敗」字眼（非錯誤）→ 放行")
        void legitimateFailureWord() {
            String text = "交易失敗有兩個原因：資金不足或訂單撤銷";
            assertThat(guard.sanitize(text, FALLBACK)).isEqualTo(text);
        }
    }

    @Nested
    @DisplayName("邊界")
    class EdgeCases {

        @Test
        @DisplayName("null → fallback")
        void nullInput() {
            assertThat(guard.sanitize(null, FALLBACK)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("空字串 → fallback")
        void emptyInput() {
            assertThat(guard.sanitize("", FALLBACK)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("只有空白 → fallback")
        void whitespaceOnly() {
            assertThat(guard.sanitize("   \n\t  ", FALLBACK)).isEqualTo(FALLBACK);
        }

        @Test
        @DisplayName("正常中文回覆 → 原樣放行")
        void normalChineseResponse() {
            String text = "您好，請問需要什麼協助？";
            assertThat(guard.sanitize(text, FALLBACK)).isEqualTo(text);
        }

        @Test
        @DisplayName("Markdown 結構化回覆 → 原樣放行")
        void markdownResponse() {
            String text = """
                    ### 您的持倉
                    - BTCUSDT LONG 0.1
                    - ETHUSDT SHORT 0.5
                    """;
            assertThat(guard.sanitize(text, FALLBACK)).isEqualTo(text);
        }
    }
}
