package com.trader.shared.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LlmMetrics 單元測試
 *
 * 用 SimpleMeterRegistry 驗證 Counter / Timer 標籤化、累加、隔離。
 * 涵蓋 recordSuccess / recordFailure / startTimer / nullSafe model & reason。
 */
@DisplayName("LlmMetrics — chatbot.llm.* Prometheus 指標")
class LlmMetricsTest {

    private SimpleMeterRegistry registry;
    private LlmMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new LlmMetrics(registry);
    }

    @Nested
    @DisplayName("recordSuccess")
    class RecordSuccessTests {

        @Test
        @DisplayName("基本成功呼叫 → chatbot.llm.calls{status=success} +1")
        void incrementsSuccessCounter() {
            long start = metrics.startTimer();
            metrics.recordSuccess(LlmMetrics.OP_GENERATE, "gemini-2.5-flash", start, 100, 50);

            Counter c = registry.find("chatbot.llm.calls")
                    .tag("operation", LlmMetrics.OP_GENERATE)
                    .tag("model", "gemini-2.5-flash")
                    .tag("status", "success")
                    .counter();
            assertThat(c).isNotNull();
            assertThat(c.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("成功呼叫會記錄 chatbot.llm.latency timer")
        void recordsLatencyTimer() {
            long start = metrics.startTimer();
            metrics.recordSuccess(LlmMetrics.OP_GENERATE, "gemini-2.5-flash", start, 10, 10);

            Timer t = registry.find("chatbot.llm.latency")
                    .tag("operation", LlmMetrics.OP_GENERATE)
                    .tag("model", "gemini-2.5-flash")
                    .timer();
            assertThat(t).isNotNull();
            assertThat(t.count()).isEqualTo(1);
            assertThat(t.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isGreaterThan(0);
        }

        @Test
        @DisplayName("promptTokens > 0 → 計入 prompt direction counter")
        void recordsPromptTokensWhenPositive() {
            long start = metrics.startTimer();
            metrics.recordSuccess(LlmMetrics.OP_GENERATE, "gemini-2.5-flash", start, 250, 0);

            Counter c = registry.find("chatbot.llm.tokens")
                    .tag("operation", LlmMetrics.OP_GENERATE)
                    .tag("model", "gemini-2.5-flash")
                    .tag("direction", "prompt")
                    .counter();
            assertThat(c).isNotNull();
            assertThat(c.count()).isEqualTo(250.0);
        }

        @Test
        @DisplayName("responseTokens > 0 → 計入 response direction counter")
        void recordsResponseTokensWhenPositive() {
            long start = metrics.startTimer();
            metrics.recordSuccess(LlmMetrics.OP_GENERATE, "gemini-2.5-flash", start, 0, 75);

            Counter c = registry.find("chatbot.llm.tokens")
                    .tag("operation", LlmMetrics.OP_GENERATE)
                    .tag("model", "gemini-2.5-flash")
                    .tag("direction", "response")
                    .counter();
            assertThat(c).isNotNull();
            assertThat(c.count()).isEqualTo(75.0);
        }

        @Test
        @DisplayName("promptTokens / responseTokens 均為 0 → 不建立 tokens counter")
        void skipsTokenCountersWhenZero() {
            long start = metrics.startTimer();
            metrics.recordSuccess(LlmMetrics.OP_GENERATE, "gemini-2.5-flash", start, 0, 0);

            assertThat(registry.find("chatbot.llm.tokens").counter()).isNull();
        }

        @Test
        @DisplayName("model = null → 標籤化為 \"unknown\"（不會 NPE）")
        void nullModelTaggedAsUnknown() {
            long start = metrics.startTimer();
            metrics.recordSuccess(LlmMetrics.OP_EMBED, null, start, 10, 5);

            Counter c = registry.find("chatbot.llm.calls")
                    .tag("operation", LlmMetrics.OP_EMBED)
                    .tag("model", "unknown")
                    .tag("status", "success")
                    .counter();
            assertThat(c).isNotNull();
            assertThat(c.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("多次呼叫同 operation + model → counter 累加")
        void multipleCallsAccumulate() {
            for (int i = 0; i < 5; i++) {
                metrics.recordSuccess(LlmMetrics.OP_GENERATE, "gemini-2.5-flash",
                        metrics.startTimer(), 100, 50);
            }
            Counter c = registry.find("chatbot.llm.calls")
                    .tag("operation", LlmMetrics.OP_GENERATE)
                    .tag("model", "gemini-2.5-flash")
                    .tag("status", "success")
                    .counter();
            assertThat(c.count()).isEqualTo(5.0);
            // tokens 累加
            Counter promptC = registry.find("chatbot.llm.tokens")
                    .tag("direction", "prompt").counter();
            assertThat(promptC.count()).isEqualTo(500.0);
            Counter respC = registry.find("chatbot.llm.tokens")
                    .tag("direction", "response").counter();
            assertThat(respC.count()).isEqualTo(250.0);
        }

        @Test
        @DisplayName("不同 operation 走不同 counter（label isolation）")
        void differentOperationsIsolated() {
            metrics.recordSuccess(LlmMetrics.OP_GENERATE, "gemini-2.5-flash",
                    metrics.startTimer(), 100, 50);
            metrics.recordSuccess(LlmMetrics.OP_EMBED, "gemini-2.5-flash",
                    metrics.startTimer(), 30, 0);

            Counter genC = registry.find("chatbot.llm.calls")
                    .tag("operation", LlmMetrics.OP_GENERATE)
                    .tag("status", "success")
                    .counter();
            Counter embC = registry.find("chatbot.llm.calls")
                    .tag("operation", LlmMetrics.OP_EMBED)
                    .tag("status", "success")
                    .counter();
            assertThat(genC.count()).isEqualTo(1.0);
            assertThat(embC.count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("recordFailure")
    class RecordFailureTests {

        @Test
        @DisplayName("失敗呼叫 → chatbot.llm.calls{status=failure, reason=X} +1")
        void incrementsFailureCounterWithReason() {
            long start = metrics.startTimer();
            metrics.recordFailure(LlmMetrics.OP_GENERATE, "gemini-2.5-flash", start, "rate_limit");

            Counter c = registry.find("chatbot.llm.calls")
                    .tag("operation", LlmMetrics.OP_GENERATE)
                    .tag("model", "gemini-2.5-flash")
                    .tag("status", "failure")
                    .tag("reason", "rate_limit")
                    .counter();
            assertThat(c).isNotNull();
            assertThat(c.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("失敗也會記 latency timer（觀察失敗呼叫多快炸）")
        void failureAlsoRecordsLatency() {
            long start = metrics.startTimer();
            metrics.recordFailure(LlmMetrics.OP_GENERATE, "gemini-2.5-flash", start, "timeout");

            Timer t = registry.find("chatbot.llm.latency")
                    .tag("operation", LlmMetrics.OP_GENERATE)
                    .timer();
            assertThat(t).isNotNull();
            assertThat(t.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("reason = null → 標籤化為 \"unknown\"（不會 NPE）")
        void nullReasonTaggedAsUnknown() {
            metrics.recordFailure(LlmMetrics.OP_GENERATE, "gemini-2.5-flash",
                    metrics.startTimer(), null);

            Counter c = registry.find("chatbot.llm.calls")
                    .tag("status", "failure")
                    .tag("reason", "unknown")
                    .counter();
            assertThat(c).isNotNull();
            assertThat(c.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("model + reason 都為 null → 雙重 nullSafe")
        void bothNullsHandled() {
            metrics.recordFailure(LlmMetrics.OP_EMBED, null, metrics.startTimer(), null);

            Counter c = registry.find("chatbot.llm.calls")
                    .tag("model", "unknown")
                    .tag("reason", "unknown")
                    .counter();
            assertThat(c).isNotNull();
            assertThat(c.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("不同 reason 走不同 failure counter（區分錯誤類型）")
        void differentReasonsIsolated() {
            metrics.recordFailure(LlmMetrics.OP_GENERATE, "gemini-2.5-flash",
                    metrics.startTimer(), "rate_limit");
            metrics.recordFailure(LlmMetrics.OP_GENERATE, "gemini-2.5-flash",
                    metrics.startTimer(), "rate_limit");
            metrics.recordFailure(LlmMetrics.OP_GENERATE, "gemini-2.5-flash",
                    metrics.startTimer(), "timeout");

            assertThat(registry.find("chatbot.llm.calls")
                    .tag("reason", "rate_limit").counter().count()).isEqualTo(2.0);
            assertThat(registry.find("chatbot.llm.calls")
                    .tag("reason", "timeout").counter().count()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("startTimer")
    class StartTimerTests {

        @Test
        @DisplayName("startTimer 回傳的值會隨時間遞增")
        void increasingNanoTime() throws InterruptedException {
            long t1 = metrics.startTimer();
            Thread.sleep(1);
            long t2 = metrics.startTimer();
            assertThat(t2).isGreaterThan(t1);
        }

        @Test
        @DisplayName("startTimer 與 recordSuccess 配合 → latency >= 0")
        void timerAndRecordIntegration() {
            long start = metrics.startTimer();
            metrics.recordSuccess(LlmMetrics.OP_GENERATE, "gemini-2.5-flash", start, 0, 0);

            Timer t = registry.find("chatbot.llm.latency").timer();
            assertThat(t.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS))
                    .isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    @DisplayName("六個 operation 常數對齊 Prometheus 標籤命名")
    class OperationConstantsTests {

        @Test
        @DisplayName("所有 OP_* 常數值與註解中的命名規範一致")
        void operationConstantValuesMatchDoc() {
            assertThat(LlmMetrics.OP_GENERATE).isEqualTo("generate_content");
            assertThat(LlmMetrics.OP_GENERATE_WITH_HISTORY).isEqualTo("generate_content_with_history");
            assertThat(LlmMetrics.OP_GENERATE_WITH_TOOLS).isEqualTo("generate_content_with_tools");
            assertThat(LlmMetrics.OP_SEND_FUNCTION_RESULT).isEqualTo("send_function_result");
            assertThat(LlmMetrics.OP_SEND_FUNCTION_RESULT_CHAIN).isEqualTo("send_function_result_chain");
            assertThat(LlmMetrics.OP_EMBED).isEqualTo("embed");
        }
    }
}
