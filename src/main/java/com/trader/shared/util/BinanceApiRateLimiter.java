package com.trader.shared.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Binance API 請求權重限流器
 *
 * Binance Futures API 限制：2400 weight / 分鐘 / IP
 * 超過後會被 ban 2-10 分鐘。
 *
 * 使用滑動視窗（1 分鐘）追蹤累計 weight，
 * 接近上限時自動等待，防止 IP 被封。
 *
 * @see <a href="https://binance-docs.github.io/apidocs/futures/en/#limits">Binance API Limits</a>
 */
@Slf4j
@Component
public class BinanceApiRateLimiter {

    /** Binance 每分鐘上限 */
    static final int MAX_WEIGHT_PER_MINUTE = 2400;

    /** 安全水位（80%），超過就開始 throttle */
    static final int THROTTLE_THRESHOLD = (int) (MAX_WEIGHT_PER_MINUTE * 0.8);  // 1920

    /** 硬上限（95%），超過直接拒絕 */
    static final int REJECT_THRESHOLD = (int) (MAX_WEIGHT_PER_MINUTE * 0.95);   // 2280

    /** 滑動視窗大小（毫秒） */
    static final long WINDOW_MS = 60_000;

    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger currentWeight = new AtomicInteger(0);

    /**
     * 請求前呼叫：檢查是否可以發送，並記錄 weight
     *
     * @param weight 此次請求的 API weight（大部分是 1-5，下單約 1）
     * @throws RateLimitExceededException 超過硬上限時拋出
     */
    public void acquire(int weight) {
        resetWindowIfExpired();

        int newWeight = currentWeight.addAndGet(weight);

        // 硬上限 — 拒絕請求
        if (newWeight > REJECT_THRESHOLD) {
            currentWeight.addAndGet(-weight); // rollback
            long remaining = remainingWindowMs();
            log.error("🔴 Binance API 硬上限！目前 weight={}/{}, 等待 {}ms 後重置",
                    newWeight, MAX_WEIGHT_PER_MINUTE, remaining);
            throw new RateLimitExceededException(
                    String.format("Binance API rate limit exceeded: %d/%d weight used. Reset in %dms",
                            newWeight, MAX_WEIGHT_PER_MINUTE, remaining));
        }

        // 安全水位 — 等待一段時間再繼續（throttle）
        if (newWeight > THROTTLE_THRESHOLD) {
            long waitMs = calculateThrottleWait(newWeight);
            log.warn("⚠️ Binance API 接近上限 weight={}/{}, throttle {}ms",
                    newWeight, MAX_WEIGHT_PER_MINUTE, waitMs);
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 簡化版：預設 weight = 1
     */
    public void acquire() {
        acquire(1);
    }

    /**
     * 從 Binance 回應 Header 更新實際使用量
     * Binance 回傳: X-MBX-USED-WEIGHT-1M
     *
     * @param actualWeight Binance 回報的已用 weight
     */
    public void updateFromHeader(int actualWeight) {
        resetWindowIfExpired();
        currentWeight.set(actualWeight);
        if (actualWeight > THROTTLE_THRESHOLD) {
            log.warn("Binance 回報 weight={}/{}", actualWeight, MAX_WEIGHT_PER_MINUTE);
        }
    }

    /**
     * 取得目前已使用的 weight（監控用）
     */
    public int getCurrentWeight() {
        resetWindowIfExpired();
        return currentWeight.get();
    }

    /**
     * 取得剩餘可用 weight
     */
    public int getRemainingWeight() {
        return MAX_WEIGHT_PER_MINUTE - getCurrentWeight();
    }

    /**
     * 取得目前視窗使用率（0.0 ~ 1.0）
     */
    public double getUsageRatio() {
        return (double) getCurrentWeight() / MAX_WEIGHT_PER_MINUTE;
    }

    // ========== Internal ==========

    private void resetWindowIfExpired() {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start >= WINDOW_MS) {
            if (windowStart.compareAndSet(start, now)) {
                int oldWeight = currentWeight.getAndSet(0);
                if (oldWeight > 0) {
                    log.debug("Binance rate limiter 視窗重置（前視窗 weight={}）", oldWeight);
                }
            }
        }
    }

    private long remainingWindowMs() {
        return Math.max(0, WINDOW_MS - (System.currentTimeMillis() - windowStart.get()));
    }

    /**
     * 越接近上限等越久：線性增加 100ms ~ 2000ms
     */
    long calculateThrottleWait(int currentUsed) {
        double ratio = (double) (currentUsed - THROTTLE_THRESHOLD)
                / (REJECT_THRESHOLD - THROTTLE_THRESHOLD);
        return (long) (100 + ratio * 1900);
    }

    /**
     * 超過 Binance API 速率限制時拋出
     */
    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) {
            super(message);
        }
    }
}
