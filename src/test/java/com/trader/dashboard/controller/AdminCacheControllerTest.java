package com.trader.dashboard.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static com.trader.shared.config.RedisCacheConfig.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminCacheController")
class AdminCacheControllerTest {

    @Mock
    private CacheManager cacheManager;

    @InjectMocks
    private AdminCacheController controller;

    @Nested
    @DisplayName("evictZone — 清除指定快取區域")
    class EvictZone {

        @Test
        @DisplayName("成功清除指定區域")
        void clearsSpecificZone() {
            Cache mockCache = mock(Cache.class);
            when(cacheManager.getCache(TRADE_CONFIG)).thenReturn(mockCache);

            ResponseEntity<Map<String, Object>> response = controller.evictZone(TRADE_CONFIG);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("zone", TRADE_CONFIG);
            verify(mockCache).clear();
        }

        @Test
        @DisplayName("不存在的區域回傳 400")
        void returnsErrorForUnknownZone() {
            when(cacheManager.getCache("nonExistent")).thenReturn(null);

            ResponseEntity<Map<String, Object>> response = controller.evictZone("nonExistent");

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody()).containsKey("error");
        }
    }

    @Nested
    @DisplayName("evictAll — 清除全部快取區域")
    class EvictAll {

        @Test
        @DisplayName("清除所有已註冊區域")
        void clearsAllZones() {
            String[] allZones = {
                    EXCHANGE_INFO, USER_API_KEYS, ALL_BINANCE_KEYS,
                    USERS_WITH_API_KEY, ACTIVE_SUBSCRIBERS, TRADE_CONFIG, TODAY_LOSS
            };
            for (String zone : allZones) {
                Cache mockCache = mock(Cache.class);
                when(cacheManager.getCache(zone)).thenReturn(mockCache);
            }

            ResponseEntity<Map<String, Object>> response = controller.evictAll();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsEntry("clearedZones", allZones.length);
            for (String zone : allZones) {
                verify(cacheManager.getCache(zone)).clear();
            }
        }
    }

    @Nested
    @DisplayName("listZones — 列出快取區域")
    class ListZones {

        @Test
        @DisplayName("回傳所有區域名稱")
        void returnsAllZoneNames() {
            ResponseEntity<Map<String, Object>> response = controller.listZones();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).containsKey("zones");
            assertThat(response.getBody()).containsEntry("total", 7);
        }
    }
}
