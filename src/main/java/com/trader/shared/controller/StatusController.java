package com.trader.shared.controller;

import com.trader.shared.dto.SystemStatusResponse;
import com.trader.shared.dto.SystemStatusResponse.ServiceStatus;
import com.trader.shared.util.BinanceApiRateLimiter;
import com.trader.trading.service.MonitorHeartbeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 公開系統狀態端點
 *
 * GET /api/status — 無需認證
 *
 * 檢查 4 個核心服務：
 * 1. Database — PostgreSQL 連線
 * 2. Trading Engine — Binance API 配額
 * 3. Notification System — RabbitMQ 連線
 * 4. Signal Monitor — Python Monitor 心跳
 *
 * 不暴露內部細節（無 error message / latency）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class StatusController {

    private final DataSource dataSource;
    private final BinanceApiRateLimiter binanceApiRateLimiter;
    private final MonitorHeartbeatService monitorHeartbeatService;
    private final RabbitTemplate rabbitTemplate;

    @GetMapping("/api/status")
    public ResponseEntity<SystemStatusResponse> getPublicStatus() {
        List<ServiceStatus> services = new ArrayList<>();
        boolean allUp = true;

        // 1. Database
        ServiceStatus db = checkDatabaseStatus();
        services.add(db);
        if (!"UP".equals(db.getStatus())) allUp = false;

        // 2. Trading Engine (Binance API)
        ServiceStatus binance = checkBinanceStatus();
        services.add(binance);
        if ("DOWN".equals(binance.getStatus())) allUp = false;

        // 3. Notification System (RabbitMQ)
        ServiceStatus rabbit = checkRabbitMQStatus();
        services.add(rabbit);
        if (!"UP".equals(rabbit.getStatus())) allUp = false;

        // 4. Signal Monitor (heartbeat)
        ServiceStatus monitor = checkMonitorStatus();
        services.add(monitor);
        if (!"UP".equals(monitor.getStatus())) allUp = false;

        return ResponseEntity.ok(SystemStatusResponse.builder()
                .overallStatus(allUp ? "UP" : "DEGRADED")
                .services(services)
                .checkedAt(Instant.now().toString())
                .build());
    }

    private ServiceStatus checkDatabaseStatus() {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("SELECT 1");
            return ServiceStatus.builder()
                    .name("Database")
                    .status("UP")
                    .description("Primary database connection")
                    .build();
        } catch (Exception e) {
            log.warn("Status check: DB 連線失敗 - {}", e.getMessage());
            return ServiceStatus.builder()
                    .name("Database")
                    .status("DOWN")
                    .description("Primary database connection")
                    .build();
        }
    }

    private ServiceStatus checkBinanceStatus() {
        double usageRatio = binanceApiRateLimiter.getUsageRatio();
        String status;
        if (usageRatio > 0.95) {
            status = "DOWN";
        } else if (usageRatio > 0.8) {
            status = "DEGRADED";
        } else {
            status = "UP";
        }
        return ServiceStatus.builder()
                .name("Trading Engine")
                .status(status)
                .description("Binance API connectivity")
                .build();
    }

    private ServiceStatus checkRabbitMQStatus() {
        try {
            rabbitTemplate.getConnectionFactory().createConnection().isOpen();
            return ServiceStatus.builder()
                    .name("Notification System")
                    .status("UP")
                    .description("Message queue and delivery")
                    .build();
        } catch (Exception e) {
            log.warn("Status check: RabbitMQ 連線失敗 - {}", e.getMessage());
            return ServiceStatus.builder()
                    .name("Notification System")
                    .status("DOWN")
                    .description("Message queue and delivery")
                    .build();
        }
    }

    @SuppressWarnings("unchecked")
    private ServiceStatus checkMonitorStatus() {
        try {
            Map<String, Object> heartbeatStatus = monitorHeartbeatService.getStatus();
            boolean connected = Boolean.TRUE.equals(heartbeatStatus.get("monitorConnected"));
            return ServiceStatus.builder()
                    .name("Signal Monitor")
                    .status(connected ? "UP" : "DOWN")
                    .description("Discord signal monitoring service")
                    .build();
        } catch (Exception e) {
            return ServiceStatus.builder()
                    .name("Signal Monitor")
                    .status("DOWN")
                    .description("Discord signal monitoring service")
                    .build();
        }
    }
}
