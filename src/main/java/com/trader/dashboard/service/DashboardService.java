package com.trader.dashboard.service;

import com.trader.dashboard.dto.DashboardOverview;
import com.trader.dashboard.dto.PerformanceStats;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.service.TradeRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Dashboard 聚合服務
 *
 * 整合 trading + subscription 模組的資料，
 * 提供前端 Dashboard 需要的各種摘要和統計。
 *
 * TODO: 實作完整邏輯（目前各方法回傳空殼資料）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TradeRecordService tradeRecordService;
    private final SubscriptionService subscriptionService;

    /**
     * 取得首頁摘要（持倉、今日盈虧、訂閱狀態）
     *
     * @param userId 用戶 ID
     */
    public DashboardOverview getOverview(String userId) {
        // TODO: 聚合各模組資料
        //   - tradeRecordService.findAllOpenTrades() → 持倉
        //   - tradeRecordService.getTodayStats() → 今日盈虧
        //   - subscriptionService.getStatus(userId) → 訂閱狀態
        return DashboardOverview.builder()
                .openPositions(0)
                .todayPnl(0)
                .todayTrades(0)
                .subscriptionPlan("none")
                .subscriptionActive(false)
                .build();
    }

    /**
     * 取得績效統計（勝率、PF、盈虧曲線）
     *
     * @param userId 用戶 ID
     * @param days   統計天數（例如 30, 90, 365）
     */
    public PerformanceStats getPerformance(String userId, int days) {
        // TODO: 從 tradeRecordService 取得歷史交易，計算績效指標
        //   - 勝率、PF、平均盈虧
        //   - 按日期聚合盈虧曲線資料點
        return PerformanceStats.builder()
                .totalTrades(0)
                .winRate(0)
                .profitFactor(0)
                .totalNetProfit(0)
                .avgProfitPerTrade(0)
                .maxWin(0)
                .maxLoss(0)
                .build();
    }
}
