package com.trader.shared.controller;

import com.trader.shared.util.BinanceApiRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * HealthController 單元測試（無 Spring Context）
 *
 * 驗證：
 * - /api/health 永遠回 200
 * - /api/health/deep DB UP + Binance UP → 200
 * - /api/health/deep DB DOWN → 503
 * - /api/health/deep Binance weight 超高 → 503
 */
class HealthControllerTest {

    private HealthController controller;
    private DataSource dataSource;
    private BinanceApiRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        dataSource = mock(DataSource.class);
        rateLimiter = mock(BinanceApiRateLimiter.class);
        controller = new HealthController(dataSource, rateLimiter);
    }

    @Test
    @DisplayName("GET /api/health → 200 UP")
    void healthReturnsUp() {
        ResponseEntity<Map<String, String>> response = controller.health();
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
    }

    @Test
    @DisplayName("deep: DB UP + Binance UP → 200 UP")
    @SuppressWarnings("unchecked")
    void deepHealthAllUp() throws Exception {
        // Mock DB OK
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);

        // Mock Binance OK
        when(rateLimiter.getCurrentWeight()).thenReturn(100);
        when(rateLimiter.getRemainingWeight()).thenReturn(2300);
        when(rateLimiter.getUsageRatio()).thenReturn(0.04);

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));

        Map<String, Object> db = (Map<String, Object>) response.getBody().get("database");
        assertEquals("UP", db.get("status"));
        assertNotNull(db.get("latencyMs"));

        Map<String, Object> binance = (Map<String, Object>) response.getBody().get("binanceApi");
        assertEquals("UP", binance.get("status"));
        assertEquals(100, binance.get("weightUsed"));
        assertEquals(2300, binance.get("weightRemaining"));
    }

    @Test
    @DisplayName("deep: DB DOWN → 503 DEGRADED")
    @SuppressWarnings("unchecked")
    void deepHealthDbDown() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));

        when(rateLimiter.getCurrentWeight()).thenReturn(0);
        when(rateLimiter.getRemainingWeight()).thenReturn(2400);
        when(rateLimiter.getUsageRatio()).thenReturn(0.0);

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        assertEquals(503, response.getStatusCode().value());
        assertEquals("DEGRADED", response.getBody().get("status"));

        Map<String, Object> db = (Map<String, Object>) response.getBody().get("database");
        assertEquals("DOWN", db.get("status"));
        assertEquals("Connection refused", db.get("error"));
    }

    @Test
    @DisplayName("deep: Binance > 95% → 503 DEGRADED")
    @SuppressWarnings("unchecked")
    void deepHealthBinanceCritical() throws Exception {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);

        when(rateLimiter.getCurrentWeight()).thenReturn(2350);
        when(rateLimiter.getRemainingWeight()).thenReturn(50);
        when(rateLimiter.getUsageRatio()).thenReturn(0.98);

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        assertEquals(503, response.getStatusCode().value());
        assertEquals("DEGRADED", response.getBody().get("status"));

        Map<String, Object> binance = (Map<String, Object>) response.getBody().get("binanceApi");
        assertEquals("DOWN", binance.get("status"));
        assertEquals("API rate limit nearly exhausted", binance.get("warning"));
    }

    @Test
    @DisplayName("deep: Binance 80-95% → 200 UP but WARN")
    @SuppressWarnings("unchecked")
    void deepHealthBinanceWarn() throws Exception {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);

        when(rateLimiter.getCurrentWeight()).thenReturn(2040);
        when(rateLimiter.getRemainingWeight()).thenReturn(360);
        when(rateLimiter.getUsageRatio()).thenReturn(0.85);

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        // WARN 不影響整體 status（only DOWN causes DEGRADED）
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));

        Map<String, Object> binance = (Map<String, Object>) response.getBody().get("binanceApi");
        assertEquals("WARN", binance.get("status"));
    }
}
