package com.trader.dashboard.controller;

import com.trader.dashboard.dto.DashboardOverview;
import com.trader.dashboard.dto.PerformanceStats;
import com.trader.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 首頁摘要
     * GET /api/dashboard/overview
     *
     * TODO: 從 SecurityContext 取得 userId
     */
    @GetMapping("/overview")
    public ResponseEntity<DashboardOverview> getOverview() {
        // TODO: String userId = 從 JWT 取得;
        // return ResponseEntity.ok(dashboardService.getOverview(userId));
        return ResponseEntity.ok(dashboardService.getOverview("TODO"));
    }

    /**
     * 用戶交易記錄（分頁）
     * GET /api/dashboard/trades?status=CLOSED&page=0&size=20
     *
     * TODO: 從 SecurityContext 取得 userId，只回傳該用戶的交易
     */
    @GetMapping("/trades")
    public ResponseEntity<?> getUserTrades(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        // TODO: per-user 交易記錄查詢
        return ResponseEntity.ok(Map.of("status", "TODO", "message", "per-user trades 尚未實作"));
    }

    /**
     * 績效統計
     * GET /api/dashboard/performance?days=30
     *
     * TODO: 從 SecurityContext 取得 userId
     */
    @GetMapping("/performance")
    public ResponseEntity<PerformanceStats> getPerformance(
            @RequestParam(defaultValue = "30") int days) {
        // TODO: String userId = 從 JWT 取得;
        // return ResponseEntity.ok(dashboardService.getPerformance(userId, days));
        return ResponseEntity.ok(dashboardService.getPerformance("TODO", days));
    }
}
