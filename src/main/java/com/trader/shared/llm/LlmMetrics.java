package com.trader.shared.llm;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * LLM 呼叫的 Micrometer metrics（W7 Observability）
 *
 * 暴露的 metric（對應 Prometheus）：
 * - chatbot_llm_calls_total{operation, model, status}   — 呼叫次數（success / fail）
 * - chatbot_llm_latency_seconds{operation, model}       — 呼叫延遲分佈
 * - chatbot_llm_tokens_total{operation, model, direction} — token 使用量（prompt / completion）
 *
 * 使用範例：
 * <pre>
 *   long start = System.nanoTime();
 *   try {
 *     String result = ...call LLM...
 *     metrics.recordSuccess("generate_content", model, start, promptTokens, responseTokens);
 *     return result;
 *   } catch (Exception e) {
 *     metrics.recordFailure("generate_content", model, start);
 *     throw e;
 *   }
 * </pre>
 */
@Component
public class LlmMetrics {

    public static final String OP_GENERATE = "generate_content";
    public static final String OP_GENERATE_WITH_HISTORY = "generate_content_with_history";
    public static final String OP_GENERATE_WITH_TOOLS = "generate_content_with_tools";
    public static final String OP_SEND_FUNCTION_RESULT = "send_function_result";
    public static final String OP_SEND_FUNCTION_RESULT_CHAIN = "send_function_result_chain";
    public static final String OP_EMBED = "embed";

    private final MeterRegistry registry;

    public LlmMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordSuccess(String operation, String model, long startNanos,
                              int promptTokens, int responseTokens) {
        long elapsed = System.nanoTime() - startNanos;
        Counter.builder("chatbot.llm.calls")
                .tag("operation", operation)
                .tag("model", nullSafe(model))
                .tag("status", "success")
                .register(registry)
                .increment();
        Timer.builder("chatbot.llm.latency")
                .tag("operation", operation)
                .tag("model", nullSafe(model))
                .publishPercentileHistogram()
                .register(registry)
                .record(elapsed, TimeUnit.NANOSECONDS);
        if (promptTokens > 0) {
            Counter.builder("chatbot.llm.tokens")
                    .tag("operation", operation)
                    .tag("model", nullSafe(model))
                    .tag("direction", "prompt")
                    .register(registry)
                    .increment(promptTokens);
        }
        if (responseTokens > 0) {
            Counter.builder("chatbot.llm.tokens")
                    .tag("operation", operation)
                    .tag("model", nullSafe(model))
                    .tag("direction", "response")
                    .register(registry)
                    .increment(responseTokens);
        }
    }

    public void recordFailure(String operation, String model, long startNanos, String reason) {
        long elapsed = System.nanoTime() - startNanos;
        Counter.builder("chatbot.llm.calls")
                .tag("operation", operation)
                .tag("model", nullSafe(model))
                .tag("status", "failure")
                .tag("reason", nullSafe(reason))
                .register(registry)
                .increment();
        Timer.builder("chatbot.llm.latency")
                .tag("operation", operation)
                .tag("model", nullSafe(model))
                .publishPercentileHistogram()
                .register(registry)
                .record(elapsed, TimeUnit.NANOSECONDS);
    }

    /** 方便的 start point — 回傳 nanoTime，呼叫端保留並在結束時傳回 recordXxx */
    public long startTimer() {
        return System.nanoTime();
    }

    private static String nullSafe(String s) {
        return s == null ? "unknown" : s;
    }
}
