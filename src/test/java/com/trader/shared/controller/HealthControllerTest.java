package com.trader.shared.controller;

import com.trader.chatbot.service.DiscordBotService;
import com.trader.shared.util.BinanceApiRateLimiter;
import com.trader.trading.service.MonitorHeartbeatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * HealthController 單元測試（無 Spring Context）
 *
 * 驗證：
 * - /api/health 永遠回 200
 * - /api/health/deep DB UP + Binance UP + heartbeat UP + bot UP → 200
 * - /api/health/deep 任一 DOWN → 503
 * - heartbeat 從未收到 → UNKNOWN（不擋健康）
 */
class HealthControllerTest {

    private HealthController controller;
    private DataSource dataSource;
    private BinanceApiRateLimiter rateLimiter;
    private MonitorHeartbeatService heartbeatService;
    private DiscordBotService discordBotService;

    @BeforeEach
    void setUp() {
        dataSource = mock(DataSource.class);
        rateLimiter = mock(BinanceApiRateLimiter.class);
        heartbeatService = mock(MonitorHeartbeatService.class);
        discordBotService = mock(DiscordBotService.class);
        controller = new HealthController(
                dataSource, rateLimiter, heartbeatService, discordBotService);

        // 預設：心跳新鮮、bot 連線
        when(heartbeatService.getStatus()).thenReturn(freshHeartbeat());
        when(discordBotService.isReady()).thenReturn(true);
    }

    private Map<String, Object> freshHeartbeat() {
        Map<String, Object> hb = new HashMap<>();
        hb.put("lastHeartbeat", "2026-05-12T12:00:00Z");
        hb.put("secondsSinceLastHeartbeat", 30L);
        hb.put("monitorStatus", "connected");
        // 預設 capture 也健康（120 秒前剛收到訊息）
        hb.put("secondsSinceAnyMessage", 120.0);
        return hb;
    }

    private Map<String, Object> heartbeatWithCaptureStalled(double secondsSinceAnyMessage) {
        Map<String, Object> hb = new HashMap<>();
        hb.put("lastHeartbeat", "2026-05-12T12:00:00Z");
        hb.put("secondsSinceLastHeartbeat", 30L);
        hb.put("monitorStatus", "connected");
        hb.put("secondsSinceAnyMessage", secondsSinceAnyMessage);
        return hb;
    }

    private Map<String, Object> heartbeatWithoutCaptureField() {
        Map<String, Object> hb = new HashMap<>();
        hb.put("lastHeartbeat", "2026-05-12T12:00:00Z");
        hb.put("secondsSinceLastHeartbeat", 30L);
        hb.put("monitorStatus", "connected");
        hb.put("secondsSinceAnyMessage", null);  // Python 啟動以來還沒收到訊息
        return hb;
    }

    private Map<String, Object> staleHeartbeat() {
        Map<String, Object> hb = new HashMap<>();
        hb.put("lastHeartbeat", "2026-05-12T11:00:00Z");
        hb.put("secondsSinceLastHeartbeat", 600L);
        hb.put("monitorStatus", "connected");
        return hb;
    }

    private Map<String, Object> noHeartbeatYet() {
        Map<String, Object> hb = new HashMap<>();
        hb.put("lastHeartbeat", null);
        hb.put("secondsSinceLastHeartbeat", null);
        hb.put("monitorStatus", "unknown");
        return hb;
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

    // ==================== heartbeat 檢查 ====================

    @Test
    @DisplayName("deep: 心跳新鮮 → monitorHeartbeat UP")
    @SuppressWarnings("unchecked")
    void deepHealthHeartbeatFresh() throws Exception {
        mockDbAndBinanceUp();

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> hb = (Map<String, Object>) response.getBody().get("monitorHeartbeat");
        assertEquals("UP", hb.get("status"));
        assertEquals(30L, hb.get("secondsSinceLastHeartbeat"));
    }

    @Test
    @DisplayName("deep: 心跳逾時（>90s）→ monitorHeartbeat DOWN + 503")
    @SuppressWarnings("unchecked")
    void deepHealthHeartbeatStale() throws Exception {
        mockDbAndBinanceUp();
        when(heartbeatService.getStatus()).thenReturn(staleHeartbeat());

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        assertEquals(503, response.getStatusCode().value());
        assertEquals("DEGRADED", response.getBody().get("status"));
        Map<String, Object> hb = (Map<String, Object>) response.getBody().get("monitorHeartbeat");
        assertEquals("DOWN", hb.get("status"));
        assertTrue(hb.get("warning").toString().contains("stale"));
    }

    @Test
    @DisplayName("deep: 從未收過心跳（冷啟動）→ UNKNOWN，不擋健康狀態")
    @SuppressWarnings("unchecked")
    void deepHealthHeartbeatNeverReceived() throws Exception {
        mockDbAndBinanceUp();
        when(heartbeatService.getStatus()).thenReturn(noHeartbeatYet());

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        // UNKNOWN ≠ DOWN，整體仍 UP
        assertEquals(200, response.getStatusCode().value());
        assertEquals("UP", response.getBody().get("status"));
        Map<String, Object> hb = (Map<String, Object>) response.getBody().get("monitorHeartbeat");
        assertEquals("UNKNOWN", hb.get("status"));
    }

    @Test
    @DisplayName("deep: 心跳服務拋異常 → UNKNOWN（非阻塞）")
    @SuppressWarnings("unchecked")
    void deepHealthHeartbeatThrows() throws Exception {
        mockDbAndBinanceUp();
        when(heartbeatService.getStatus()).thenThrow(new RuntimeException("boom"));

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> hb = (Map<String, Object>) response.getBody().get("monitorHeartbeat");
        assertEquals("UNKNOWN", hb.get("status"));
        assertEquals("boom", hb.get("error"));
    }

    // ==================== Discord Bot 檢查 ====================

    @Test
    @DisplayName("deep: Discord Bot 已連線 → discordBot UP")
    @SuppressWarnings("unchecked")
    void deepHealthDiscordBotReady() throws Exception {
        mockDbAndBinanceUp();

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> bot = (Map<String, Object>) response.getBody().get("discordBot");
        assertEquals("UP", bot.get("status"));
    }

    @Test
    @DisplayName("deep: Discord Bot 未連線 → discordBot DOWN + 503")
    @SuppressWarnings("unchecked")
    void deepHealthDiscordBotDown() throws Exception {
        mockDbAndBinanceUp();
        when(discordBotService.isReady()).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        assertEquals(503, response.getStatusCode().value());
        assertEquals("DEGRADED", response.getBody().get("status"));
        Map<String, Object> bot = (Map<String, Object>) response.getBody().get("discordBot");
        assertEquals("DOWN", bot.get("status"));
        assertTrue(bot.get("warning").toString().contains("JDA"));
    }

    @Test
    @DisplayName("deep: Discord Bot 拋異常 → UNKNOWN（非阻塞，不影響其他檢查）")
    @SuppressWarnings("unchecked")
    void deepHealthDiscordBotThrows() throws Exception {
        mockDbAndBinanceUp();
        when(discordBotService.isReady()).thenThrow(new RuntimeException("jda error"));

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> bot = (Map<String, Object>) response.getBody().get("discordBot");
        assertEquals("UNKNOWN", bot.get("status"));
        assertEquals("jda error", bot.get("error"));
    }

    // ==================== Layer 1 capture watchdog ====================

    @Test
    @DisplayName("deep: capture 新鮮（120s）→ capture UP")
    @SuppressWarnings("unchecked")
    void deepHealthCaptureFresh() throws Exception {
        mockDbAndBinanceUp();

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> capture = (Map<String, Object>) response.getBody().get("capture");
        assertEquals("UP", capture.get("status"));
        assertEquals(120.0, capture.get("secondsSinceAnyMessage"));
    }

    @Test
    @DisplayName("deep: capture stalled（15000s > 14400 門檻）→ capture DEGRADED + 503")
    @SuppressWarnings("unchecked")
    void deepHealthCaptureStalled() throws Exception {
        mockDbAndBinanceUp();
        when(heartbeatService.getStatus()).thenReturn(heartbeatWithCaptureStalled(15000.0));

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        assertEquals(503, response.getStatusCode().value());
        assertEquals("DEGRADED", response.getBody().get("status"));
        Map<String, Object> capture = (Map<String, Object>) response.getBody().get("capture");
        assertEquals("DEGRADED", capture.get("status"));
        assertTrue(capture.get("warning").toString().contains("stalled"));
        assertTrue(capture.get("warning").toString().contains("hours"));
    }

    @Test
    @DisplayName("deep: capture 剛好在門檻邊緣（14400s）→ UP")
    @SuppressWarnings("unchecked")
    void deepHealthCaptureAtBoundary() throws Exception {
        mockDbAndBinanceUp();
        when(heartbeatService.getStatus()).thenReturn(heartbeatWithCaptureStalled(14400.0));

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        // 14400 exact = 不超過門檻 → UP
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> capture = (Map<String, Object>) response.getBody().get("capture");
        assertEquals("UP", capture.get("status"));
    }

    @Test
    @DisplayName("deep: capture null（Python 啟動還沒收到訊息）→ capture UP + 不擋健康")
    @SuppressWarnings("unchecked")
    void deepHealthCaptureNull() throws Exception {
        mockDbAndBinanceUp();
        when(heartbeatService.getStatus()).thenReturn(heartbeatWithoutCaptureField());

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> capture = (Map<String, Object>) response.getBody().get("capture");
        assertEquals("UP", capture.get("status"));
        assertEquals("no messages received yet", capture.get("reason"));
    }

    @Test
    @DisplayName("deep: 心跳本身 UNKNOWN（冷啟動）→ capture UNKNOWN")
    @SuppressWarnings("unchecked")
    void deepHealthCaptureUnknownWhenHeartbeatUnknown() throws Exception {
        mockDbAndBinanceUp();
        when(heartbeatService.getStatus()).thenReturn(noHeartbeatYet());

        ResponseEntity<Map<String, Object>> response = controller.deepHealth();

        // heartbeat UNKNOWN 不擋健康，capture 也應跟著 UNKNOWN
        assertEquals(200, response.getStatusCode().value());
        Map<String, Object> capture = (Map<String, Object>) response.getBody().get("capture");
        assertEquals("UNKNOWN", capture.get("status"));
    }

    /** 共用：mock DB + Binance 都 UP（給 heartbeat / bot 測試用） */
    private void mockDbAndBinanceUp() throws SQLException {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
        when(rateLimiter.getCurrentWeight()).thenReturn(100);
        when(rateLimiter.getRemainingWeight()).thenReturn(2300);
        when(rateLimiter.getUsageRatio()).thenReturn(0.04);
    }
}
