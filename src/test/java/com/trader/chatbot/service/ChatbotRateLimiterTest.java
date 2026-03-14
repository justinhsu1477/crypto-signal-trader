package com.trader.chatbot.service;

import com.trader.chatbot.config.ChatbotConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@DisplayName("ChatbotRateLimiter — 限流")
class ChatbotRateLimiterTest {

    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ChatbotConfig chatbotConfig;

    private ChatbotRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        rateLimiter = new ChatbotRateLimiter(redisTemplate, chatbotConfig);
    }

    @Test
    @DisplayName("正常流量 → 允許")
    void normalTrafficAllowed() {
        when(chatbotConfig.getRateLimitPerMinute()).thenReturn(5);
        when(chatbotConfig.getRateLimitPerDay()).thenReturn(50);
        when(valueOps.increment(anyString())).thenReturn(1L);

        assertThat(rateLimiter.isAllowed("u1")).isTrue();
    }

    @Test
    @DisplayName("超過每分鐘限制 → 拒絕")
    void perMinuteLimitExceeded() {
        when(chatbotConfig.getRateLimitPerMinute()).thenReturn(5);
        when(valueOps.increment("chatbot:rate:min:u1")).thenReturn(6L);

        assertThat(rateLimiter.isAllowed("u1")).isFalse();
    }

    @Test
    @DisplayName("超過每日限制 → 拒絕")
    void perDayLimitExceeded() {
        when(chatbotConfig.getRateLimitPerMinute()).thenReturn(5);
        when(chatbotConfig.getRateLimitPerDay()).thenReturn(50);
        when(valueOps.increment("chatbot:rate:min:u1")).thenReturn(1L);
        when(valueOps.increment("chatbot:rate:day:u1")).thenReturn(51L);

        assertThat(rateLimiter.isAllowed("u1")).isFalse();
    }

    @Test
    @DisplayName("Redis 故障 → graceful degradation（放行）")
    void redisFailureAllows() {
        when(redisTemplate.opsForValue()).thenThrow(new RuntimeException("Redis down"));

        assertThat(rateLimiter.isAllowed("u1")).isTrue();
    }

    @Test
    @DisplayName("getRateLimitMessage 包含限制數字")
    void rateLimitMessage() {
        when(chatbotConfig.getRateLimitPerMinute()).thenReturn(5);
        when(chatbotConfig.getRateLimitPerDay()).thenReturn(50);

        assertThat(rateLimiter.getRateLimitMessage()).contains("5").contains("50");
    }
}
