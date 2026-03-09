package com.trader.shared.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RedisCacheConfig 單元測試
 *
 * 驗證：
 * - CacheManager Bean 建立成功
 * - 7 個快取區域各自 TTL 正確
 * - ttlConfig 方法產生正確配置
 * - 常數名稱正確映射
 * - GracefulCacheErrorHandler 不拋異常（Redis 掛了不影響系統）
 */
class RedisCacheConfigTest {

    private RedisCacheConfig redisCacheConfig;
    private RedisConnectionFactory connectionFactory;
    private RedisCacheManager cacheManager;

    @BeforeEach
    void setUp() {
        redisCacheConfig = new RedisCacheConfig();
        connectionFactory = mock(RedisConnectionFactory.class);
        cacheManager = redisCacheConfig.cacheManager(connectionFactory);
        // 初始化 CacheManager（Spring Container 會自動呼叫，測試環境需手動觸發）
        cacheManager.afterPropertiesSet();
    }

    @Test
    @DisplayName("CacheManager Bean 建立成功")
    void cacheManager_createsSuccessfully() {
        assertThat(cacheManager).isNotNull();
        assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
    }

    @Test
    @DisplayName("7 個快取區域全部註冊")
    void allCacheRegions_registered() {
        assertThat(cacheManager.getCacheNames())
                .containsExactlyInAnyOrder(
                        "exchangeInfo",
                        "userApiKeys",
                        "allBinanceKeys",
                        "usersWithApiKey",
                        "activeSubscribers",
                        "tradeConfig",
                        "todayLoss"
                );
    }

    @ParameterizedTest
    @DisplayName("快取區域常數名稱正確")
    @CsvSource({
            "EXCHANGE_INFO, exchangeInfo",
            "USER_API_KEYS, userApiKeys",
            "ALL_BINANCE_KEYS, allBinanceKeys",
            "USERS_WITH_API_KEY, usersWithApiKey",
            "ACTIVE_SUBSCRIBERS, activeSubscribers",
            "TRADE_CONFIG, tradeConfig",
            "TODAY_LOSS, todayLoss"
    })
    void cacheConstants_matchExpectedNames(String fieldName, String expectedValue) throws Exception {
        java.lang.reflect.Field field = RedisCacheConfig.class.getDeclaredField(fieldName);
        String actualValue = (String) field.get(null);
        assertThat(actualValue).isEqualTo(expectedValue);
    }

    @Test
    @DisplayName("ttlConfig 方法產生正確的 TTL 配置")
    void ttlConfig_producesCorrectTtl() throws Exception {
        Method ttlConfigMethod = RedisCacheConfig.class.getDeclaredMethod("ttlConfig", Duration.class);
        ttlConfigMethod.setAccessible(true);

        Duration testDuration = Duration.ofMinutes(42);
        RedisCacheConfiguration config = (RedisCacheConfiguration) ttlConfigMethod.invoke(redisCacheConfig, testDuration);

        assertThat(config).isNotNull();
        assertThat(config.getTtl()).isEqualTo(testDuration);
    }

    @ParameterizedTest
    @DisplayName("各區域 TTL 正確")
    @CsvSource({
            "exchangeInfo, 1440",      // 24h = 1440 min
            "userApiKeys, 720",        // 12h = 720 min
            "allBinanceKeys, 720",     // 12h = 720 min
            "usersWithApiKey, 10",     // 10 min
            "activeSubscribers, 5",    // 5 min
            "tradeConfig, 30",         // 30 min
            "todayLoss, 2"             // 2 min
    })
    void cacheRegion_hasCorrectTtl(String regionName, long expectedMinutes) {
        // getCacheConfigurations() 在 afterPropertiesSet 後可用
        var configs = cacheManager.getCacheConfigurations();
        assertThat(configs).containsKey(regionName);

        RedisCacheConfiguration config = configs.get(regionName);
        assertThat(config.getTtl()).isEqualTo(Duration.ofMinutes(expectedMinutes));
    }

    @Test
    @DisplayName("快取區域數量正確（不多不少）")
    void exactlySevenRegions() {
        assertThat(cacheManager.getCacheNames()).hasSize(7);
    }

    // ==================== GracefulCacheErrorHandler ====================

    @Nested
    @DisplayName("GracefulCacheErrorHandler — Redis 容錯")
    class GracefulCacheErrorHandlerTests {

        private CacheErrorHandler errorHandler;
        private Cache mockCache;

        @BeforeEach
        void setUp() {
            errorHandler = redisCacheConfig.errorHandler();
            mockCache = mock(Cache.class);
            when(mockCache.getName()).thenReturn("testCache");
        }

        @Test
        @DisplayName("errorHandler 回傳 GracefulCacheErrorHandler 實例")
        void errorHandler_returnsInstance() {
            assertThat(errorHandler).isNotNull();
            assertThat(errorHandler).isInstanceOf(RedisCacheConfig.GracefulCacheErrorHandler.class);
        }

        @Test
        @DisplayName("GET 失敗 → 不拋異常（fallback 查 DB）")
        void handleCacheGetError_doesNotThrow() {
            assertThatCode(() ->
                    errorHandler.handleCacheGetError(
                            new RuntimeException("Redis 斷線"), mockCache, "key1")
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("PUT 失敗 → 不拋異常（跳過快取寫入）")
        void handleCachePutError_doesNotThrow() {
            assertThatCode(() ->
                    errorHandler.handleCachePutError(
                            new RuntimeException("Redis 斷線"), mockCache, "key1", "value1")
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("EVICT 失敗 → 不拋異常（忽略清除失敗）")
        void handleCacheEvictError_doesNotThrow() {
            assertThatCode(() ->
                    errorHandler.handleCacheEvictError(
                            new RuntimeException("Redis 斷線"), mockCache, "key1")
            ).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("CLEAR 失敗 → 不拋異常（忽略全清失敗）")
        void handleCacheClearError_doesNotThrow() {
            assertThatCode(() ->
                    errorHandler.handleCacheClearError(
                            new RuntimeException("Redis 斷線"), mockCache)
            ).doesNotThrowAnyException();
        }
    }
}
