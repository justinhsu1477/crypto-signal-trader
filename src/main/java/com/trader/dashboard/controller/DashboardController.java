package com.trader.dashboard.controller;

import com.trader.dashboard.dto.DashboardOverview;
import com.trader.dashboard.dto.PerformanceStats;
import com.trader.dashboard.dto.TradeHistoryResponse;
import com.trader.dashboard.service.DashboardService;
import com.trader.shared.util.SecurityUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Dashboard API
 *
 * 提供前端儀表板需要的所有數據：
 * - /overview — 首頁總覽（帳戶、風控、訂閱、持倉）
 * - /performance — 績效統計（勝率、PF、訊號排名、盈虧曲線）
 * - /trades — 交易歷史（分頁）
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 首頁總覽
     * GET /api/dashboard/overview
     *
     * 包含：帳戶餘額、持倉、今日盈虧、風控預算、訂閱狀態
     */
    @GetMapping("/overview")
    public ResponseEntity<DashboardOverview> getOverview() {
        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(dashboardService.getOverview(userId));
    }

    /**
     * 績效統計
     * GET /api/dashboard/performance?days=30
     *
     * 包含：摘要指標、出場原因分布、訊號來源排名、盈虧曲線
     */
    @GetMapping("/performance")
    public ResponseEntity<PerformanceStats> getPerformance(
            @RequestParam(defaultValue = "30") int days) {
        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(dashboardService.getPerformance(userId, days));
    }

    /**
     * 交易歷史（分頁）
     * GET /api/dashboard/trades?page=0&size=20
     */
    @GetMapping("/trades")
    public ResponseEntity<TradeHistoryResponse> getTradeHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(dashboardService.getTradeHistory(userId, page, size));
    }
}
