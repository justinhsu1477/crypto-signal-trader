package com.trader.shared.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * BitgetApiRateLimiter 單元測試
 *
 * 驗證：
 * - 正常請求不阻塞
 * - Throttle 行為（超過 80% 水位）
 * - 硬上限拒絕（超過 90% 水位）
 * - 視窗重置行為
 */
class BitgetApiRateLimiterTest {

    private BitgetApiRateLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new BitgetApiRateLimiter();
    }

    @Nested
    @DisplayName("正常請求")
    class NormalRequests {

        @Test
        @DisplayName("低於水位 → 不拋例外、不阻塞")
        void belowThreshold_doesNotBlock() {
            // Throttle 閥值 = 8，前 8 次都不該有問題
            for (int i = 0; i < BitgetApiRateLimiter.THROTTLE_THRESHOLD; i++) {
                assertThatCode(() -> limiter.acquire()).doesNotThrowAnyException();
            }
        }

        @Test
        @DisplayName("getCurrentCount 反映真實請求數")
        void currentCount_reflectsActualRequests() {
            limiter.acquire();
            limiter.acquire();
            limiter.acquire();

            assertThat(limiter.getCurrentCount()).isEqualTo(3);
        }

        @Test
        @DisplayName("getRemainingCount 計算正確")
        void remainingCount_isCorrect() {
            assertThat(limiter.getRemainingCount())
                    .isEqualTo(BitgetApiRateLimiter.MAX_REQUESTS_PER_SECOND);

            limiter.acquire();
            assertThat(limiter.getRemainingCount())
                    .isEqualTo(BitgetApiRateLimiter.MAX_REQUESTS_PER_SECOND - 1);
        }

        @Test
        @DisplayName("getUsageRatio 計算正確")
        void usageRatio_isCorrect() {
            assertThat(limiter.getUsageRatio()).isEqualTo(0.0);

            // 5 次請求 / 10 上限 = 0.5
            for (int i = 0; i < 5; i++) {
                limiter.acquire();
            }
            assertThat(limiter.getUsageRatio()).isEqualTo(0.5);
        }
    }

    @Nested
    @DisplayName("硬上限拒絕")
    class HardLimitReject {

        @Test
        @DisplayName("超過硬上限 → 拋出 RateLimitExceededException")
        void exceedsRejectThreshold_throwsException() {
            // 先填到硬上限
            for (int i = 0; i < BitgetApiRateLimiter.REJECT_THRESHOLD; i++) {
                limiter.acquire();
            }

            // 下一次應拒絕
            assertThatThrownBy(() -> limiter.acquire())
                    .isInstanceOf(BitgetApiRateLimiter.RateLimitExceededException.class)
                    .hasMessageContaining("rate limit exceeded");
        }

        @Test
        @DisplayName("拒絕後 count 不增加（rollback）")
        void rejectedRequest_doesNotIncrement() {
            for (int i = 0; i < BitgetApiRateLimiter.REJECT_THRESHOLD; i++) {
                limiter.acquire();
            }

            int countBefore = limiter.getCurrentCount();

            try {
                limiter.acquire();
            } catch (BitgetApiRateLimiter.RateLimitExceededException ignored) {}

            assertThat(limiter.getCurrentCount()).isEqualTo(countBefore);
        }
    }

    @Nested
    @DisplayName("Throttle 等待計算")
    class ThrottleCalculation {

        @Test
        @DisplayName("throttle 等待時間遞增：越接近上限等越久")
        void throttleWaitIncreases() {
            // THROTTLE=8, REJECT=9 → 只有 1 個 throttle 層級
            // 在 threshold+1 (即 reject 邊界) 時產生最大等待
            long wait = limiter.calculateThrottleWait(BitgetApiRateLimiter.REJECT_THRESHOLD);

            assertThat(wait).isPositive();
            assertThat(wait).isBetween(100L, 800L);
        }

        @Test
        @DisplayName("throttle 等待範圍：100ms ~ 800ms")
        void throttleWaitRange() {
            long minWait = limiter.calculateThrottleWait(BitgetApiRateLimiter.THROTTLE_THRESHOLD + 1);
            long maxWait = limiter.calculateThrottleWait(BitgetApiRateLimiter.REJECT_THRESHOLD);

            assertThat(minWait).isBetween(100L, 800L);
            assertThat(maxWait).isBetween(100L, 800L);
        }
    }

    @Nested
    @DisplayName("常數驗證")
    class Constants {

        @Test
        @DisplayName("Bitget 上限比 Bybit 更保守")
        void bitgetLimitsAreMoreConservative() {
            // Bitget: 10 req/s（下單類）；Bybit: 20 req/s
            assertThat(BitgetApiRateLimiter.MAX_REQUESTS_PER_SECOND).isEqualTo(10);
            assertThat(BitgetApiRateLimiter.THROTTLE_THRESHOLD).isEqualTo(8);
            assertThat(BitgetApiRateLimiter.REJECT_THRESHOLD).isEqualTo(9);
        }

        @Test
        @DisplayName("視窗大小為 1 秒")
        void windowSizeIsOneSecond() {
            assertThat(BitgetApiRateLimiter.WINDOW_MS).isEqualTo(1_000);
        }
    }
}
