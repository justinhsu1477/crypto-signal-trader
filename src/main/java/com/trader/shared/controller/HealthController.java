package com.trader.shared.controller;

import com.trader.chatbot.service.DiscordBotService;
import com.trader.shared.util.BinanceApiRateLimiter;
import com.trader.trading.service.MonitorHeartbeatService;
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
 * GET /api/health/deep  — 深度檢查（DB + Binance API 配額 + Python 心跳 + Discord Bot）
 *
 * 無認證、無副作用。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class HealthController {

    /** Python heartbeat 過期門檻（與 MonitorHeartbeatService.HEARTBEAT_TIMEOUT_SECONDS 一致） */
    private static final long HEARTBEAT_FRESH_SECONDS = 90;

    /**
     * Layer 1 capture watchdog 門檻：CDP 全域沉默超過 4 小時就判定 DEGRADED。
     * 4 小時 = 14400 秒，能容忍訊號群最安靜時段（17-22 Taipei 夜間 1-12 msgs/hr）。
     */
    private static final long CAPTURE_STALLED_SECONDS = 14400;

    private final DataSource dataSource;
    private final BinanceApiRateLimiter binanceApiRateLimiter;
    private final MonitorHeartbeatService monitorHeartbeatService;
    private final DiscordBotService discordBotService;

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
     *   3. Python Monitor heartbeat（90 秒新鮮度門檻）
     *   4. Discord JDA Bot 連線狀態
     *
     * 註：Gemini reachability 不在此處同步檢查（會消耗 token），改由
     *     Prometheus chatbot_llm_calls_total 等 metric 觀測。
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

        // 3. Python Monitor Heartbeat
        Map<String, Object> heartbeatStatus = checkMonitorHeartbeat();
        result.put("monitorHeartbeat", heartbeatStatus);
        if ("DOWN".equals(heartbeatStatus.get("status"))) {
            allHealthy = false;
        }

        // 4. Discord JDA Bot
        Map<String, Object> botStatus = checkDiscordBot();
        result.put("discordBot", botStatus);
        if ("DOWN".equals(botStatus.get("status"))) {
            allHealthy = false;
        }

        // 5. Layer 1 capture watchdog — CDP 是否還在送訊息
        // 與 monitorHeartbeat 不同：心跳是 Python 進程活著沒，capture 是 hook 是否還在 fire。
        // 5/13 incident 就是心跳正常但 hook 死掉，所以分開檢查。
        Map<String, Object> captureStatus = checkCaptureHealth(heartbeatStatus);
        result.put("capture", captureStatus);
        if ("DEGRADED".equals(captureStatus.get("status"))) {
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

    /**
     * Python Monitor 心跳檢查 — 用 secondsSinceLastHeartbeat 判斷是否新鮮
     * 90 秒內有心跳視為 UP，逾時為 DOWN，從未收過為 UNKNOWN（避免冷啟動誤報）。
     */
    private Map<String, Object> checkMonitorHeartbeat() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            Map<String, Object> hb = monitorHeartbeatService.getStatus();
            Object secondsSinceObj = hb.get("secondsSinceLastHeartbeat");
            Object lastHeartbeat = hb.get("lastHeartbeat");

            if (secondsSinceObj == null || lastHeartbeat == null) {
                // 系統剛啟動還沒收到心跳 — 不擋健康狀態
                status.put("status", "UNKNOWN");
                status.put("reason", "no heartbeat received yet");
                return status;
            }

            long elapsed = ((Number) secondsSinceObj).longValue();
            boolean fresh = elapsed >= 0 && elapsed <= HEARTBEAT_FRESH_SECONDS;
            status.put("status", fresh ? "UP" : "DOWN");
            status.put("lastHeartbeat", lastHeartbeat);
            status.put("secondsSinceLastHeartbeat", elapsed);
            status.put("monitorStatus", hb.get("monitorStatus"));
            // Python monitor commit hash（前 7 字 git HEAD）—— admin 可比對 main HEAD
            // 看本地 Python 是否落後（5/13 silent capture failure 那種狀況的 visibility）；
            // null = 舊版 Python 沒帶這個欄位（向後相容）。
            status.put("monitorVersion", hb.get("monitorVersion"));
            if (!fresh) {
                status.put("warning",
                        "Python heartbeat stale (> " + HEARTBEAT_FRESH_SECONDS + "s)");
            }
        } catch (Exception e) {
            log.error("Health check: 心跳查詢失敗 - {}", e.getMessage());
            status.put("status", "UNKNOWN");
            status.put("error", e.getMessage());
        }
        return status;
    }

    /**
     * Layer 1 capture watchdog — 看 Python 帶來的 secondsSinceAnyMessage，
     * 超過 CAPTURE_STALLED_SECONDS（4h）即視為 DEGRADED。
     *
     * Decision matrix:
     *   - heartbeat UNKNOWN（系統剛啟動）→ capture UNKNOWN
     *   - secondsSinceAnyMessage == null（Python 啟動以來還沒收訊息，或舊版 Python）→ UP
     *     不誤報。冷啟動空窗很正常，何況夜間時段本來就可能上小時無訊號。
     *   - 0 ≤ value ≤ threshold → UP
     *   - value > threshold → DEGRADED（並帶人類可讀的 hours 訊息）
     */
    private Map<String, Object> checkCaptureHealth(Map<String, Object> heartbeatStatus) {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            // 心跳本身 UNKNOWN 時 capture 也算 UNKNOWN（避免冷啟動誤報）
            if ("UNKNOWN".equals(heartbeatStatus.get("status"))) {
                status.put("status", "UNKNOWN");
                status.put("reason", "heartbeat not yet received");
                return status;
            }

            Map<String, Object> hb = monitorHeartbeatService.getStatus();
            Object raw = hb.get("secondsSinceAnyMessage");
            if (!(raw instanceof Number)) {
                // Python 啟動以來還沒收過訊息（或舊版 Python 沒送這個欄位）
                status.put("status", "UP");
                status.put("reason", "no messages received yet");
                return status;
            }

            double secondsSince = ((Number) raw).doubleValue();
            status.put("secondsSinceAnyMessage", secondsSince);

            if (secondsSince > CAPTURE_STALLED_SECONDS) {
                double hours = secondsSince / 3600.0;
                status.put("status", "DEGRADED");
                status.put("warning",
                        String.format("Capture stalled %.1f hours (threshold %d hours)",
                                hours, CAPTURE_STALLED_SECONDS / 3600));
            } else {
                status.put("status", "UP");
            }
        } catch (Exception e) {
            log.error("Health check: capture 狀態查詢失敗 - {}", e.getMessage());
            status.put("status", "UNKNOWN");
            status.put("error", e.getMessage());
        }
        return status;
    }

    /**
     * Discord JDA Bot 連線狀態檢查 — JDA Status == CONNECTED 為 UP。
     * Chatbot 沒啟用（token 空）也算 DOWN，因為整條 chatbot 服務鏈是停的。
     */
    private Map<String, Object> checkDiscordBot() {
        Map<String, Object> status = new LinkedHashMap<>();
        try {
            boolean ready = discordBotService.isReady();
            status.put("status", ready ? "UP" : "DOWN");
            if (!ready) {
                status.put("warning", "Discord JDA not connected");
            }
        } catch (Exception e) {
            log.error("Health check: Discord Bot 狀態查詢失敗 - {}", e.getMessage());
            status.put("status", "UNKNOWN");
            status.put("error", e.getMessage());
        }
        return status;
    }
}
