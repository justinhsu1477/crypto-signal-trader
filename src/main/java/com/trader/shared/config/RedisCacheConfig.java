package com.trader.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;
import java.util.Map;

/**
 * Redis 快取配置 — 各區域獨立 TTL
 *
 * 快取策略：Cache Aside Pattern
 * - 讀取：先查 Redis → miss → 查 DB → 寫入 Redis → 回傳
 * - 寫入：更新 DB → 刪除 Redis（@CacheEvict）
 *
 * 面試重點：
 * 1. 為什麼不用 Write Through？→ 讀多寫少，Cache Aside 更適合
 * 2. 為什麼選 Redis 不選 Caffeine？→ 多實例共享 + 可觀測性 + 持久化
 * 3. TTL 設計原則：不常變 → 長 TTL、用戶配置 → 中 TTL + evict、業務統計 → 短 TTL
 * 4. 不快取什麼？→ 即時價格、帳戶餘額、持倉數量（風控正確性 > 效能）
 * 5. Redis 掛了怎麼辦？→ GracefulCacheErrorHandler 吞掉異常，fallback 直接查 DB
 */
@Slf4j
@Configuration
@EnableCaching
public class RedisCacheConfig implements CachingConfigurer {

    // ==================== 快取區域名稱常數 ====================
    public static final String EXCHANGE_INFO = "exchangeInfo";
    public static final String USER_API_KEYS = "userApiKeys";
    public static final String ALL_BINANCE_KEYS = "allBinanceKeys";
    public static final String USERS_WITH_API_KEY = "usersWithApiKey";
    public static final String ACTIVE_SUBSCRIBERS = "activeSubscribers";
    public static final String TRADE_CONFIG = "tradeConfig";
    public static final String TODAY_LOSS = "todayLoss";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        Map<String, RedisCacheConfiguration> cacheConfigs = Map.of(
                EXCHANGE_INFO,       ttlConfig(Duration.ofHours(24)),    // 交易所資訊幾乎不變
                USER_API_KEYS,       ttlConfig(Duration.ofHours(12)),    // 用戶 API Key（寫入即清除）
                ALL_BINANCE_KEYS,    ttlConfig(Duration.ofHours(12)),    // 批量 API Key（寫入即清除）
                USERS_WITH_API_KEY,  ttlConfig(Duration.ofMinutes(10)),  // 擁有 Key 的用戶 Set
                ACTIVE_SUBSCRIBERS,  ttlConfig(Duration.ofMinutes(5)),   // 有效訂閱用戶 Set
                TRADE_CONFIG,        ttlConfig(Duration.ofMinutes(30)),  // 用戶交易參數（寫入即清除）
                TODAY_LOSS,          ttlConfig(Duration.ofMinutes(2))    // 今日虧損（平倉即清除）
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(ttlConfig(Duration.ofMinutes(10)))   // 未定義的區域預設 10 分鐘
                .withInitialCacheConfigurations(cacheConfigs)
                .build();
    }

    // ==================== Graceful Degradation ====================

    /**
     * Redis 容錯處理器 — Redis 掛了不影響交易系統
     *
     * 生產安全：Redis 斷線時，@Cacheable 直接 fallback 查 DB，@CacheEvict 忽略錯誤
     * 避免 RedisConnectionFailureException 級聯導致整個交易流程中斷
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return new GracefulCacheErrorHandler();
    }

    static class GracefulCacheErrorHandler implements CacheErrorHandler {

        @Override
        public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
            log.warn("Redis GET 失敗，fallback 查 DB [cache={}, key={}]: {}",
                    cache.getName(), key, exception.getMessage());
        }

        @Override
        public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
            log.warn("Redis PUT 失敗，跳過快取 [cache={}, key={}]: {}",
                    cache.getName(), key, exception.getMessage());
        }

        @Override
        public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
            log.warn("Redis EVICT 失敗，忽略 [cache={}, key={}]: {}",
                    cache.getName(), key, exception.getMessage());
        }

        @Override
        public void handleCacheClearError(RuntimeException exception, Cache cache) {
            log.warn("Redis CLEAR 失敗，忽略 [cache={}]: {}",
                    cache.getName(), exception.getMessage());
        }
    }

    // ==================== TTL 配置工廠 ====================

    /**
     * 建立指定 TTL 的快取配置
     * - JSON 序列化（便於 redis-cli 檢視）
     * - 快取 null 值（防止快取穿透）
     */
    private RedisCacheConfiguration ttlConfig(Duration ttl) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .serializeValuesWith(
                        SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer()));
    }
}
