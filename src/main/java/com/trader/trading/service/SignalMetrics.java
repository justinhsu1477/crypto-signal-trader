package com.trader.trading.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signal 業務層面 Micrometer metrics
 *
 * 暴露的 metric：
 * - signal_image_total{result}      — 圖訊號接收計數（result=received/skip）
 * - signal_compound_total{action}   — 複合動作子訊號計數（action=close/move_sl）
 *
 * 用途：
 * - 原本只能靠 log grep 估算圖訊號 / 複合訊號量，這裡補上 Prometheus counter
 * - 觀察圖訊號佔比，評估 OCR / VLM 線路是否值得繼續投資
 * - 觀察 CLOSE / MOVE_SL 複合子訊號的觸發頻率，反推訊號源結構是否健康
 *
 * 設計：用 ConcurrentHashMap 緩存 Counter，避免每次 increment 都呼叫
 * MeterRegistry.find（Micrometer 雖然有 cache 但加一層 short-circuit 更省）。
 */
@Component
public class SignalMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> imageCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> compoundCounters = new ConcurrentHashMap<>();

    public SignalMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 記錄一筆圖觸發的訊號（source.attachment_sha256 不為 null 時呼叫）
     *
     * @param result received（成功收下） / skip（被去重/拒絕）
     */
    public void recordImageSignal(String result) {
        imageCounters.computeIfAbsent(result, r ->
                Counter.builder("signal_image_total")
                        .description("Number of image-triggered signals received")
                        .tag("result", r)
                        .register(registry)
        ).increment();
    }

    /**
     * 記錄一筆複合動作子訊號（messageId 帶 __close / __move_sl 後綴時呼叫）
     *
     * @param action close / move_sl
     */
    public void recordCompoundAction(String action) {
        compoundCounters.computeIfAbsent(action, a ->
                Counter.builder("signal_compound_total")
                        .description("Number of compound action sub-signals (CLOSE/MOVE_SL pairs)")
                        .tag("action", a)
                        .register(registry)
        ).increment();
    }
}
