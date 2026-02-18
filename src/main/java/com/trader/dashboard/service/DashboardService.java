package com.trader.dashboard.service;

import com.trader.dashboard.dto.DashboardOverview;
import com.trader.dashboard.dto.PerformanceStats;
import com.trader.dashboard.dto.TradeHistoryResponse;
import com.trader.shared.config.AppConstants;
import com.trader.shared.config.RiskConfig;
import com.trader.subscription.dto.SubscriptionStatusResponse;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.entity.Trade;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.TradeRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dashboard 聚合服務
 *
 * 整合 trading + subscription + binance 模組的資料，
 * 提供前端 Dashboard 需要的各種摘要和統計。
 *
 * 差異化價值（幣安看不到的）：
 * - 訊號來源績效排名
 * - 風控預算即時狀態
 * - 出場原因分布
 * - 盈虧曲線
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TradeRecordService tradeRecordService;
    private final SubscriptionService subscriptionService;
    private final BinanceFuturesService binanceFuturesService;
    private final RiskConfig riskConfig;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ==================== Overview ====================

    /**
     * 取得首頁摘要（帳戶、風控、訂閱、持倉）
     */
    public DashboardOverview getOverview(String userId) {
        return DashboardOverview.builder()
                .account(buildAccountSummary())
                .riskBudget(buildRiskBudget())
                .subscription(buildSubscriptionInfo(userId))
                .positions(buildPositionList())
                .build();
    }

    private DashboardOverview.AccountSummary buildAccountSummary() {
        Map<String, Object> todayStats = tradeRecordService.getTodayStats();
        long todayTrades = (long) todayStats.get("trades");
        double todayPnl = (double) todayStats.get("netProfit");
        List<Trade> openTrades = tradeRecordService.findAllOpenTrades();

        double balance = 0;
        try {
            balance = binanceFuturesService.getAvailableBalance();
        } catch (Exception e) {
            log.warn("取得餘額失敗: {}", e.getMessage());
        }

        return DashboardOverview.AccountSummary.builder()
                .availableBalance(round2(balance))
                .openPositionCount(openTrades.size())
                .todayPnl(round2(todayPnl))
                .todayTradeCount((int) todayTrades)
                .build();
    }

    private DashboardOverview.RiskBudget buildRiskBudget() {
        double dailyLimit = riskConfig.getMaxDailyLossUsdt();
        double todayLoss = tradeRecordService.getTodayRealizedLoss(); // 負數
        double lossUsed = Math.abs(todayLoss);
        double remaining = Math.max(0, dailyLimit - lossUsed);

        return DashboardOverview.RiskBudget.builder()
                .dailyLossLimit(round2(dailyLimit))
                .todayLossUsed(round2(lossUsed))
                .remainingBudget(round2(remaining))
                .circuitBreakerActive(lossUsed >= dailyLimit)
                .build();
    }

    private DashboardOverview.SubscriptionInfo buildSubscriptionInfo(String userId) {
        try {
            SubscriptionStatusResponse status = subscriptionService.getStatus(userId);
            return DashboardOverview.SubscriptionInfo.builder()
                    .plan(status.getPlanId() != null ? status.getPlanId() : "none")
                    .active(status.isActive())
                    .expiresAt(status.getCurrentPeriodEnd() != null
                            ? status.getCurrentPeriodEnd().toString() : null)
                    .build();
        } catch (Exception e) {
            log.warn("取得訂閱狀態失敗: {}", e.getMessage());
            return DashboardOverview.SubscriptionInfo.builder()
                    .plan("none").active(false).build();
        }
    }

    private List<DashboardOverview.OpenPositionSummary> buildPositionList() {
        return tradeRecordService.findAllOpenTrades().stream()
                .map(t -> DashboardOverview.OpenPositionSummary.builder()
                        .symbol(t.getSymbol())
                        .side(t.getSide())
                        .entryPrice(t.getEntryPrice() != null ? t.getEntryPrice() : 0)
                        .stopLoss(t.getStopLoss())
                        .riskAmount(t.getRiskAmount())
                        .dcaCount(t.getDcaCount())
                        .signalSource(t.getSourceAuthorName())
                        .entryTime(t.getEntryTime() != null ? t.getEntryTime().toString() : null)
                        .build())
                .toList();
    }

    // ==================== Performance ====================

    /**
     * 取得績效統計（勝率、PF、訊號來源排名、盈虧曲線）
     */
    public PerformanceStats getPerformance(String userId, int days) {
        LocalDateTime since = LocalDate.now(AppConstants.ZONE_ID).minusDays(days).atStartOfDay();

        List<Trade> closedTrades = tradeRecordService.findAll().stream()
                .filter(t -> "CLOSED".equals(t.getStatus()))
                .filter(t -> t.getExitTime() != null && t.getExitTime().isAfter(since))
                .toList();

        return PerformanceStats.builder()
                .summary(buildSummary(closedTrades))
                .exitReasonBreakdown(closedTrades.stream()
                        .filter(t -> t.getExitReason() != null)
                        .collect(Collectors.groupingBy(Trade::getExitReason, Collectors.counting())))
                .signalSourceRanking(buildSignalSourceRanking(closedTrades))
                .pnlCurve(buildPnlCurve(closedTrades))
                .build();
    }

    private PerformanceStats.Summary buildSummary(List<Trade> closedTrades) {
        long total = closedTrades.size();
        long wins = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
        double winRate = total > 0 ? (double) wins / total * 100 : 0;

        double totalNet = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null)
                .mapToDouble(Trade::getNetProfit).sum();
        double grossWins = closedTrades.stream()
                .filter(t -> t.getGrossProfit() != null && t.getGrossProfit() > 0)
                .mapToDouble(Trade::getGrossProfit).sum();
        double grossLosses = closedTrades.stream()
                .filter(t -> t.getGrossProfit() != null && t.getGrossProfit() < 0)
                .mapToDouble(t -> Math.abs(t.getGrossProfit())).sum();
        double pf = grossLosses > 0 ? grossWins / grossLosses : 0;
        double totalCommission = closedTrades.stream()
                .filter(t -> t.getCommission() != null)
                .mapToDouble(Trade::getCommission).sum();
        double avgProfit = total > 0 ? totalNet / total : 0;
        double maxWin = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null)
                .mapToDouble(Trade::getNetProfit).max().orElse(0);
        double maxLoss = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null)
                .mapToDouble(Trade::getNetProfit).min().orElse(0);

        return PerformanceStats.Summary.builder()
                .totalTrades(total).winningTrades(wins)
                .winRate(round2(winRate)).profitFactor(round2(pf))
                .totalNetProfit(round2(totalNet)).avgProfitPerTrade(round2(avgProfit))
                .totalCommission(round2(totalCommission))
                .maxWin(round2(maxWin)).maxLoss(round2(maxLoss))
                .build();
    }

    private List<PerformanceStats.SignalSourceStats> buildSignalSourceRanking(List<Trade> closedTrades) {
        Map<String, List<Trade>> bySource = closedTrades.stream()
                .filter(t -> t.getSourceAuthorName() != null && !t.getSourceAuthorName().isBlank())
                .collect(Collectors.groupingBy(Trade::getSourceAuthorName));

        return bySource.entrySet().stream()
                .map(e -> {
                    List<Trade> trades = e.getValue();
                    long count = trades.size();
                    long wins = trades.stream()
                            .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
                    double netProfit = trades.stream()
                            .filter(t -> t.getNetProfit() != null)
                            .mapToDouble(Trade::getNetProfit).sum();
                    return PerformanceStats.SignalSourceStats.builder()
                            .source(e.getKey()).trades(count)
                            .winRate(round2(count > 0 ? (double) wins / count * 100 : 0))
                            .netProfit(round2(netProfit))
                            .build();
                })
                .sorted(Comparator.comparingDouble(PerformanceStats.SignalSourceStats::getNetProfit).reversed())
                .toList();
    }

    private List<PerformanceStats.PnlDataPoint> buildPnlCurve(List<Trade> closedTrades) {
        Map<LocalDate, Double> dailyPnl = new TreeMap<>();
        for (Trade t : closedTrades) {
            if (t.getExitTime() == null || t.getNetProfit() == null) continue;
            dailyPnl.merge(t.getExitTime().toLocalDate(), t.getNetProfit(), Double::sum);
        }

        List<PerformanceStats.PnlDataPoint> curve = new ArrayList<>();
        double cumulative = 0;
        for (Map.Entry<LocalDate, Double> entry : dailyPnl.entrySet()) {
            cumulative += entry.getValue();
            curve.add(PerformanceStats.PnlDataPoint.builder()
                    .date(entry.getKey().format(DATE_FMT))
                    .dailyPnl(round2(entry.getValue()))
                    .cumulativePnl(round2(cumulative))
                    .build());
        }
        return curve;
    }

    // ==================== Trade History ====================

    /**
     * 取得交易歷史（分頁）
     */
    public TradeHistoryResponse getTradeHistory(String userId, int page, int size) {
        List<Trade> allClosed = tradeRecordService.findByStatus("CLOSED");

        long totalElements = allClosed.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int fromIndex = Math.min(page * size, allClosed.size());
        int toIndex = Math.min(fromIndex + size, allClosed.size());
        List<Trade> pageContent = allClosed.subList(fromIndex, toIndex);

        List<TradeHistoryResponse.TradeRecord> records = pageContent.stream()
                .map(t -> TradeHistoryResponse.TradeRecord.builder()
                        .tradeId(t.getTradeId())
                        .symbol(t.getSymbol()).side(t.getSide())
                        .entryPrice(t.getEntryPrice()).exitPrice(t.getExitPrice())
                        .entryQuantity(t.getEntryQuantity())
                        .netProfit(t.getNetProfit()).exitReason(t.getExitReason())
                        .signalSource(t.getSourceAuthorName())
                        .dcaCount(t.getDcaCount())
                        .entryTime(t.getEntryTime() != null ? t.getEntryTime().toString() : null)
                        .exitTime(t.getExitTime() != null ? t.getExitTime().toString() : null)
                        .status(t.getStatus())
                        .build())
                .toList();

        return TradeHistoryResponse.builder()
                .trades(records)
                .pagination(TradeHistoryResponse.Pagination.builder()
                        .page(page).size(size)
                        .totalPages(totalPages).totalElements(totalElements)
                        .build())
                .build();
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
