package com.trader.advisor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * AI 信號評分觀測指標
 *
 * 追蹤：
 * - 完成次數（scored）
 * - 丟棄次數（discarded — 線程池滿 + 排隊滿，降級為 null）
 * - 失敗次數（failed — Gemini 錯誤/超時/解析失敗）
 * - 跳過次數（skipped — 功能關閉/非 ENTRY）
 * - 累計延遲（用於算平均）
 * - 當前排隊長度（即時查詢線程池 queue）
 *
 * 每 30 分鐘 log 一次摘要（只在有活動時印出）。
 */
@Slf4j
@Component
public class ScoringMetrics {

    private final ThreadPoolExecutor scoringExecutor;

    private final LongAdder scoredCount = new LongAdder();
    private final LongAdder discardedCount = new LongAdder();
    private final LongAdder failedCount = new LongAdder();
    private final LongAdder skippedCount = new LongAdder();
    private final LongAdder totalLatencyMs = new LongAdder();
    private final AtomicLong maxLatencyMs = new AtomicLong(0);

    public ScoringMetrics(@Qualifier("scoringExecutor") ThreadPoolExecutor scoringExecutor) {
        this.scoringExecutor = scoringExecutor;
    }

    // ========== 記錄事件 ==========

    /**
     * 評分成功完成
     */
    public void recordScored(long latencyMs) {
        scoredCount.increment();
        totalLatencyMs.add(latencyMs);
        maxLatencyMs.updateAndGet(current -> Math.max(current, latencyMs));
    }

    /**
     * 因線程池滿被丟棄
     */
    public void recordDiscarded() {
        discardedCount.increment();
        log.warn("AI 評分被丟棄: 線程池已滿 (active={}, queue={}, max={})",
                scoringExecutor.getActiveCount(),
                scoringExecutor.getQueue().size(),
                scoringExecutor.getMaximumPoolSize());
    }

    /**
     * 評分失敗（Gemini 錯誤/解析失敗）
     */
    public void recordFailed() {
        failedCount.increment();
    }

    /**
     * 跳過評分（功能關閉/非 ENTRY）
     */
    public void recordSkipped() {
        skippedCount.increment();
    }

    // ========== 查詢指標 ==========

    public long getScoredCount() {
        return scoredCount.sum();
    }

    public long getDiscardedCount() {
        return discardedCount.sum();
    }

    public long getFailedCount() {
        return failedCount.sum();
    }

    public long getSkippedCount() {
        return skippedCount.sum();
    }

    /**
     * 平均延遲（毫秒），無資料時回傳 0
     */
    public long getAvgLatencyMs() {
        long count = scoredCount.sum();
        return count > 0 ? totalLatencyMs.sum() / count : 0;
    }

    public long getMaxLatencyMs() {
        return maxLatencyMs.get();
    }

    /**
     * 當前排隊長度
     */
    public int getQueueSize() {
        return scoringExecutor.getQueue().size();
    }

    /**
     * 當前活躍線程數
     */
    public int getActiveThreads() {
        return scoringExecutor.getActiveCount();
    }

    // ========== 定時摘要 ==========

    /**
     * 每 30 分鐘印一次摘要（只在有活動時印出）
     */
    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void logSummary() {
        long scored = scoredCount.sum();
        long discarded = discardedCount.sum();
        long failed = failedCount.sum();
        long skipped = skippedCount.sum();
        long total = scored + discarded + failed + skipped;

        if (total == 0) return;  // 沒活動就不印

        log.info("AI 評分統計 [30min]: scored={}, discarded={}, failed={}, skipped={}, " +
                        "avgLatency={}ms, maxLatency={}ms, queueNow={}, activeNow={}",
                scored, discarded, failed, skipped,
                getAvgLatencyMs(), maxLatencyMs.get(),
                getQueueSize(), getActiveThreads());
    }

    /**
     * 取得結構化摘要（供 API / Dashboard 使用）
     */
    public java.util.Map<String, Object> getSummary() {
        return java.util.Map.of(
                "scored", scoredCount.sum(),
                "discarded", discardedCount.sum(),
                "failed", failedCount.sum(),
                "skipped", skippedCount.sum(),
                "avgLatencyMs", getAvgLatencyMs(),
                "maxLatencyMs", maxLatencyMs.get(),
                "queueSize", getQueueSize(),
                "activeThreads", getActiveThreads()
        );
    }
}
