package com.trader.dashboard.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.dashboard.dto.AdminSystemOverview;
import com.trader.dashboard.dto.DatabaseStatsResponse;
import com.trader.dashboard.service.DashboardService;
import com.trader.trading.dto.BroadcastLogResponse;
import com.trader.trading.dto.BroadcastLogResponse.BroadcastLogDetail;
import com.trader.trading.dto.BroadcastLogResponse.BroadcastLogSummary;
import com.trader.trading.entity.BroadcastLog;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;

import com.trader.shared.service.MetricsService;
import com.trader.trading.service.DailySignalReportService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdminDashboardController 單元測試
 *
 * 覆蓋：
 * - system-overview：用戶匯總、空用戶、per-user 統計例外
 * - database-stats：正常查詢、DB 例外、空表
 */
class AdminDashboardControllerTest {

    private DashboardService dashboardService;
    private UserRepository userRepository;
    private DataSource dataSource;
    private BroadcastLogRepository broadcastLogRepository;
    private ObjectMapper objectMapper;
    private AdminDashboardController controller;

    @BeforeEach
    void setUp() {
        dashboardService = mock(DashboardService.class);
        userRepository = mock(UserRepository.class);
        dataSource = mock(DataSource.class);
        broadcastLogRepository = mock(BroadcastLogRepository.class);
        objectMapper = new ObjectMapper();
        controller = new AdminDashboardController(
                dashboardService, userRepository, dataSource,
                mock(MetricsService.class), broadcastLogRepository, objectMapper,
                mock(DailySignalReportService.class));
    }

    // ── system-overview ──

    @Nested
    @DisplayName("GET /system-overview")
    class SystemOverviewTests {

        @Test
        @DisplayName("空用戶列表 → 200 + 全部歸零")
        void emptyUsers() {
            when(userRepository.findAll()).thenReturn(List.of());
            when(dashboardService.getBatchLightweightUserStats()).thenReturn(Map.of());

            ResponseEntity<AdminSystemOverview> response = controller.getSystemOverview("email", "asc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            AdminSystemOverview body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getTotalUsers()).isEqualTo(0);
            assertThat(body.getUserSummaries()).isEmpty();
        }

        @Test
        @DisplayName("多用戶 → 正確匯總（批次查詢，非 per-user）")
        void multipleUsers() {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A").enabled(true).autoTradeEnabled(true).build();
            User user2 = User.builder().userId("u2").email("b@test.com").name("B").enabled(false).autoTradeEnabled(false).build();
            when(userRepository.findAll()).thenReturn(List.of(user1, user2));

            Map<String, Map<String, Object>> batchStats = new LinkedHashMap<>();
            batchStats.put("u1", new LinkedHashMap<>(Map.of(
                    "openPositionCount", 2,
                    "closedTradeCount", 10L,
                    "totalNetProfit", 500.0,
                    "todayPnl", 50.0,
                    "todayTradeCount", 3L
            )));
            batchStats.put("u2", new LinkedHashMap<>(Map.of(
                    "openPositionCount", 0,
                    "closedTradeCount", 5L,
                    "totalNetProfit", -100.0,
                    "todayPnl", -20.0,
                    "todayTradeCount", 1L
            )));
            when(dashboardService.getBatchLightweightUserStats()).thenReturn(batchStats);

            ResponseEntity<AdminSystemOverview> response = controller.getSystemOverview("email", "asc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            AdminSystemOverview body = response.getBody();
            assertThat(body.getTotalUsers()).isEqualTo(2);
            assertThat(body.getActiveUsers()).isEqualTo(1); // only user1 enabled
            assertThat(body.getUsersWithOpenPositions()).isEqualTo(1);
            assertThat(body.getTotalOpenPositions()).isEqualTo(2);
            assertThat(body.getTotalClosedTrades()).isEqualTo(15);
            assertThat(body.getTotalNetProfit()).isCloseTo(400.0, within(0.01));
            assertThat(body.getTodayNetProfit()).isCloseTo(30.0, within(0.01));
            assertThat(body.getTodayTradeCount()).isEqualTo(4);
            assertThat(body.getUserSummaries()).hasSize(2);

            // 驗證使用批次查詢，而非 per-user 查詢
            verify(dashboardService).getBatchLightweightUserStats();
            verify(dashboardService, never()).getLightweightUserStats(anyString());
        }

        @Test
        @DisplayName("用戶無交易數據 → fallback 歸零")
        void userWithNoTradeData() {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A").enabled(true).autoTradeEnabled(true).build();
            when(userRepository.findAll()).thenReturn(List.of(user1));
            when(dashboardService.getBatchLightweightUserStats()).thenReturn(Map.of());

            ResponseEntity<AdminSystemOverview> response = controller.getSystemOverview("email", "asc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().getUserSummaries()).hasSize(1);
            assertThat(response.getBody().getUserSummaries().get(0).getOpenPositionCount()).isEqualTo(0);
            assertThat(response.getBody().getUserSummaries().get(0).getClosedTradeCount()).isEqualTo(0);
            assertThat(response.getBody().getUserSummaries().get(0).getEmail()).isEqualTo("a@test.com");
        }

        @Test
        @DisplayName("sortBy=totalNetProfit desc → 獲利最高排前面")
        void sortByTotalNetProfitDesc() {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A").enabled(true).autoTradeEnabled(true).build();
            User user2 = User.builder().userId("u2").email("b@test.com").name("B").enabled(true).autoTradeEnabled(true).build();
            User user3 = User.builder().userId("u3").email("c@test.com").name("C").enabled(true).autoTradeEnabled(true).build();
            when(userRepository.findAll()).thenReturn(List.of(user1, user2, user3));

            Map<String, Map<String, Object>> batchStats = new LinkedHashMap<>();
            batchStats.put("u1", new LinkedHashMap<>(Map.of(
                    "openPositionCount", 0, "closedTradeCount", 5L,
                    "totalNetProfit", 100.0, "todayPnl", 0.0, "todayTradeCount", 0L)));
            batchStats.put("u2", new LinkedHashMap<>(Map.of(
                    "openPositionCount", 0, "closedTradeCount", 3L,
                    "totalNetProfit", 500.0, "todayPnl", 0.0, "todayTradeCount", 0L)));
            batchStats.put("u3", new LinkedHashMap<>(Map.of(
                    "openPositionCount", 0, "closedTradeCount", 1L,
                    "totalNetProfit", -200.0, "todayPnl", 0.0, "todayTradeCount", 0L)));
            when(dashboardService.getBatchLightweightUserStats()).thenReturn(batchStats);

            ResponseEntity<AdminSystemOverview> response = controller.getSystemOverview("totalNetProfit", "desc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            List<AdminSystemOverview.UserTradingSummary> summaries = response.getBody().getUserSummaries();
            assertThat(summaries).hasSize(3);
            assertThat(summaries.get(0).getEmail()).isEqualTo("b@test.com"); // 500
            assertThat(summaries.get(1).getEmail()).isEqualTo("a@test.com"); // 100
            assertThat(summaries.get(2).getEmail()).isEqualTo("c@test.com"); // -200
        }

        @Test
        @DisplayName("sortBy=email asc（預設）→ 字母順序")
        void sortByEmailAsc() {
            User user1 = User.builder().userId("u1").email("charlie@test.com").name("C").enabled(true).autoTradeEnabled(true).build();
            User user2 = User.builder().userId("u2").email("alice@test.com").name("A").enabled(true).autoTradeEnabled(true).build();
            User user3 = User.builder().userId("u3").email("bob@test.com").name("B").enabled(true).autoTradeEnabled(true).build();
            when(userRepository.findAll()).thenReturn(List.of(user1, user2, user3));
            when(dashboardService.getBatchLightweightUserStats()).thenReturn(Map.of());

            ResponseEntity<AdminSystemOverview> response = controller.getSystemOverview("email", "asc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            List<AdminSystemOverview.UserTradingSummary> summaries = response.getBody().getUserSummaries();
            assertThat(summaries).hasSize(3);
            assertThat(summaries.get(0).getEmail()).isEqualTo("alice@test.com");
            assertThat(summaries.get(1).getEmail()).isEqualTo("bob@test.com");
            assertThat(summaries.get(2).getEmail()).isEqualTo("charlie@test.com");
        }

        @Test
        @DisplayName("未知 sortBy → fallback 到 email 排序，不報錯")
        void unknownSortByFallsBackToEmail() {
            User user1 = User.builder().userId("u1").email("z@test.com").name("Z").enabled(true).autoTradeEnabled(true).build();
            User user2 = User.builder().userId("u2").email("a@test.com").name("A").enabled(true).autoTradeEnabled(true).build();
            when(userRepository.findAll()).thenReturn(List.of(user1, user2));
            when(dashboardService.getBatchLightweightUserStats()).thenReturn(Map.of());

            ResponseEntity<AdminSystemOverview> response = controller.getSystemOverview("nonExistentField", "asc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            List<AdminSystemOverview.UserTradingSummary> summaries = response.getBody().getUserSummaries();
            assertThat(summaries).hasSize(2);
            // fallback 到 email asc
            assertThat(summaries.get(0).getEmail()).isEqualTo("a@test.com");
            assertThat(summaries.get(1).getEmail()).isEqualTo("z@test.com");
        }

        @Test
        @DisplayName("健康欄位正確填充 — hasBinanceApiKey / circuitBreakerActive / lastTradeAt / consecutiveLosses")
        void healthIndicatorsPopulated() {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A").enabled(true).autoTradeEnabled(true).build();
            when(userRepository.findAll()).thenReturn(List.of(user1));

            LocalDateTime lastTrade = LocalDateTime.of(2025, 3, 1, 10, 0);
            Map<String, Map<String, Object>> batchStats = new LinkedHashMap<>();
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("openPositionCount", 1);
            stats.put("closedTradeCount", 10L);
            stats.put("totalNetProfit", 200.0);
            stats.put("todayPnl", -50.0);
            stats.put("todayTradeCount", 2L);
            stats.put("weekPnl", 0.0);
            stats.put("monthPnl", 0.0);
            stats.put("hasBinanceApiKey", true);
            stats.put("circuitBreakerActive", true);
            stats.put("lastTradeAt", lastTrade);
            stats.put("consecutiveLosses", 3);
            batchStats.put("u1", stats);
            when(dashboardService.getBatchLightweightUserStats()).thenReturn(batchStats);

            ResponseEntity<AdminSystemOverview> response = controller.getSystemOverview("email", "asc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            AdminSystemOverview.UserTradingSummary summary = response.getBody().getUserSummaries().get(0);
            assertThat(summary.isHasBinanceApiKey()).isTrue();
            assertThat(summary.isCircuitBreakerActive()).isTrue();
            assertThat(summary.getLastTradeAt()).isEqualTo(lastTrade);
            assertThat(summary.getConsecutiveLosses()).isEqualTo(3);
        }

        @Test
        @DisplayName("健康欄位缺失 → fallback 預設值")
        void healthIndicatorsDefaults() {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A").enabled(true).autoTradeEnabled(true).build();
            when(userRepository.findAll()).thenReturn(List.of(user1));
            when(dashboardService.getBatchLightweightUserStats()).thenReturn(Map.of());

            ResponseEntity<AdminSystemOverview> response = controller.getSystemOverview("email", "asc");

            AdminSystemOverview.UserTradingSummary summary = response.getBody().getUserSummaries().get(0);
            assertThat(summary.isHasBinanceApiKey()).isFalse();
            assertThat(summary.isCircuitBreakerActive()).isFalse();
            assertThat(summary.getLastTradeAt()).isNull();
            assertThat(summary.getConsecutiveLosses()).isEqualTo(0);
        }

        @Test
        @DisplayName("sortBy=consecutiveLosses desc → 連續虧損最多排前面")
        void sortByConsecutiveLossesDesc() {
            User user1 = User.builder().userId("u1").email("a@test.com").name("A").enabled(true).autoTradeEnabled(true).build();
            User user2 = User.builder().userId("u2").email("b@test.com").name("B").enabled(true).autoTradeEnabled(true).build();
            when(userRepository.findAll()).thenReturn(List.of(user1, user2));

            Map<String, Map<String, Object>> batchStats = new LinkedHashMap<>();
            Map<String, Object> s1 = new LinkedHashMap<>(Map.of(
                    "openPositionCount", 0, "closedTradeCount", 5L, "totalNetProfit", 0.0,
                    "todayPnl", 0.0, "todayTradeCount", 0L, "weekPnl", 0.0, "monthPnl", 0.0,
                    "consecutiveLosses", 2));
            s1.put("hasBinanceApiKey", false);
            s1.put("circuitBreakerActive", false);
            s1.put("lastTradeAt", null);
            batchStats.put("u1", s1);

            Map<String, Object> s2 = new LinkedHashMap<>(Map.of(
                    "openPositionCount", 0, "closedTradeCount", 3L, "totalNetProfit", 0.0,
                    "todayPnl", 0.0, "todayTradeCount", 0L, "weekPnl", 0.0, "monthPnl", 0.0,
                    "consecutiveLosses", 7));
            s2.put("hasBinanceApiKey", false);
            s2.put("circuitBreakerActive", false);
            s2.put("lastTradeAt", null);
            batchStats.put("u2", s2);

            when(dashboardService.getBatchLightweightUserStats()).thenReturn(batchStats);

            ResponseEntity<AdminSystemOverview> response = controller.getSystemOverview("consecutiveLosses", "desc");

            List<AdminSystemOverview.UserTradingSummary> summaries = response.getBody().getUserSummaries();
            assertThat(summaries.get(0).getEmail()).isEqualTo("b@test.com"); // 7
            assertThat(summaries.get(1).getEmail()).isEqualTo("a@test.com"); // 2
        }

        @Test
        @DisplayName("name 欄位有 null → null 排最後")
        void nullableNameSortingNullsLast() {
            User user1 = User.builder().userId("u1").email("a@test.com").name(null).enabled(true).autoTradeEnabled(true).build();
            User user2 = User.builder().userId("u2").email("b@test.com").name("Bob").enabled(true).autoTradeEnabled(true).build();
            User user3 = User.builder().userId("u3").email("c@test.com").name("Alice").enabled(true).autoTradeEnabled(true).build();
            when(userRepository.findAll()).thenReturn(List.of(user1, user2, user3));
            when(dashboardService.getBatchLightweightUserStats()).thenReturn(Map.of());

            ResponseEntity<AdminSystemOverview> response = controller.getSystemOverview("name", "asc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            List<AdminSystemOverview.UserTradingSummary> summaries = response.getBody().getUserSummaries();
            assertThat(summaries).hasSize(3);
            assertThat(summaries.get(0).getName()).isEqualTo("Alice");
            assertThat(summaries.get(1).getName()).isEqualTo("Bob");
            assertThat(summaries.get(2).getName()).isNull(); // null 排最後
        }
    }

    // ── database-stats ──

    @Nested
    @DisplayName("GET /database-stats")
    class DatabaseStatsTests {

        @Test
        @DisplayName("正常查詢 → 200 + 正確的 DB 大小與表統計")
        @SuppressWarnings("unchecked")
        void normalStats() throws Exception {
            Connection conn = mock(Connection.class);
            Statement stmt = mock(Statement.class);
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.createStatement()).thenReturn(stmt);

            // Mock pg_database_size
            ResultSet sizeRs = mock(ResultSet.class);
            when(stmt.executeQuery("SELECT pg_database_size(current_database())")).thenReturn(sizeRs);
            when(sizeRs.next()).thenReturn(true);
            when(sizeRs.getLong(1)).thenReturn(10_485_760L); // 10 MB

            // Mock table stats
            ResultSet tableRs = mock(ResultSet.class);
            when(stmt.executeQuery(contains("pg_stat_user_tables"))).thenReturn(tableRs);
            when(tableRs.next()).thenReturn(true, true, false);
            when(tableRs.getString("table_name")).thenReturn("audit_logs", "trades");
            when(tableRs.getLong("row_count")).thenReturn(1500L, 30L);
            when(tableRs.getLong("total_bytes")).thenReturn(524_288L, 131_072L); // 512 KB, 128 KB

            ResponseEntity<DatabaseStatsResponse> response = controller.getDatabaseStats();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            DatabaseStatsResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getTotalSizeBytes()).isEqualTo(10_485_760L);
            assertThat(body.getStorageLimitBytes()).isEqualTo(512L * 1024 * 1024);
            assertThat(body.getUsagePercent()).isCloseTo(2.0, within(0.1));
            assertThat(body.getTables()).hasSize(2);
            assertThat(body.getTables().get(0).getTableName()).isEqualTo("audit_logs");
            assertThat(body.getTables().get(0).getRowCount()).isEqualTo(1500);
            assertThat(body.getTables().get(0).getTotalBytes()).isEqualTo(524_288L);
            assertThat(body.getTables().get(1).getTableName()).isEqualTo("trades");
        }

        @Test
        @DisplayName("空資料庫（無表）→ 200 + 空 tables 列表")
        void emptyDatabase() throws Exception {
            Connection conn = mock(Connection.class);
            Statement stmt = mock(Statement.class);
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.createStatement()).thenReturn(stmt);

            ResultSet sizeRs = mock(ResultSet.class);
            when(stmt.executeQuery("SELECT pg_database_size(current_database())")).thenReturn(sizeRs);
            when(sizeRs.next()).thenReturn(true);
            when(sizeRs.getLong(1)).thenReturn(8_192L); // 8 KB

            ResultSet tableRs = mock(ResultSet.class);
            when(stmt.executeQuery(contains("pg_stat_user_tables"))).thenReturn(tableRs);
            when(tableRs.next()).thenReturn(false); // no tables

            ResponseEntity<DatabaseStatsResponse> response = controller.getDatabaseStats();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().getTables()).isEmpty();
            assertThat(response.getBody().getTotalSizeBytes()).isEqualTo(8_192L);
        }

        @Test
        @DisplayName("DB 連線失敗 → 500")
        void connectionFailed() throws Exception {
            when(dataSource.getConnection()).thenThrow(new SQLException("Connection refused"));

            ResponseEntity<DatabaseStatsResponse> response = controller.getDatabaseStats();

            assertThat(response.getStatusCode().value()).isEqualTo(500);
        }

        @Test
        @DisplayName("SQL 查詢失敗 → 500")
        void queryFailed() throws Exception {
            Connection conn = mock(Connection.class);
            Statement stmt = mock(Statement.class);
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.createStatement()).thenReturn(stmt);
            when(stmt.executeQuery(anyString())).thenThrow(new SQLException("permission denied"));

            ResponseEntity<DatabaseStatsResponse> response = controller.getDatabaseStats();

            assertThat(response.getStatusCode().value()).isEqualTo(500);
        }

        @Test
        @DisplayName("高使用率 → usagePercent 正確計算")
        void highUsage() throws Exception {
            Connection conn = mock(Connection.class);
            Statement stmt = mock(Statement.class);
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.createStatement()).thenReturn(stmt);

            ResultSet sizeRs = mock(ResultSet.class);
            when(stmt.executeQuery("SELECT pg_database_size(current_database())")).thenReturn(sizeRs);
            when(sizeRs.next()).thenReturn(true);
            when(sizeRs.getLong(1)).thenReturn(450L * 1024 * 1024); // 450 MB

            ResultSet tableRs = mock(ResultSet.class);
            when(stmt.executeQuery(contains("pg_stat_user_tables"))).thenReturn(tableRs);
            when(tableRs.next()).thenReturn(false);

            ResponseEntity<DatabaseStatsResponse> response = controller.getDatabaseStats();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            // 450 / 512 = ~87.9%
            assertThat(response.getBody().getUsagePercent()).isCloseTo(87.9, within(0.2));
        }
    }

    // ── broadcast-logs ──

    @Nested
    @DisplayName("GET /broadcast-logs")
    class BroadcastLogsTests {

        @Test
        @DisplayName("正常分頁查詢 → 200 + 正確映射 summary 欄位")
        void normalPagination() {
            BroadcastLog log1 = BroadcastLog.builder()
                    .id(1L).signalAction("ENTRY").symbol("BTCUSDT").side("LONG")
                    .totalUsers(5).successCount(4).failCount(1)
                    .skippedNoSub(2).skippedNoKey(1).status("COMPLETED")
                    .aiConfidence(80).durationMs(1200L)
                    .createdAt(LocalDateTime.of(2025, 3, 1, 10, 0))
                    .build();

            Page<BroadcastLog> page = new PageImpl<>(List.of(log1), PageRequest.of(0, 20), 1);
            when(broadcastLogRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(page);

            ResponseEntity<BroadcastLogResponse> response = controller.getBroadcastLogs(0, 20);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            BroadcastLogResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getTotalElements()).isEqualTo(1);
            assertThat(body.getTotalPages()).isEqualTo(1);
            assertThat(body.getContent()).hasSize(1);

            BroadcastLogSummary summary = body.getContent().get(0);
            assertThat(summary.getId()).isEqualTo(1L);
            assertThat(summary.getSignalAction()).isEqualTo("ENTRY");
            assertThat(summary.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(summary.getSide()).isEqualTo("LONG");
            assertThat(summary.getSuccessCount()).isEqualTo(4);
            assertThat(summary.getFailCount()).isEqualTo(1);
            assertThat(summary.getSkippedNoSub()).isEqualTo(2);
            assertThat(summary.getSkippedNoKey()).isEqualTo(1);
            assertThat(summary.getAiConfidence()).isEqualTo(80);
            assertThat(summary.getDurationMs()).isEqualTo(1200L);
        }

        @Test
        @DisplayName("空結果 → 200 + content 為空列表")
        void emptyResults() {
            Page<BroadcastLog> page = new PageImpl<>(List.of(), PageRequest.of(0, 20), 0);
            when(broadcastLogRepository.findAllByOrderByCreatedAtDesc(any())).thenReturn(page);

            ResponseEntity<BroadcastLogResponse> response = controller.getBroadcastLogs(0, 20);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().getContent()).isEmpty();
            assertThat(response.getBody().getTotalElements()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("GET /broadcast-logs/{id}")
    class BroadcastLogDetailTests {

        @Test
        @DisplayName("存在的 log → 200 + 正確解析 userResults JSON")
        void existingLogWithUserResults() {
            String userResultsJson = "[{\"userId\":\"u1\",\"email\":\"a@test.com\",\"success\":true,\"errorMessage\":null},"
                    + "{\"userId\":\"u2\",\"email\":\"b@test.com\",\"success\":false,\"errorMessage\":\"Insufficient balance\"}]";

            BroadcastLog log = BroadcastLog.builder()
                    .id(1L).signalAction("ENTRY").symbol("BTCUSDT").side("LONG")
                    .entryPrice(50000.0).stopLoss(49000.0).takeProfit(52000.0)
                    .closeRatio(null).newStopLoss(null).newTakeProfit(null)
                    .isDca(false).sourceAuthor("陳哥")
                    .totalUsers(2).successCount(1).failCount(1)
                    .skippedNoSub(0).skippedNoKey(0).status("COMPLETED")
                    .aiConfidence(85).aiReasoning("Strong trend")
                    .durationMs(800L).userResults(userResultsJson)
                    .createdAt(LocalDateTime.of(2025, 3, 1, 10, 0))
                    .build();

            when(broadcastLogRepository.findById(1L)).thenReturn(Optional.of(log));

            ResponseEntity<BroadcastLogDetail> response = controller.getBroadcastLogDetail(1L);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            BroadcastLogDetail detail = response.getBody();
            assertThat(detail).isNotNull();
            assertThat(detail.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(detail.getEntryPrice()).isEqualTo(50000.0);
            assertThat(detail.getSourceAuthor()).isEqualTo("陳哥");
            assertThat(detail.getAiConfidence()).isEqualTo(85);
            assertThat(detail.getAiReasoning()).isEqualTo("Strong trend");
            assertThat(detail.getUserResults()).hasSize(2);
            assertThat(detail.getUserResults().get(0).getEmail()).isEqualTo("a@test.com");
            assertThat(detail.getUserResults().get(0).isSuccess()).isTrue();
            assertThat(detail.getUserResults().get(1).getErrorMessage()).isEqualTo("Insufficient balance");
        }

        @Test
        @DisplayName("userResults 為 null → 空列表")
        void nullUserResults() {
            BroadcastLog log = BroadcastLog.builder()
                    .id(2L).signalAction("CLOSE").symbol("ETHUSDT")
                    .totalUsers(0).successCount(0).failCount(0)
                    .skippedNoSub(0).skippedNoKey(0).status("COMPLETED")
                    .durationMs(100L).userResults(null)
                    .createdAt(LocalDateTime.of(2025, 3, 1, 10, 0))
                    .build();

            when(broadcastLogRepository.findById(2L)).thenReturn(Optional.of(log));

            ResponseEntity<BroadcastLogDetail> response = controller.getBroadcastLogDetail(2L);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().getUserResults()).isEmpty();
        }

        @Test
        @DisplayName("不存在的 id → 404")
        void notFound() {
            when(broadcastLogRepository.findById(999L)).thenReturn(Optional.empty());

            ResponseEntity<BroadcastLogDetail> response = controller.getBroadcastLogDetail(999L);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("userResults JSON 損壞 → 空列表（不報錯）")
        void corruptedUserResultsJson() {
            BroadcastLog log = BroadcastLog.builder()
                    .id(3L).signalAction("ENTRY").symbol("BTCUSDT")
                    .totalUsers(1).successCount(1).failCount(0)
                    .skippedNoSub(0).skippedNoKey(0).status("COMPLETED")
                    .durationMs(500L).userResults("{invalid json!!")
                    .createdAt(LocalDateTime.of(2025, 3, 1, 10, 0))
                    .build();

            when(broadcastLogRepository.findById(3L)).thenReturn(Optional.of(log));

            ResponseEntity<BroadcastLogDetail> response = controller.getBroadcastLogDetail(3L);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().getUserResults()).isEmpty();
        }
    }
}
