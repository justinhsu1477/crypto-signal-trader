package com.trader.dashboard.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.dashboard.dto.AdminSystemOverview;
import com.trader.dashboard.dto.AdminSystemOverview.UserTradingSummary;
import com.trader.dashboard.dto.DatabaseStatsResponse;
import com.trader.dashboard.dto.DatabaseStatsResponse.TableStats;
import com.trader.dashboard.dto.DashboardOverview;
import com.trader.dashboard.dto.FunnelStatsResponse;
import com.trader.dashboard.dto.PerformanceStats;
import com.trader.dashboard.dto.TradeHistoryResponse;
import com.trader.dashboard.service.DashboardService;
import com.trader.shared.service.MetricsService;
import com.trader.shared.util.SortHelper;
import com.trader.trading.dto.BroadcastLogResponse;
import com.trader.trading.dto.BroadcastLogResponse.BroadcastLogDetail;
import com.trader.trading.dto.BroadcastLogResponse.BroadcastLogSummary;
import com.trader.trading.dto.DailySignalReportResponse;
import com.trader.trading.dto.DailySignalReportResponse.ReportDetail;
import com.trader.trading.dto.DailySignalReportResponse.ReportSummary;
import com.trader.trading.entity.BroadcastLog;
import com.trader.trading.entity.DailySignalReport;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.service.DailySignalReportService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

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

    /** 用戶摘要排序欄位定義 */
    private static final Map<String, Function<Boolean, Comparator<UserTradingSummary>>> OVERVIEW_SORT_FIELDS =
            Map.ofEntries(
                    SortHelper.stringField("email", UserTradingSummary::getEmail),
                    SortHelper.stringField("name", UserTradingSummary::getName),
                    SortHelper.booleanField("enabled", UserTradingSummary::isEnabled),
                    SortHelper.booleanField("autoTradeEnabled", UserTradingSummary::isAutoTradeEnabled),
                    SortHelper.intField("openPositionCount", UserTradingSummary::getOpenPositionCount),
                    SortHelper.longField("closedTradeCount", UserTradingSummary::getClosedTradeCount),
                    SortHelper.doubleField("totalNetProfit", UserTradingSummary::getTotalNetProfit),
                    SortHelper.doubleField("todayPnl", UserTradingSummary::getTodayPnl),
                    SortHelper.doubleField("weekPnl", UserTradingSummary::getWeekPnl),
                    SortHelper.doubleField("monthPnl", UserTradingSummary::getMonthPnl),
                    SortHelper.intField("todayTradeCount", UserTradingSummary::getTodayTradeCount),
                    SortHelper.booleanField("hasBinanceApiKey", UserTradingSummary::isHasBinanceApiKey),
                    SortHelper.booleanField("circuitBreakerActive", UserTradingSummary::isCircuitBreakerActive),
                    SortHelper.intField("consecutiveLosses", UserTradingSummary::getConsecutiveLosses)
            );

    private final DashboardService dashboardService;
    private final UserRepository userRepository;
    private final DataSource dataSource;
    private final MetricsService metricsService;
    private final BroadcastLogRepository broadcastLogRepository;
    private final ObjectMapper objectMapper;
    private final DailySignalReportService dailySignalReportService;

    /**
     * 系統全域概覽 — 所有用戶匯總 + per-user 摘要
     *
     * 修復 N+1 問題：
     *   原本 for-each user → getLightweightUserStats() → 每人 ~10 次 DB 查詢
     *   改為 getBatchLightweightUserStats() 用 4 次 GROUP BY 批次聚合取代。
     *   100 用戶：1000+ 次查詢 → 5 次查詢（findAll + 4 次聚合：全期/今日/本周/本月）
     */
    @GetMapping("/system-overview")
    public ResponseEntity<AdminSystemOverview> getSystemOverview(
            @RequestParam(defaultValue = "email") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {
        List<User> allUsers = userRepository.findAll();

        // 批次取得所有用戶統計（4 queries for all users, 取代 N * 10 queries）
        Map<String, Map<String, Object>> batchStats = dashboardService.getBatchLightweightUserStats();

        int activeUsers = 0;
        int usersWithOpenPositions = 0;
        int totalOpenPositions = 0;
        long totalClosedTrades = 0;
        double totalNetProfit = 0;
        double todayNetProfit = 0;
        double weekNetProfit = 0;
        double monthNetProfit = 0;
        int todayTradeCount = 0;

        List<UserTradingSummary> summaries = new ArrayList<>();

        for (User user : allUsers) {
            if (user.isEnabled()) activeUsers++;

            Map<String, Object> stats = batchStats.getOrDefault(user.getUserId(), Map.of());
            int openCount = stats.containsKey("openPositionCount") ? ((Number) stats.get("openPositionCount")).intValue() : 0;
            long closedCount = stats.containsKey("closedTradeCount") ? ((Number) stats.get("closedTradeCount")).longValue() : 0;
            double netProfit = stats.containsKey("totalNetProfit") ? ((Number) stats.get("totalNetProfit")).doubleValue() : 0;
            double todayPnl = stats.containsKey("todayPnl") ? ((Number) stats.get("todayPnl")).doubleValue() : 0;
            double weekPnl = stats.containsKey("weekPnl") ? ((Number) stats.get("weekPnl")).doubleValue() : 0;
            double monthPnl = stats.containsKey("monthPnl") ? ((Number) stats.get("monthPnl")).doubleValue() : 0;
            int todayTrades = stats.containsKey("todayTradeCount") ? ((Number) stats.get("todayTradeCount")).intValue() : 0;

            // 健康度指標
            boolean hasBinanceApiKey = Boolean.TRUE.equals(stats.get("hasBinanceApiKey"));
            boolean circuitBreakerActive = Boolean.TRUE.equals(stats.get("circuitBreakerActive"));
            LocalDateTime lastTradeAt = stats.get("lastTradeAt") instanceof LocalDateTime lt ? lt : null;
            int consecutiveLosses = stats.containsKey("consecutiveLosses") ? ((Number) stats.get("consecutiveLosses")).intValue() : 0;

            if (openCount > 0) usersWithOpenPositions++;
            totalOpenPositions += openCount;
            totalClosedTrades += closedCount;
            totalNetProfit += netProfit;
            todayNetProfit += todayPnl;
            weekNetProfit += weekPnl;
            monthNetProfit += monthPnl;
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
                    .weekPnl(weekPnl)
                    .monthPnl(monthPnl)
                    .todayTradeCount(todayTrades)
                    .hasBinanceApiKey(hasBinanceApiKey)
                    .circuitBreakerActive(circuitBreakerActive)
                    .lastTradeAt(lastTradeAt)
                    .consecutiveLosses(consecutiveLosses)
                    .build());
        }

        List<UserTradingSummary> sorted = SortHelper.sort(
                summaries, sortBy, sortDir, OVERVIEW_SORT_FIELDS, "email");

        return ResponseEntity.ok(AdminSystemOverview.builder()
                .totalUsers(allUsers.size())
                .activeUsers(activeUsers)
                .usersWithOpenPositions(usersWithOpenPositions)
                .totalOpenPositions(totalOpenPositions)
                .totalClosedTrades(totalClosedTrades)
                .totalNetProfit(totalNetProfit)
                .todayNetProfit(todayNetProfit)
                .weekNetProfit(weekNetProfit)
                .monthNetProfit(monthNetProfit)
                .todayTradeCount(todayTradeCount)
                .userSummaries(sorted)
                .build());
    }

    /**
     * 所有用戶帳戶餘額 — 並行查詢 Binance Futures API
     *
     * 只回傳有 API Key 的用戶，value 為 null 表示查詢失敗。
     * 獨立 endpoint 避免拖慢 system-overview 載入速度。
     */
    @GetMapping("/user-balances")
    public ResponseEntity<Map<String, Double>> getUserBalances() {
        return ResponseEntity.ok(dashboardService.getBatchUserBalances());
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

    /**
     * 系統運行指標 — Micrometer 指標摘要（下單/訊號/通知/API延遲）
     */
    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        return ResponseEntity.ok(metricsService.getMetricsSummary());
    }

    // ── 用戶漏斗統計 ──

    /**
     * 用戶漏斗統計 — 6 階段 + 註冊趨勢 + 最近註冊
     */
    @GetMapping("/funnel")
    public ResponseEntity<FunnelStatsResponse> getFunnelStats() {
        return ResponseEntity.ok(dashboardService.getFunnelStats());
    }

    // ── 廣播紀錄 ──

    /**
     * 廣播紀錄列表（分頁，不含 userResults 明細）
     */
    @GetMapping("/broadcast-logs")
    public ResponseEntity<BroadcastLogResponse> getBroadcastLogs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<BroadcastLog> logs = broadcastLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));

        List<BroadcastLogSummary> summaries = logs.getContent().stream()
                .map(l -> BroadcastLogSummary.builder()
                        .id(l.getId())
                        .signalAction(l.getSignalAction())
                        .symbol(l.getSymbol())
                        .side(l.getSide())
                        .totalUsers(l.getTotalUsers())
                        .successCount(l.getSuccessCount())
                        .failCount(l.getFailCount())
                        .skippedNoSub(l.getSkippedNoSub())
                        .skippedNoKey(l.getSkippedNoKey())
                        .status(l.getStatus())
                        .aiConfidence(l.getAiConfidence())
                        .durationMs(l.getDurationMs())
                        .createdAt(l.getCreatedAt())
                        .build())
                .toList();

        return ResponseEntity.ok(BroadcastLogResponse.builder()
                .content(summaries)
                .page(page)
                .size(size)
                .totalPages(logs.getTotalPages())
                .totalElements(logs.getTotalElements())
                .build());
    }

    /**
     * 廣播紀錄明細（含 userResults JSON 解析）
     */
    @GetMapping("/broadcast-logs/{id}")
    public ResponseEntity<BroadcastLogDetail> getBroadcastLogDetail(@PathVariable Long id) {
        return broadcastLogRepository.findById(id)
                .map(l -> {
                    List<BroadcastLogResponse.UserResult> userResults = List.of();
                    if (l.getUserResults() != null) {
                        try {
                            userResults = objectMapper.readValue(l.getUserResults(),
                                    new TypeReference<List<BroadcastLogResponse.UserResult>>() {});
                        } catch (Exception e) {
                            log.warn("解析 userResults JSON 失敗: logId={}", id);
                        }
                    }

                    return ResponseEntity.ok(BroadcastLogDetail.builder()
                            .id(l.getId())
                            .signalAction(l.getSignalAction())
                            .symbol(l.getSymbol())
                            .side(l.getSide())
                            .entryPrice(l.getEntryPrice())
                            .stopLoss(l.getStopLoss())
                            .takeProfit(l.getTakeProfit())
                            .closeRatio(l.getCloseRatio())
                            .newStopLoss(l.getNewStopLoss())
                            .newTakeProfit(l.getNewTakeProfit())
                            .isDca(l.getIsDca())
                            .sourceAuthor(l.getSourceAuthor())
                            .totalUsers(l.getTotalUsers())
                            .successCount(l.getSuccessCount())
                            .failCount(l.getFailCount())
                            .skippedNoSub(l.getSkippedNoSub())
                            .skippedNoKey(l.getSkippedNoKey())
                            .status(l.getStatus())
                            .aiConfidence(l.getAiConfidence())
                            .aiReasoning(l.getAiReasoning())
                            .durationMs(l.getDurationMs())
                            .createdAt(l.getCreatedAt())
                            .userResults(userResults)
                            .build());
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ── 每日訊號日報 ──

    /**
     * 訊號日報列表（分頁）
     */
    @GetMapping("/daily-reports")
    public ResponseEntity<DailySignalReportResponse> getDailyReports(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DailySignalReport> reports = dailySignalReportService.getReports(page, size);

        List<ReportSummary> summaries = reports.getContent().stream()
                .map(r -> ReportSummary.builder()
                        .id(r.getId())
                        .reportDate(r.getReportDate())
                        .totalSignals(r.getTotalSignals())
                        .totalSources(r.getTotalSources())
                        .longCount(r.getLongCount())
                        .shortCount(r.getShortCount())
                        .avgConfidence(r.getAvgConfidence())
                        .hasAiAnalysis(r.getAiAnalysis() != null)
                        .createdAt(r.getCreatedAt())
                        .build())
                .toList();

        return ResponseEntity.ok(DailySignalReportResponse.builder()
                .content(summaries)
                .page(page)
                .size(size)
                .totalPages(reports.getTotalPages())
                .totalElements(reports.getTotalElements())
                .build());
    }

    /**
     * 訊號日報詳情（含 AI 分析全文 + reportData JSON）
     */
    @GetMapping("/daily-reports/{id}")
    public ResponseEntity<ReportDetail> getDailyReportDetail(@PathVariable Long id) {
        return dailySignalReportService.getReportById(id)
                .map(r -> ResponseEntity.ok(ReportDetail.builder()
                        .id(r.getId())
                        .reportDate(r.getReportDate())
                        .totalSignals(r.getTotalSignals())
                        .totalSources(r.getTotalSources())
                        .longCount(r.getLongCount())
                        .shortCount(r.getShortCount())
                        .avgConfidence(r.getAvgConfidence())
                        .reportData(r.getReportData())
                        .aiAnalysis(r.getAiAnalysis())
                        .aiTokensUsed(r.getAiTokensUsed())
                        .createdAt(r.getCreatedAt())
                        .build()))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * 手動產生指定日期的訊號日報
     */
    @PostMapping("/daily-reports/generate")
    public ResponseEntity<ReportDetail> generateDailyReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        DailySignalReport report = dailySignalReportService.generateReportForDate(date);
        return ResponseEntity.ok(ReportDetail.builder()
                .id(report.getId())
                .reportDate(report.getReportDate())
                .totalSignals(report.getTotalSignals())
                .totalSources(report.getTotalSources())
                .longCount(report.getLongCount())
                .shortCount(report.getShortCount())
                .avgConfidence(report.getAvgConfidence())
                .reportData(report.getReportData())
                .aiAnalysis(report.getAiAnalysis())
                .aiTokensUsed(report.getAiTokensUsed())
                .createdAt(report.getCreatedAt())
                .build());
    }
}
