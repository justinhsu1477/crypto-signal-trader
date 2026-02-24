package com.trader.dashboard.controller;

import com.trader.dashboard.dto.AdminSystemOverview;
import com.trader.dashboard.dto.AdminSystemOverview.UserTradingSummary;
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

    private final DashboardService dashboardService;
    private final UserRepository userRepository;

    /**
     * 系統全域概覽 — 所有用戶匯總 + per-user 摘要
     */
    @GetMapping("/system-overview")
    public ResponseEntity<AdminSystemOverview> getSystemOverview() {
        List<User> allUsers = userRepository.findAll();

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

            try {
                Map<String, Object> stats = dashboardService.getLightweightUserStats(user.getUserId());

                int openCount = (int) stats.get("openPositionCount");
                long closedCount = (long) stats.get("closedTradeCount");
                double netProfit = (double) stats.get("totalNetProfit");
                double todayPnl = (double) stats.get("todayPnl");
                int todayTrades = ((Long) stats.get("todayTradeCount")).intValue();

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
            } catch (Exception e) {
                log.warn("取得用戶 {} 統計失敗: {}", user.getUserId(), e.getMessage());
                summaries.add(UserTradingSummary.builder()
                        .userId(user.getUserId())
                        .email(user.getEmail())
                        .name(user.getName())
                        .enabled(user.isEnabled())
                        .autoTradeEnabled(user.isAutoTradeEnabled())
                        .build());
            }
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
}
