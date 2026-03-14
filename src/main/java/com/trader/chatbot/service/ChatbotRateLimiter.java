package com.trader.chatbot.service;

import com.trader.chatbot.config.ChatbotConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 客服限流器 — Redis 滑動窗口
 *
 * 限制每位用戶的提問頻率：
 * - 每分鐘上限（預設 5）
 * - 每日上限（預設 50）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatbotRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final ChatbotConfig chatbotConfig;

    private static final String KEY_PREFIX_MIN = "chatbot:rate:min:";
    private static final String KEY_PREFIX_DAY = "chatbot:rate:day:";

    /**
     * 檢查是否允許（先檢查上限，通過後才遞增計數）
     *
     * 避免被拒絕的請求也消耗計數配額
     */
    public boolean isAllowed(String userId) {
        try {
            String minKey = KEY_PREFIX_MIN + userId;
            String dayKey = KEY_PREFIX_DAY + userId;

            // 先檢查當前計數是否已超限（不遞增）
            String minVal = redisTemplate.opsForValue().get(minKey);
            if (minVal != null && Long.parseLong(minVal) >= chatbotConfig.getRateLimitPerMinute()) {
                log.info("客服限流（分鐘）: userId={} count={}", userId, minVal);
                return false;
            }

            String dayVal = redisTemplate.opsForValue().get(dayKey);
            if (dayVal != null && Long.parseLong(dayVal) >= chatbotConfig.getRateLimitPerDay()) {
                log.info("客服限流（每日）: userId={} count={}", userId, dayVal);
                return false;
            }

            // 通過檢查 → 遞增計數
            Long minCount = redisTemplate.opsForValue().increment(minKey);
            if (minCount != null && minCount == 1) {
                redisTemplate.expire(minKey, Duration.ofSeconds(60));
            }

            Long dayCount = redisTemplate.opsForValue().increment(dayKey);
            if (dayCount != null && dayCount == 1) {
                redisTemplate.expire(dayKey, Duration.ofSeconds(86400));
            }

            return true;
        } catch (Exception e) {
            // Redis 故障時放行（graceful degradation）
            log.warn("客服限流 Redis 異常，放行: {}", e.getMessage());
            return true;
        }
    }

    public String getRateLimitMessage() {
        return String.format("您的提問頻率過高，請稍後再試。\n每分鐘最多 %d 則，每日最多 %d 則。",
                chatbotConfig.getRateLimitPerMinute(), chatbotConfig.getRateLimitPerDay());
    }
}
