package com.trader.shared.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bybit V5 API 請求限流器
 *
 * Bybit 限制：20 requests / 秒 / UID（非 weight-based）
 * 超過後會回傳 403 並可能被暫時封鎖。
 *
 * 使用滑動視窗（1 秒）追蹤累計請求數，
 * 接近上限時自動等待，防止被封。
 *
 * @see <a href="https://bybit-exchange.github.io/docs/v5/rate-limit">Bybit V5 Rate Limit</a>
 */
@Slf4j
@Component
public class BybitApiRateLimiter {

    /** Bybit 每秒上限 */
    static final int MAX_REQUESTS_PER_SECOND = 20;

    /** 安全水位（80%），超過就開始 throttle */
    static final int THROTTLE_THRESHOLD = (int) (MAX_REQUESTS_PER_SECOND * 0.8);  // 16

    /** 硬上限（95%），超過直接拒絕 */
    static final int REJECT_THRESHOLD = (int) (MAX_REQUESTS_PER_SECOND * 0.95);   // 19

    /** 滑動視窗大小（毫秒）— 1 秒 */
    static final long WINDOW_MS = 1_000;

    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger currentCount = new AtomicInteger(0);

    /**
     * 請求前呼叫：檢查是否可以發送，並記錄請求數
     * Bybit 不使用 weight，每個請求計為 1
     *
     * @throws RateLimitExceededException 超過硬上限時拋出
     */
    public void acquire() {
        resetWindowIfExpired();

        int newCount = currentCount.incrementAndGet();

        // 硬上限 — 拒絕請求
        if (newCount > REJECT_THRESHOLD) {
            currentCount.decrementAndGet(); // rollback
            long remaining = remainingWindowMs();
            log.error("Bybit API 硬上限！目前 requests={}/{}, 等待 {}ms 後重置",
                    newCount, MAX_REQUESTS_PER_SECOND, remaining);
            throw new RateLimitExceededException(
                    String.format("Bybit API rate limit exceeded: %d/%d requests used. Reset in %dms",
                            newCount, MAX_REQUESTS_PER_SECOND, remaining));
        }

        // 安全水位 — 等待一段時間再繼續（throttle）
        if (newCount > THROTTLE_THRESHOLD) {
            long waitMs = calculateThrottleWait(newCount);
            log.warn("Bybit API 接近上限 requests={}/{}, throttle {}ms",
                    newCount, MAX_REQUESTS_PER_SECOND, waitMs);
            try {
                Thread.sleep(waitMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 取得目前已使用的請求數（監控用）
     */
    public int getCurrentCount() {
        resetWindowIfExpired();
        return currentCount.get();
    }

    /**
     * 取得剩餘可用請求數
     */
    public int getRemainingCount() {
        return MAX_REQUESTS_PER_SECOND - getCurrentCount();
    }

    /**
     * 取得目前視窗使用率（0.0 ~ 1.0）
     */
    public double getUsageRatio() {
        return (double) getCurrentCount() / MAX_REQUESTS_PER_SECOND;
    }

    // ========== Internal ==========

    private void resetWindowIfExpired() {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start >= WINDOW_MS) {
            if (windowStart.compareAndSet(start, now)) {
                int oldCount = currentCount.getAndSet(0);
                if (oldCount > 0) {
                    log.debug("Bybit rate limiter 視窗重置（前視窗 requests={}）", oldCount);
                }
            }
        }
    }

    private long remainingWindowMs() {
        return Math.max(0, WINDOW_MS - (System.currentTimeMillis() - windowStart.get()));
    }

    /**
     * 越接近上限等越久：線性增加 50ms ~ 500ms
     * （Bybit 視窗僅 1 秒，等待時間比 Binance 短）
     */
    long calculateThrottleWait(int currentUsed) {
        double ratio = (double) (currentUsed - THROTTLE_THRESHOLD)
                / (REJECT_THRESHOLD - THROTTLE_THRESHOLD);
        return (long) (50 + ratio * 450);
    }

    /**
     * 超過 Bybit API 速率限制時拋出
     */
    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) {
            super(message);
        }
    }
}
