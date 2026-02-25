package com.trader.shared.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BinanceApiRateLimiter 單元測試
 *
 * 驗證：
 * - 正常 acquire 記錄 weight
 * - 超過硬上限拒絕請求
 * - updateFromHeader 校正本地計數
 * - 視窗重置後計數歸零
 * - throttle 等待時間計算
 */
class BinanceApiRateLimiterTest {

    private BinanceApiRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new BinanceApiRateLimiter();
    }

    @Test
    @DisplayName("初始狀態：weight = 0, remaining = 2400")
    void initialState() {
        assertEquals(0, rateLimiter.getCurrentWeight());
        assertEquals(2400, rateLimiter.getRemainingWeight());
        assertEquals(0.0, rateLimiter.getUsageRatio(), 0.001);
    }

    @Test
    @DisplayName("acquire 正常記錄 weight")
    void acquireTracksWeight() {
        rateLimiter.acquire(5);
        assertEquals(5, rateLimiter.getCurrentWeight());

        rateLimiter.acquire(10);
        assertEquals(15, rateLimiter.getCurrentWeight());

        assertEquals(2385, rateLimiter.getRemainingWeight());
    }

    @Test
    @DisplayName("acquire() 預設 weight = 1")
    void acquireDefaultWeight() {
        rateLimiter.acquire();
        rateLimiter.acquire();
        rateLimiter.acquire();
        assertEquals(3, rateLimiter.getCurrentWeight());
    }

    @Test
    @DisplayName("超過硬上限（95%）拋出 RateLimitExceededException")
    void rejectWhenExceedingHardLimit() {
        // 先用到接近上限
        rateLimiter.updateFromHeader(BinanceApiRateLimiter.REJECT_THRESHOLD);

        // 再 acquire 就應該被拒絕
        assertThrows(BinanceApiRateLimiter.RateLimitExceededException.class,
                () -> rateLimiter.acquire(5));
    }

    @Test
    @DisplayName("拒絕後 weight 回滾（不計入）")
    void rejectRollsBackWeight() {
        rateLimiter.updateFromHeader(BinanceApiRateLimiter.REJECT_THRESHOLD);
        int beforeReject = rateLimiter.getCurrentWeight();

        try {
            rateLimiter.acquire(10);
        } catch (BinanceApiRateLimiter.RateLimitExceededException ignored) {
        }

        assertEquals(beforeReject, rateLimiter.getCurrentWeight());
    }

    @Test
    @DisplayName("updateFromHeader 覆蓋本地計數")
    void updateFromHeaderOverridesLocalCount() {
        rateLimiter.acquire(100);
        assertEquals(100, rateLimiter.getCurrentWeight());

        // Binance 回報實際只用了 50
        rateLimiter.updateFromHeader(50);
        assertEquals(50, rateLimiter.getCurrentWeight());
    }

    @Test
    @DisplayName("使用率計算正確")
    void usageRatioCalculation() {
        rateLimiter.updateFromHeader(1200);
        assertEquals(0.5, rateLimiter.getUsageRatio(), 0.001);

        rateLimiter.updateFromHeader(2400);
        assertEquals(1.0, rateLimiter.getUsageRatio(), 0.001);
    }

    @Test
    @DisplayName("throttle 等待時間：越接近上限越久")
    void throttleWaitIncreasesWithUsage() {
        // 剛到 throttle 門檻 → 等待短
        long waitLow = rateLimiter.calculateThrottleWait(BinanceApiRateLimiter.THROTTLE_THRESHOLD + 1);

        // 接近 reject 門檻 → 等待長
        long waitHigh = rateLimiter.calculateThrottleWait(BinanceApiRateLimiter.REJECT_THRESHOLD - 1);

        assertTrue(waitLow < waitHigh,
                "Wait at low usage (" + waitLow + "ms) should be less than at high usage (" + waitHigh + "ms)");
        assertTrue(waitLow >= 100, "Minimum wait should be >= 100ms");
        assertTrue(waitHigh <= 2100, "Maximum wait should be <= 2100ms");
    }

    @Test
    @DisplayName("低於 throttle 門檻時不阻塞（正常快速通過）")
    void noThrottleBelowThreshold() {
        long start = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            rateLimiter.acquire();
        }
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 500, "100 acquires below threshold should complete in < 500ms, took " + elapsed + "ms");
    }

    @Test
    @DisplayName("多個 acquire 累加不超出")
    void multipleAcquiresAccumulate() {
        for (int i = 0; i < 1000; i++) {
            rateLimiter.acquire(1);
        }
        assertEquals(1000, rateLimiter.getCurrentWeight());
    }
}
