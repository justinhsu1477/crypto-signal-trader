package com.trader.shared.controller;

import com.trader.shared.util.BinanceApiRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Health Check 端點
 *
 * GET /api/health       — Docker 探活（輕量，永遠回 200）
 * GET /api/health/deep  — 深度檢查（DB 連線 + Binance API 配額）
 *
 * 無認證、無副作用。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    private final DataSource dataSource;
    private final BinanceApiRateLimiter binanceApiRateLimiter;

    /**
     * 輕量探活 — Docker / Load Balancer 用
     * 不做任何 I/O，永遠快速回應
     */
    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    /**
     * 深度健康檢查 — 監控面板 / 人工檢查用
     * 檢查：
     *   1. 資料庫連線（SELECT 1）
     *   2. Binance API 配額使用量
     */
    @GetMapping("/api/health/deep")
    public ResponseEntity<Map<String, Object>> deepHealth() {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean allHealthy = true;

        // 1. Database
        Map<String, Object> dbStatus = checkDatabase();
        result.put("database", dbStatus);
        if (!"UP".equals(dbStatus.get("status"))) {
            allHealthy = false;
        }

        // 2. Binance API Rate Limit
        Map<String, Object> binanceStatus = checkBinanceRateLimit();
        result.put("binanceApi", binanceStatus);
        if ("DOWN".equals(binanceStatus.get("status"))) {
            allHealthy = false;
        }

        result.put("status", allHealthy ? "UP" : "DEGRADED");

        return allHealthy
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(503).body(result);
    }

    private Map<String, Object> checkDatabase() {
        Map<String, Object> status = new LinkedHashMap<>();
        long start = System.currentTimeMillis();
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SELECT 1");
            long latency = System.currentTimeMillis() - start;
            status.put("status", "UP");
            status.put("latencyMs", latency);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.error("Health check: DB 連線失敗 - {}", e.getMessage());
            status.put("status", "DOWN");
            status.put("error", e.getMessage());
            status.put("latencyMs", latency);
        }
        return status;
    }

    private Map<String, Object> checkBinanceRateLimit() {
        Map<String, Object> status = new LinkedHashMap<>();
        int currentWeight = binanceApiRateLimiter.getCurrentWeight();
        int remaining = binanceApiRateLimiter.getRemainingWeight();
        double usageRatio = binanceApiRateLimiter.getUsageRatio();

        status.put("weightUsed", currentWeight);
        status.put("weightRemaining", remaining);
        status.put("usagePercent", String.format("%.1f%%", usageRatio * 100));

        if (usageRatio > 0.95) {
            status.put("status", "DOWN");
            status.put("warning", "API rate limit nearly exhausted");
        } else if (usageRatio > 0.8) {
            status.put("status", "WARN");
            status.put("warning", "API rate limit usage high");
        } else {
            status.put("status", "UP");
        }
        return status;
    }
}
