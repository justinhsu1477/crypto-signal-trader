package com.trader.dashboard.controller;

import com.trader.dashboard.dto.AdminSystemOverview;
import com.trader.dashboard.dto.AdminSystemOverview.UserTradingSummary;
import com.trader.dashboard.dto.DatabaseStatsResponse;
import com.trader.dashboard.dto.DatabaseStatsResponse.TableStats;
import com.trader.dashboard.dto.DashboardOverview;
import com.trader.dashboard.dto.PerformanceStats;
import com.trader.dashboard.dto.TradeHistoryResponse;
import com.trader.dashboard.service.DashboardService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管理員 Dashboard API
 *
 * 路徑 /api/admin/** 已被 AuthConfig hasRole("ADMIN") 保護
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private static final long NEON_FREE_TIER_BYTES = 512L * 1024 * 1024; // 512 MB

    private final DashboardService dashboardService;
    private final UserRepository userRepository;
    private final DataSource dataSource;

    /**
     * 系統全域概覽 — 所有用戶匯總 + per-user 摘要
     *
     * 修復 N+1 問題：
     *   原本 for-each user → getLightweightUserStats() → 每人 ~10 次 DB 查詢
     *   改為 getBatchLightweightUserStats() 用 2 次 GROUP BY 批次聚合取代。
     *   100 用戶：1000+ 次查詢 → 3 次查詢（findAll + 2 次聚合）
     */
    @GetMapping("/system-overview")
    public ResponseEntity<AdminSystemOverview> getSystemOverview() {
        List<User> allUsers = userRepository.findAll();

        // 批次取得所有用戶統計（2 queries for all users, 取代 N * 10 queries）
        Map<String, Map<String, Object>> batchStats = dashboardService.getBatchLightweightUserStats();

        int activeUsers = 0;
        int usersWithOpenPositions = 0;
        int totalOpenPositions = 0;
        long totalClosedTrades = 0;
        double totalNetProfit = 0;
        double todayNetProfit = 0;
        int todayTradeCount = 0;

        List<UserTradingSummary> summaries = new ArrayList<>();

        for (User user : allUsers) {
            if (user.isEnabled()) activeUsers++;

            Map<String, Object> stats = batchStats.getOrDefault(user.getUserId(), Map.of());
            int openCount = stats.containsKey("openPositionCount") ? ((Number) stats.get("openPositionCount")).intValue() : 0;
            long closedCount = stats.containsKey("closedTradeCount") ? ((Number) stats.get("closedTradeCount")).longValue() : 0;
            double netProfit = stats.containsKey("totalNetProfit") ? ((Number) stats.get("totalNetProfit")).doubleValue() : 0;
            double todayPnl = stats.containsKey("todayPnl") ? ((Number) stats.get("todayPnl")).doubleValue() : 0;
            int todayTrades = stats.containsKey("todayTradeCount") ? ((Number) stats.get("todayTradeCount")).intValue() : 0;

            if (openCount > 0) usersWithOpenPositions++;
            totalOpenPositions += openCount;
            totalClosedTrades += closedCount;
            totalNetProfit += netProfit;
            todayNetProfit += todayPnl;
            todayTradeCount += todayTrades;

            summaries.add(UserTradingSummary.builder()
                    .userId(user.getUserId())
                    .email(user.getEmail())
                    .name(user.getName())
                    .enabled(user.isEnabled())
                    .autoTradeEnabled(user.isAutoTradeEnabled())
                    .openPositionCount(openCount)
                    .closedTradeCount(closedCount)
                    .totalNetProfit(netProfit)
                    .todayPnl(todayPnl)
                    .todayTradeCount(todayTrades)
                    .build());
        }

        return ResponseEntity.ok(AdminSystemOverview.builder()
                .totalUsers(allUsers.size())
                .activeUsers(activeUsers)
                .usersWithOpenPositions(usersWithOpenPositions)
                .totalOpenPositions(totalOpenPositions)
                .totalClosedTrades(totalClosedTrades)
                .totalNetProfit(totalNetProfit)
                .todayNetProfit(todayNetProfit)
                .todayTradeCount(todayTradeCount)
                .userSummaries(summaries)
                .build());
    }

    /**
     * 查看任意用戶的 Dashboard Overview
     */
    @GetMapping("/users/{userId}/overview")
    public ResponseEntity<DashboardOverview> getUserOverview(@PathVariable String userId) {
        if (!userRepository.existsById(userId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dashboardService.getOverview(userId));
    }

    /**
     * 查看任意用戶的績效統計
     */
    @GetMapping("/users/{userId}/performance")
    public ResponseEntity<PerformanceStats> getUserPerformance(
            @PathVariable String userId,
            @RequestParam(defaultValue = "30") int days) {
        if (!userRepository.existsById(userId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dashboardService.getPerformance(userId, days));
    }

    /**
     * 查看任意用戶的交易歷史
     */
    @GetMapping("/users/{userId}/trades")
    public ResponseEntity<TradeHistoryResponse> getUserTrades(
            @PathVariable String userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (!userRepository.existsById(userId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(dashboardService.getTradeHistory(userId, page, size));
    }

    /**
     * 資料庫使用量統計 — 查詢 PG 系統表
     */
    @GetMapping("/database-stats")
    public ResponseEntity<DatabaseStatsResponse> getDatabaseStats() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {

            // 1. 總 DB 大小
            long totalSizeBytes = 0;
            try (ResultSet rs = stmt.executeQuery("SELECT pg_database_size(current_database())")) {
                if (rs.next()) {
                    totalSizeBytes = rs.getLong(1);
                }
            }

            // 2. 各表大小 + 行數
            List<TableStats> tables = new ArrayList<>();
            String tableSql = """
                    SELECT
                        relname AS table_name,
                        n_live_tup AS row_count,
                        pg_total_relation_size(schemaname || '.' || relname) AS total_bytes
                    FROM pg_stat_user_tables
                    WHERE schemaname = 'public'
                    ORDER BY pg_total_relation_size(schemaname || '.' || relname) DESC
                    """;
            try (ResultSet rs = stmt.executeQuery(tableSql)) {
                while (rs.next()) {
                    tables.add(TableStats.builder()
                            .tableName(rs.getString("table_name"))
                            .rowCount(rs.getLong("row_count"))
                            .totalBytes(rs.getLong("total_bytes"))
                            .build());
                }
            }

            double usagePercent = totalSizeBytes * 100.0 / NEON_FREE_TIER_BYTES;

            return ResponseEntity.ok(DatabaseStatsResponse.builder()
                    .totalSizeBytes(totalSizeBytes)
                    .storageLimitBytes(NEON_FREE_TIER_BYTES)
                    .usagePercent(Math.round(usagePercent * 10.0) / 10.0)
                    .tables(tables)
                    .build());

        } catch (Exception e) {
            log.error("查詢資料庫統計失敗: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
