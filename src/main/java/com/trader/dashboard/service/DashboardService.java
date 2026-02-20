package com.trader.dashboard.service;

import com.trader.dashboard.dto.DashboardOverview;
import com.trader.dashboard.dto.PerformanceStats;
import com.trader.dashboard.dto.TradeHistoryResponse;
import com.trader.shared.config.AppConstants;
import com.trader.shared.config.RiskConfig;
import com.trader.subscription.dto.SubscriptionStatusResponse;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
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
 * - 盈虧曲線 + 回撤疊加
 * - 幣種 / 多空 / 時間分組
 * - DCA 補倉效果分析
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TradeRecordService tradeRecordService;
    private final SubscriptionService subscriptionService;
    private final BinanceFuturesService binanceFuturesService;
    private final RiskConfig riskConfig;
    private final UserRepository userRepository;
    private final TradeConfigResolver tradeConfigResolver;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    // ==================== Overview ====================

    /**
     * 取得首頁摘要（帳戶、風控、訂閱、持倉、自動跟單狀態）
     */
    public DashboardOverview getOverview(String userId) {
        boolean autoTradeEnabled = userRepository.findById(userId)
                .map(u -> u.isAutoTradeEnabled())
                .orElse(false);

        return DashboardOverview.builder()
                .account(buildAccountSummary())
                .riskBudget(buildRiskBudget(userId))
                .subscription(buildSubscriptionInfo(userId))
                .autoTradeEnabled(autoTradeEnabled)
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

    private DashboardOverview.RiskBudget buildRiskBudget(String userId) {
        EffectiveTradeConfig config = tradeConfigResolver.resolve(userId);
        double dailyLimit = config.maxDailyLossUsdt();
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
     * 取得績效統計（勝率、PF、訊號來源排名、盈虧曲線、進階分析）
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
                // === 進階分析 ===
                .symbolStats(buildSymbolStats(closedTrades))
                .sideComparison(buildSideComparison(closedTrades))
                .weeklyStats(buildWeeklyStats(closedTrades))
                .monthlyStats(buildMonthlyStats(closedTrades))
                .dayOfWeekStats(buildDayOfWeekStats(closedTrades))
                .dcaAnalysis(buildDcaAnalysis(closedTrades))
                .build();
    }

    // ==================== Summary（含進階指標） ====================

    private PerformanceStats.Summary buildSummary(List<Trade> closedTrades) {
        long total = closedTrades.size();
        long wins = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
        long losses = total - wins;
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

        // === 進階指標 ===

        // 平均獲利 / 平均虧損
        double avgWin = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0)
                .mapToDouble(Trade::getNetProfit).average().orElse(0);
        double avgLoss = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null && t.getNetProfit() <= 0)
                .mapToDouble(Trade::getNetProfit).average().orElse(0);

        // 風報比 = |avgWin| / |avgLoss|
        double riskRewardRatio = avgLoss != 0 ? Math.abs(avgWin / avgLoss) : 0;

        // 期望值 = (winPct × avgWin) - (lossPct × |avgLoss|)
        double winPct = total > 0 ? (double) wins / total : 0;
        double lossPct = total > 0 ? (double) losses / total : 0;
        double expectancy = (winPct * avgWin) - (lossPct * Math.abs(avgLoss));

        // 最大連勝 / 連敗
        int[] streaks = calculateStreaks(closedTrades);

        // 最大回撤
        double[] drawdownResult = calculateMaxDrawdown(closedTrades);

        // 平均持倉時間（小時）
        double avgHoldingHours = closedTrades.stream()
                .filter(t -> t.getEntryTime() != null && t.getExitTime() != null)
                .mapToLong(t -> Duration.between(t.getEntryTime(), t.getExitTime()).toMinutes())
                .average().orElse(0) / 60.0;

        return PerformanceStats.Summary.builder()
                .totalTrades(total).winningTrades(wins).losingTrades(losses)
                .winRate(round2(winRate)).profitFactor(round2(pf))
                .totalNetProfit(round2(totalNet)).avgProfitPerTrade(round2(avgProfit))
                .totalCommission(round2(totalCommission))
                .maxWin(round2(maxWin)).maxLoss(round2(maxLoss))
                .avgWin(round2(avgWin)).avgLoss(round2(avgLoss))
                .riskRewardRatio(round2(riskRewardRatio))
                .expectancy(round2(expectancy))
                .maxConsecutiveWins(streaks[0]).maxConsecutiveLosses(streaks[1])
                .maxDrawdown(round2(drawdownResult[0]))
                .maxDrawdownPercent(round2(drawdownResult[1]))
                .maxDrawdownDays((int) drawdownResult[2])
                .avgHoldingHours(round2(avgHoldingHours))
                .build();
    }

    /**
     * 計算最大連勝和最大連敗
     * @return [maxConsecutiveWins, maxConsecutiveLosses]
     */
    private int[] calculateStreaks(List<Trade> closedTrades) {
        List<Trade> sorted = closedTrades.stream()
                .filter(t -> t.getExitTime() != null)
                .sorted(Comparator.comparing(Trade::getExitTime))
                .toList();

        int maxWins = 0, maxLosses = 0, currentWins = 0, currentLosses = 0;
        for (Trade t : sorted) {
            if (t.getNetProfit() != null && t.getNetProfit() > 0) {
                currentWins++;
                currentLosses = 0;
                maxWins = Math.max(maxWins, currentWins);
            } else {
                currentLosses++;
                currentWins = 0;
                maxLosses = Math.max(maxLosses, currentLosses);
            }
        }
        return new int[]{maxWins, maxLosses};
    }

    /**
     * 計算最大回撤 (金額、百分比、天數)
     * 遍歷按 exitTime 排序的交易，累計 equity curve，追蹤 peak-to-trough。
     *
     * @return [maxDrawdownUsdt, maxDrawdownPercent, maxDrawdownDays]
     */
    private double[] calculateMaxDrawdown(List<Trade> closedTrades) {
        List<Trade> sorted = closedTrades.stream()
                .filter(t -> t.getExitTime() != null && t.getNetProfit() != null)
                .sorted(Comparator.comparing(Trade::getExitTime))
                .toList();

        if (sorted.isEmpty()) return new double[]{0, 0, 0};

        double cumPnl = 0;
        double peak = 0;
        double maxDd = 0;
        double maxDdPercent = 0;
        LocalDate peakDate = sorted.get(0).getExitTime().toLocalDate();
        LocalDate troughDate = peakDate;
        int maxDdDays = 0;

        for (Trade t : sorted) {
            cumPnl += t.getNetProfit();
            if (cumPnl > peak) {
                peak = cumPnl;
                peakDate = t.getExitTime().toLocalDate();
            }
            double dd = cumPnl - peak; // 負數或零
            if (dd < maxDd) {
                maxDd = dd;
                maxDdPercent = peak > 0 ? (dd / peak) * 100 : 0;
                troughDate = t.getExitTime().toLocalDate();
                maxDdDays = (int) ChronoUnit.DAYS.between(peakDate, troughDate);
            }
        }
        return new double[]{maxDd, maxDdPercent, maxDdDays};
    }

    // ==================== 訊號來源排名 ====================

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

    // ==================== 盈虧曲線（含回撤） ====================

    private List<PerformanceStats.PnlDataPoint> buildPnlCurve(List<Trade> closedTrades) {
        Map<LocalDate, Double> dailyPnl = new TreeMap<>();
        for (Trade t : closedTrades) {
            if (t.getExitTime() == null || t.getNetProfit() == null) continue;
            dailyPnl.merge(t.getExitTime().toLocalDate(), t.getNetProfit(), Double::sum);
        }

        List<PerformanceStats.PnlDataPoint> curve = new ArrayList<>();
        double cumulative = 0;
        double peak = 0;
        for (Map.Entry<LocalDate, Double> entry : dailyPnl.entrySet()) {
            cumulative += entry.getValue();
            if (cumulative > peak) peak = cumulative;
            double dd = cumulative - peak;
            double ddPercent = peak > 0 ? (dd / peak) * 100 : 0;

            curve.add(PerformanceStats.PnlDataPoint.builder()
                    .date(entry.getKey().format(DATE_FMT))
                    .dailyPnl(round2(entry.getValue()))
                    .cumulativePnl(round2(cumulative))
                    .drawdown(round2(dd))
                    .drawdownPercent(round2(ddPercent))
                    .build());
        }
        return curve;
    }

    // ==================== 幣種別績效 ====================

    private List<PerformanceStats.SymbolStats> buildSymbolStats(List<Trade> closedTrades) {
        Map<String, List<Trade>> bySymbol = closedTrades.stream()
                .filter(t -> t.getSymbol() != null)
                .collect(Collectors.groupingBy(Trade::getSymbol));

        return bySymbol.entrySet().stream()
                .map(e -> {
                    List<Trade> trades = e.getValue();
                    long count = trades.size();
                    long wins = trades.stream()
                            .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
                    double netProfit = trades.stream()
                            .filter(t -> t.getNetProfit() != null)
                            .mapToDouble(Trade::getNetProfit).sum();
                    return PerformanceStats.SymbolStats.builder()
                            .symbol(e.getKey())
                            .trades(count).wins(wins)
                            .winRate(round2(count > 0 ? (double) wins / count * 100 : 0))
                            .netProfit(round2(netProfit))
                            .avgProfit(round2(count > 0 ? netProfit / count : 0))
                            .build();
                })
                .sorted(Comparator.comparingDouble(PerformanceStats.SymbolStats::getNetProfit).reversed())
                .toList();
    }

    // ==================== 多空對比 ====================

    private PerformanceStats.SideComparison buildSideComparison(List<Trade> closedTrades) {
        return PerformanceStats.SideComparison.builder()
                .longStats(buildSideStats(closedTrades, "LONG"))
                .shortStats(buildSideStats(closedTrades, "SHORT"))
                .build();
    }

    private PerformanceStats.SideStats buildSideStats(List<Trade> closedTrades, String side) {
        List<Trade> filtered = closedTrades.stream()
                .filter(t -> side.equals(t.getSide()))
                .toList();

        long count = filtered.size();
        long wins = filtered.stream()
                .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
        double netProfit = filtered.stream()
                .filter(t -> t.getNetProfit() != null)
                .mapToDouble(Trade::getNetProfit).sum();
        double grossWins = filtered.stream()
                .filter(t -> t.getGrossProfit() != null && t.getGrossProfit() > 0)
                .mapToDouble(Trade::getGrossProfit).sum();
        double grossLosses = filtered.stream()
                .filter(t -> t.getGrossProfit() != null && t.getGrossProfit() < 0)
                .mapToDouble(t -> Math.abs(t.getGrossProfit())).sum();

        return PerformanceStats.SideStats.builder()
                .trades(count).wins(wins)
                .winRate(round2(count > 0 ? (double) wins / count * 100 : 0))
                .netProfit(round2(netProfit))
                .avgProfit(round2(count > 0 ? netProfit / count : 0))
                .profitFactor(round2(grossLosses > 0 ? grossWins / grossLosses : 0))
                .build();
    }

    // ==================== 週統計 ====================

    private List<PerformanceStats.WeeklyStats> buildWeeklyStats(List<Trade> closedTrades) {
        WeekFields weekFields = WeekFields.ISO;

        Map<String, List<Trade>> byWeek = closedTrades.stream()
                .filter(t -> t.getExitTime() != null)
                .collect(Collectors.groupingBy(t -> {
                    LocalDate date = t.getExitTime().toLocalDate();
                    int year = date.get(weekFields.weekBasedYear());
                    int week = date.get(weekFields.weekOfWeekBasedYear());
                    return String.format("%d-W%02d", year, week);
                }, TreeMap::new, Collectors.toList()));

        return byWeek.entrySet().stream()
                .map(e -> {
                    List<Trade> trades = e.getValue();
                    long count = trades.size();
                    long wins = trades.stream()
                            .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
                    double netProfit = trades.stream()
                            .filter(t -> t.getNetProfit() != null)
                            .mapToDouble(Trade::getNetProfit).sum();
                    LocalDate earliest = trades.stream()
                            .map(t -> t.getExitTime().toLocalDate())
                            .min(Comparator.naturalOrder()).orElse(LocalDate.now(AppConstants.ZONE_ID));
                    LocalDate latest = trades.stream()
                            .map(t -> t.getExitTime().toLocalDate())
                            .max(Comparator.naturalOrder()).orElse(LocalDate.now(AppConstants.ZONE_ID));

                    return PerformanceStats.WeeklyStats.builder()
                            .weekStart(earliest.format(DATE_FMT))
                            .weekEnd(latest.format(DATE_FMT))
                            .trades(count)
                            .netProfit(round2(netProfit))
                            .winRate(round2(count > 0 ? (double) wins / count * 100 : 0))
                            .build();
                })
                .toList();
    }

    // ==================== 月統計 ====================

    private List<PerformanceStats.MonthlyStats> buildMonthlyStats(List<Trade> closedTrades) {
        Map<String, List<Trade>> byMonth = closedTrades.stream()
                .filter(t -> t.getExitTime() != null)
                .collect(Collectors.groupingBy(
                        t -> t.getExitTime().toLocalDate().format(MONTH_FMT),
                        TreeMap::new, Collectors.toList()));

        return byMonth.entrySet().stream()
                .map(e -> {
                    List<Trade> trades = e.getValue();
                    long count = trades.size();
                    long wins = trades.stream()
                            .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
                    double netProfit = trades.stream()
                            .filter(t -> t.getNetProfit() != null)
                            .mapToDouble(Trade::getNetProfit).sum();
                    return PerformanceStats.MonthlyStats.builder()
                            .month(e.getKey())
                            .trades(count)
                            .netProfit(round2(netProfit))
                            .winRate(round2(count > 0 ? (double) wins / count * 100 : 0))
                            .build();
                })
                .toList();
    }

    // ==================== 星期幾績效 ====================

    private List<PerformanceStats.DayOfWeekStats> buildDayOfWeekStats(List<Trade> closedTrades) {
        Map<DayOfWeek, List<Trade>> byDay = closedTrades.stream()
                .filter(t -> t.getExitTime() != null)
                .collect(Collectors.groupingBy(t -> t.getExitTime().getDayOfWeek()));

        return Arrays.stream(DayOfWeek.values())
                .map(day -> {
                    List<Trade> trades = byDay.getOrDefault(day, List.of());
                    long count = trades.size();
                    long wins = trades.stream()
                            .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
                    double netProfit = trades.stream()
                            .filter(t -> t.getNetProfit() != null)
                            .mapToDouble(Trade::getNetProfit).sum();
                    return PerformanceStats.DayOfWeekStats.builder()
                            .dayOfWeek(day.name())
                            .trades(count)
                            .netProfit(round2(netProfit))
                            .winRate(round2(count > 0 ? (double) wins / count * 100 : 0))
                            .build();
                })
                .toList();
    }

    // ==================== DCA 補倉分析 ====================

    private PerformanceStats.DcaAnalysis buildDcaAnalysis(List<Trade> closedTrades) {
        Map<Boolean, List<Trade>> partitioned = closedTrades.stream()
                .collect(Collectors.partitioningBy(t -> t.getDcaCount() != null && t.getDcaCount() > 0));

        List<Trade> dcaTrades = partitioned.get(true);
        List<Trade> noDcaTrades = partitioned.get(false);

        return PerformanceStats.DcaAnalysis.builder()
                .noDcaTrades(noDcaTrades.size())
                .noDcaWinRate(round2(calcWinRate(noDcaTrades)))
                .noDcaAvgProfit(round2(calcAvgProfit(noDcaTrades)))
                .dcaTrades(dcaTrades.size())
                .dcaWinRate(round2(calcWinRate(dcaTrades)))
                .dcaAvgProfit(round2(calcAvgProfit(dcaTrades)))
                .build();
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

    // ==================== Utility ====================

    private double calcWinRate(List<Trade> trades) {
        if (trades.isEmpty()) return 0;
        long wins = trades.stream()
                .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
        return (double) wins / trades.size() * 100;
    }

    private double calcAvgProfit(List<Trade> trades) {
        if (trades.isEmpty()) return 0;
        return trades.stream()
                .filter(t -> t.getNetProfit() != null)
                .mapToDouble(Trade::getNetProfit).average().orElse(0);
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
