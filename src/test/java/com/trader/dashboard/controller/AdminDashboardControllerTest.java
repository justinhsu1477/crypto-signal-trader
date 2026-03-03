package com.trader.dashboard.controller;

import com.trader.dashboard.dto.AdminSystemOverview;
import com.trader.dashboard.dto.DatabaseStatsResponse;
import com.trader.dashboard.service.DashboardService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
    private AdminDashboardController controller;

    @BeforeEach
    void setUp() {
        dashboardService = mock(DashboardService.class);
        userRepository = mock(UserRepository.class);
        dataSource = mock(DataSource.class);
        controller = new AdminDashboardController(dashboardService, userRepository, dataSource);
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

            ResponseEntity<AdminSystemOverview> response = controller.getSystemOverview();

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

            ResponseEntity<AdminSystemOverview> response = controller.getSystemOverview();

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

            ResponseEntity<AdminSystemOverview> response = controller.getSystemOverview();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().getUserSummaries()).hasSize(1);
            assertThat(response.getBody().getUserSummaries().get(0).getOpenPositionCount()).isEqualTo(0);
            assertThat(response.getBody().getUserSummaries().get(0).getClosedTradeCount()).isEqualTo(0);
            assertThat(response.getBody().getUserSummaries().get(0).getEmail()).isEqualTo("a@test.com");
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
}
