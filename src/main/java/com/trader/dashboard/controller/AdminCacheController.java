package com.trader.dashboard.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.trader.shared.config.RedisCacheConfig.*;

/**
 * Admin 快取管理 API
 *
 * 路徑 /api/admin/** 已被 AuthConfig hasRole("ADMIN") 保護
 *
 * 功能：
 * - DELETE /api/admin/cache/{zone}    — 清除指定快取區域
 * - DELETE /api/admin/cache           — 清除所有快取區域
 * - GET    /api/admin/cache/zones     — 列出所有快取區域名稱
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/cache")
@RequiredArgsConstructor
public class AdminCacheController {

    private static final String[] ALL_ZONES = {
            EXCHANGE_INFO, USER_API_KEYS, ALL_BINANCE_KEYS,
            USERS_WITH_API_KEY, ACTIVE_SUBSCRIBERS, TRADE_CONFIG, TODAY_LOSS
    };

    private final CacheManager cacheManager;

    /**
     * 清除指定快取區域
     */
    @DeleteMapping("/{zone}")
    public ResponseEntity<Map<String, Object>> evictZone(@PathVariable String zone) {
        Cache cache = cacheManager.getCache(zone);
        if (cache == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "快取區域不存在: " + zone
            ));
        }

        cache.clear();
        log.info("Admin 手動清除快取區域: {}", zone);

        return ResponseEntity.ok(Map.of(
                "message", "快取已清除",
                "zone", zone
        ));
    }

    /**
     * 清除所有快取區域
     */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> evictAll() {
        int cleared = 0;
        for (String zone : ALL_ZONES) {
            Cache cache = cacheManager.getCache(zone);
            if (cache != null) {
                cache.clear();
                cleared++;
            }
        }

        log.info("Admin 手動清除全部快取區域: {} 個", cleared);

        return ResponseEntity.ok(Map.of(
                "message", "全部快取已清除",
                "clearedZones", cleared
        ));
    }

    /**
     * 列出所有快取區域名稱
     */
    @GetMapping("/zones")
    public ResponseEntity<Map<String, Object>> listZones() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("zones", ALL_ZONES);
        response.put("total", ALL_ZONES.length);
        return ResponseEntity.ok(response);
    }
}
