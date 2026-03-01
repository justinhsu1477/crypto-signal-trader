package com.trader.shared.controller;

import com.trader.shared.dto.SystemStatusResponse;
import com.trader.shared.dto.SystemStatusResponse.ServiceStatus;
import com.trader.shared.util.BinanceApiRateLimiter;
import com.trader.trading.service.MonitorHeartbeatService;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * StatusController 單元測試
 */
class StatusControllerTest {

    private DataSource dataSource;
    private BinanceApiRateLimiter binanceApiRateLimiter;
    private MonitorHeartbeatService monitorHeartbeatService;
    private RabbitTemplate rabbitTemplate;
    private ConnectionFactory connectionFactory;
    private Connection rabbitConnection;
    private StatusController controller;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = mock(DataSource.class);
        binanceApiRateLimiter = mock(BinanceApiRateLimiter.class);
        monitorHeartbeatService = mock(MonitorHeartbeatService.class);
        rabbitTemplate = mock(RabbitTemplate.class);
        connectionFactory = mock(ConnectionFactory.class);
        rabbitConnection = mock(Connection.class);

        // 預設全部健康
        var dbConnection = mock(java.sql.Connection.class);
        var statement = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(dbConnection);
        when(dbConnection.createStatement()).thenReturn(statement);

        when(binanceApiRateLimiter.getUsageRatio()).thenReturn(0.3);

        when(rabbitTemplate.getConnectionFactory()).thenReturn(connectionFactory);
        when(connectionFactory.createConnection()).thenReturn(rabbitConnection);
        when(rabbitConnection.isOpen()).thenReturn(true);

        Map<String, Object> heartbeat = new LinkedHashMap<>();
        heartbeat.put("monitorConnected", true);
        when(monitorHeartbeatService.getStatus()).thenReturn(heartbeat);

        controller = new StatusController(dataSource, binanceApiRateLimiter,
                monitorHeartbeatService, rabbitTemplate);
    }

    @Nested
    @DisplayName("GET /api/status")
    class GetStatus {

        @Test
        @DisplayName("全部健康 → overallStatus = UP + 4 個 service")
        void allUp() {
            ResponseEntity<SystemStatusResponse> response = controller.getPublicStatus();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            SystemStatusResponse body = response.getBody();
            assertThat(body.getOverallStatus()).isEqualTo("UP");
            assertThat(body.getServices()).hasSize(4);
            assertThat(body.getServices()).allSatisfy(s ->
                    assertThat(s.getStatus()).isEqualTo("UP"));
        }

        @Test
        @DisplayName("DB DOWN → overallStatus = DEGRADED")
        void databaseDown() throws Exception {
            when(dataSource.getConnection()).thenThrow(new RuntimeException("Connection refused"));

            ResponseEntity<SystemStatusResponse> response = controller.getPublicStatus();
            SystemStatusResponse body = response.getBody();

            assertThat(body.getOverallStatus()).isEqualTo("DEGRADED");
            ServiceStatus db = body.getServices().stream()
                    .filter(s -> s.getName().equals("Database"))
                    .findFirst().orElseThrow();
            assertThat(db.getStatus()).isEqualTo("DOWN");
        }

        @Test
        @DisplayName("Monitor 離線 → overallStatus = DEGRADED")
        void monitorOffline() {
            Map<String, Object> heartbeat = new LinkedHashMap<>();
            heartbeat.put("monitorConnected", false);
            when(monitorHeartbeatService.getStatus()).thenReturn(heartbeat);

            ResponseEntity<SystemStatusResponse> response = controller.getPublicStatus();
            SystemStatusResponse body = response.getBody();

            assertThat(body.getOverallStatus()).isEqualTo("DEGRADED");
            ServiceStatus monitor = body.getServices().stream()
                    .filter(s -> s.getName().equals("Signal Monitor"))
                    .findFirst().orElseThrow();
            assertThat(monitor.getStatus()).isEqualTo("DOWN");
        }

        @Test
        @DisplayName("RabbitMQ 斷線 → overallStatus = DEGRADED")
        void rabbitDown() {
            when(connectionFactory.createConnection()).thenThrow(new RuntimeException("Connection refused"));

            ResponseEntity<SystemStatusResponse> response = controller.getPublicStatus();
            SystemStatusResponse body = response.getBody();

            assertThat(body.getOverallStatus()).isEqualTo("DEGRADED");
            ServiceStatus rabbit = body.getServices().stream()
                    .filter(s -> s.getName().equals("Notification System"))
                    .findFirst().orElseThrow();
            assertThat(rabbit.getStatus()).isEqualTo("DOWN");
        }

        @Test
        @DisplayName("Binance API 配額臨界 → Trading Engine DEGRADED")
        void binanceDegraded() {
            when(binanceApiRateLimiter.getUsageRatio()).thenReturn(0.85);

            ResponseEntity<SystemStatusResponse> response = controller.getPublicStatus();
            SystemStatusResponse body = response.getBody();

            ServiceStatus binance = body.getServices().stream()
                    .filter(s -> s.getName().equals("Trading Engine"))
                    .findFirst().orElseThrow();
            assertThat(binance.getStatus()).isEqualTo("DEGRADED");
        }

        @Test
        @DisplayName("Binance API 配額耗盡 → Trading Engine DOWN")
        void binanceDown() {
            when(binanceApiRateLimiter.getUsageRatio()).thenReturn(0.96);

            ResponseEntity<SystemStatusResponse> response = controller.getPublicStatus();
            SystemStatusResponse body = response.getBody();

            assertThat(body.getOverallStatus()).isEqualTo("DEGRADED");
            ServiceStatus binance = body.getServices().stream()
                    .filter(s -> s.getName().equals("Trading Engine"))
                    .findFirst().orElseThrow();
            assertThat(binance.getStatus()).isEqualTo("DOWN");
        }

        @Test
        @DisplayName("checkedAt 為有效 ISO-8601 時間")
        void checkedAtIsValid() {
            ResponseEntity<SystemStatusResponse> response = controller.getPublicStatus();

            assertThat(response.getBody().getCheckedAt()).isNotNull();
            assertThatCode(() -> Instant.parse(response.getBody().getCheckedAt()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("每個 service 都有 description")
        void servicesHaveDescriptions() {
            ResponseEntity<SystemStatusResponse> response = controller.getPublicStatus();

            assertThat(response.getBody().getServices()).allSatisfy(s -> {
                assertThat(s.getName()).isNotBlank();
                assertThat(s.getDescription()).isNotBlank();
            });
        }
    }
}
