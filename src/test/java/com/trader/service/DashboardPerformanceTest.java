package com.trader.service;

import com.trader.dashboard.dto.PerformanceStats;
import com.trader.dashboard.service.DashboardService;
import com.trader.shared.config.RiskConfig;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.entity.Trade;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.when;

/**
 * 測試 DashboardService 的進階績效指標計算。
 *
 * 策略：Mock TradeRecordService.findAll() 返回已知 Trade 列表，
 * 透過 getPerformance() 公開方法驗證所有指標。
 */
class DashboardPerformanceTest {

    private DashboardService dashboardService;
    private TradeRecordService tradeRecordService;

    @BeforeEach
    void setUp() {
        tradeRecordService = Mockito.mock(TradeRecordService.class);
        SubscriptionService subscriptionService = Mockito.mock(SubscriptionService.class);
        BinanceFuturesService binanceFuturesService = Mockito.mock(BinanceFuturesService.class);
        RiskConfig riskConfig = Mockito.mock(RiskConfig.class);

        dashboardService = new DashboardService(
                tradeRecordService, subscriptionService, binanceFuturesService, riskConfig, Mockito.mock(UserRepository.class),
                Mockito.mock(TradeConfigResolver.class));
    }

    /**
     * 建立 CLOSED Trade 的工具方法
     */
    private Trade closedTrade(String symbol, String side, double netProfit, double grossProfit,
                              LocalDateTime entryTime, LocalDateTime exitTime,
                              Integer dcaCount, String exitReason) {
        return Trade.builder()
                .symbol(symbol)
                .side(side)
                .status("CLOSED")
                .netProfit(netProfit)
                .grossProfit(grossProfit)
                .entryTime(entryTime)
                .exitTime(exitTime)
                .dcaCount(dcaCount)
                .exitReason(exitReason)
                .build();
    }

    // ==================== 基礎摘要 ====================

    @Nested
    @DisplayName("Summary 進階指標")
    class SummaryAdvanced {

        @Test
        @DisplayName("avgWin / avgLoss / riskRewardRatio / expectancy")
        void avgWinLossAndRatio() {
            // 3 wins: +100, +200, +300 → avgWin = 200
            // 2 losses: -50, -150 → avgLoss = -100
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusHours(2), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 200, 210, base.plusDays(1), base.plusDays(1).plusHours(3), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "SHORT", -50, -45, base.plusDays(2), base.plusDays(2).plusHours(1), 0, "STOP_LOSS"),
                    closedTrade("BTCUSDT", "LONG", 300, 310, base.plusDays(3), base.plusDays(3).plusHours(5), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "SHORT", -150, -140, base.plusDays(4), base.plusDays(4).plusHours(2), 0, "STOP_LOSS")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            PerformanceStats.Summary s = stats.getSummary();
            assertThat(s.getTotalTrades()).isEqualTo(5);
            assertThat(s.getWinningTrades()).isEqualTo(3);
            assertThat(s.getLosingTrades()).isEqualTo(2);
            assertThat(s.getAvgWin()).isCloseTo(200.0, within(0.01));
            assertThat(s.getAvgLoss()).isCloseTo(-100.0, within(0.01));
            // riskRewardRatio = |200| / |100| = 2.0
            assertThat(s.getRiskRewardRatio()).isCloseTo(2.0, within(0.01));
            // expectancy = (0.6 × 200) - (0.4 × 100) = 120 - 40 = 80
            assertThat(s.getExpectancy()).isCloseTo(80.0, within(0.01));
        }

        @Test
        @DisplayName("avgHoldingHours 計算")
        void avgHoldingHours() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            // Trade 1: 2 小時, Trade 2: 6 小時 → 平均 4 小時
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusHours(2), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 50, 60, base.plusDays(1), base.plusDays(1).plusHours(6), 0, "SIGNAL_CLOSE")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getSummary().getAvgHoldingHours()).isCloseTo(4.0, within(0.01));
        }
    }

    // ==================== 連勝連敗 ====================

    @Nested
    @DisplayName("連勝 / 連敗")
    class Streaks {

        @Test
        @DisplayName("W W W L L W → maxWin=3, maxLoss=2")
        void mixedStreaks() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusHours(1), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 50, 60, base, base.plusHours(2), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 80, 90, base, base.plusHours(3), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "SHORT", -30, -25, base, base.plusHours(4), 0, "STOP_LOSS"),
                    closedTrade("BTCUSDT", "SHORT", -20, -15, base, base.plusHours(5), 0, "STOP_LOSS"),
                    closedTrade("BTCUSDT", "LONG", 60, 70, base, base.plusHours(6), 0, "SIGNAL_CLOSE")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getSummary().getMaxConsecutiveWins()).isEqualTo(3);
            assertThat(stats.getSummary().getMaxConsecutiveLosses()).isEqualTo(2);
        }

        @Test
        @DisplayName("全勝 → maxWin=N, maxLoss=0")
        void allWins() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusHours(1), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 200, 210, base, base.plusHours(2), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 150, 160, base, base.plusHours(3), 0, "SIGNAL_CLOSE")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getSummary().getMaxConsecutiveWins()).isEqualTo(3);
            assertThat(stats.getSummary().getMaxConsecutiveLosses()).isEqualTo(0);
        }
    }

    // ==================== 最大回撤 ====================

    @Nested
    @DisplayName("最大回撤 (Max Drawdown)")
    class MaxDrawdown {

        @Test
        @DisplayName("先贏後虧 — peak=300, trough=300-250=50 → MDD=-250")
        void drawdownAfterPeak() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            // cumPnl: +100, +300, +200, +50
            // peak=300 at day2, trough=50 at day4 → dd=-250
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusDays(1), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 200, 210, base, base.plusDays(2), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "SHORT", -100, -90, base, base.plusDays(3), 0, "STOP_LOSS"),
                    closedTrade("BTCUSDT", "SHORT", -150, -140, base, base.plusDays(4), 0, "STOP_LOSS")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getSummary().getMaxDrawdown()).isCloseTo(-250.0, within(0.01));
            // -250 / 300 * 100 = -83.33%
            assertThat(stats.getSummary().getMaxDrawdownPercent()).isCloseTo(-83.33, within(0.01));
            // day2 → day4 = 2 days
            assertThat(stats.getSummary().getMaxDrawdownDays()).isEqualTo(2);
        }

        @Test
        @DisplayName("全勝 → drawdown = 0")
        void allWinsNoDrawdown() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusDays(1), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 200, 210, base, base.plusDays(2), 0, "SIGNAL_CLOSE")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getSummary().getMaxDrawdown()).isEqualTo(0.0);
            assertThat(stats.getSummary().getMaxDrawdownPercent()).isEqualTo(0.0);
            assertThat(stats.getSummary().getMaxDrawdownDays()).isEqualTo(0);
        }
    }

    // ==================== 幣種分組 ====================

    @Nested
    @DisplayName("幣種別績效 (SymbolStats)")
    class SymbolStatsTest {

        @Test
        @DisplayName("多幣種分組 — 排序按 netProfit DESC")
        void multipleSymbols() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 500, 520, base, base.plusHours(1), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", -100, -90, base, base.plusHours(2), 0, "STOP_LOSS"),
                    closedTrade("ETHUSDT", "SHORT", 200, 210, base, base.plusHours(3), 0, "SIGNAL_CLOSE"),
                    closedTrade("ETHUSDT", "SHORT", 100, 110, base, base.plusHours(4), 0, "SIGNAL_CLOSE")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getSymbolStats()).hasSize(2);
            // BTC: 500 + (-100) = 400 → 排第一
            assertThat(stats.getSymbolStats().get(0).getSymbol()).isEqualTo("BTCUSDT");
            assertThat(stats.getSymbolStats().get(0).getNetProfit()).isCloseTo(400.0, within(0.01));
            assertThat(stats.getSymbolStats().get(0).getTrades()).isEqualTo(2);
            assertThat(stats.getSymbolStats().get(0).getWins()).isEqualTo(1);
            // ETH: 200 + 100 = 300
            assertThat(stats.getSymbolStats().get(1).getSymbol()).isEqualTo("ETHUSDT");
            assertThat(stats.getSymbolStats().get(1).getNetProfit()).isCloseTo(300.0, within(0.01));
        }
    }

    // ==================== 多空對比 ====================

    @Nested
    @DisplayName("多空對比 (SideComparison)")
    class SideComparisonTest {

        @Test
        @DisplayName("LONG 2W1L vs SHORT 1W1L")
        void longVsShort() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusHours(1), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 200, 210, base, base.plusHours(2), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", -50, -40, base, base.plusHours(3), 0, "STOP_LOSS"),
                    closedTrade("BTCUSDT", "SHORT", 150, 160, base, base.plusHours(4), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "SHORT", -80, -70, base, base.plusHours(5), 0, "STOP_LOSS")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            PerformanceStats.SideStats longStats = stats.getSideComparison().getLongStats();
            assertThat(longStats.getTrades()).isEqualTo(3);
            assertThat(longStats.getWins()).isEqualTo(2);
            assertThat(longStats.getWinRate()).isCloseTo(66.67, within(0.01));
            assertThat(longStats.getNetProfit()).isCloseTo(250.0, within(0.01));

            PerformanceStats.SideStats shortStats = stats.getSideComparison().getShortStats();
            assertThat(shortStats.getTrades()).isEqualTo(2);
            assertThat(shortStats.getWins()).isEqualTo(1);
            assertThat(shortStats.getWinRate()).isCloseTo(50.0, within(0.01));
            assertThat(shortStats.getNetProfit()).isCloseTo(70.0, within(0.01));
        }
    }

    // ==================== DCA 分析 ====================

    @Nested
    @DisplayName("DCA 補倉分析")
    class DcaAnalysisTest {

        @Test
        @DisplayName("有補倉 vs 無補倉 勝率和平均獲利")
        void dcaVsNoDca() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    // 無 DCA: 2W1L, avgProfit = (100+200-50)/3 = 83.33
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusHours(1), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 200, 210, base, base.plusHours(2), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", -50, -40, base, base.plusHours(3), 0, "STOP_LOSS"),
                    // 有 DCA: 2W0L, avgProfit = (300+150)/2 = 225
                    closedTrade("BTCUSDT", "LONG", 300, 310, base, base.plusHours(4), 1, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 150, 160, base, base.plusHours(5), 2, "SIGNAL_CLOSE")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            PerformanceStats.DcaAnalysis dca = stats.getDcaAnalysis();
            assertThat(dca.getNoDcaTrades()).isEqualTo(3);
            assertThat(dca.getNoDcaWinRate()).isCloseTo(66.67, within(0.01));
            assertThat(dca.getNoDcaAvgProfit()).isCloseTo(83.33, within(0.01));
            assertThat(dca.getDcaTrades()).isEqualTo(2);
            assertThat(dca.getDcaWinRate()).isCloseTo(100.0, within(0.01));
            assertThat(dca.getDcaAvgProfit()).isCloseTo(225.0, within(0.01));
        }
    }

    // ==================== 星期幾績效 ====================

    @Nested
    @DisplayName("星期幾績效")
    class DayOfWeekTest {

        @Test
        @DisplayName("所有 7 天都有回傳，無交易的天為 0")
        void allDaysPresent() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 12, 12, 0); // 2026-01-12 是星期一
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base, 0, "SIGNAL_CLOSE"),            // Monday
                    closedTrade("BTCUSDT", "LONG", -50, -40, base, base.plusDays(2), 0, "STOP_LOSS")    // Wednesday
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getDayOfWeekStats()).hasSize(7);
            // Monday: 1 trade
            assertThat(stats.getDayOfWeekStats().get(0).getDayOfWeek()).isEqualTo("MONDAY");
            assertThat(stats.getDayOfWeekStats().get(0).getTrades()).isEqualTo(1);
            // Tuesday: 0 trades
            assertThat(stats.getDayOfWeekStats().get(1).getDayOfWeek()).isEqualTo("TUESDAY");
            assertThat(stats.getDayOfWeekStats().get(1).getTrades()).isEqualTo(0);
            // Wednesday: 1 trade
            assertThat(stats.getDayOfWeekStats().get(2).getDayOfWeek()).isEqualTo("WEDNESDAY");
            assertThat(stats.getDayOfWeekStats().get(2).getTrades()).isEqualTo(1);
        }
    }

    // ==================== PnL Curve 回撤 ====================

    @Nested
    @DisplayName("盈虧曲線含回撤")
    class PnlCurveDrawdown {

        @Test
        @DisplayName("回撤數據隨曲線計算")
        void drawdownOnCurve() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            // Day1: +200 → cum=200, peak=200, dd=0
            // Day2: -300 → cum=-100, peak=200, dd=-300 (-150%)
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 200, 210, base, base, 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "SHORT", -300, -290, base, base.plusDays(1), 0, "STOP_LOSS")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getPnlCurve()).hasSize(2);
            // Day1: no drawdown
            assertThat(stats.getPnlCurve().get(0).getDrawdown()).isEqualTo(0.0);
            // Day2: drawdown = -100 - 200 = -300
            assertThat(stats.getPnlCurve().get(1).getDrawdown()).isCloseTo(-300.0, within(0.01));
            assertThat(stats.getPnlCurve().get(1).getDrawdownPercent()).isCloseTo(-150.0, within(0.01));
        }
    }

    // ==================== 空列表 ====================

    @Nested
    @DisplayName("空資料處理")
    class EmptyData {

        @Test
        @DisplayName("無交易資料 — 所有指標返回 0 或空列表")
        void emptyTrades() {
            when(tradeRecordService.findAll()).thenReturn(List.of());
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            PerformanceStats.Summary s = stats.getSummary();
            assertThat(s.getTotalTrades()).isEqualTo(0);
            assertThat(s.getMaxDrawdown()).isEqualTo(0.0);
            assertThat(s.getMaxConsecutiveWins()).isEqualTo(0);
            assertThat(s.getExpectancy()).isEqualTo(0.0);
            assertThat(s.getAvgHoldingHours()).isEqualTo(0.0);

            assertThat(stats.getSymbolStats()).isEmpty();
            assertThat(stats.getWeeklyStats()).isEmpty();
            assertThat(stats.getMonthlyStats()).isEmpty();
            assertThat(stats.getDayOfWeekStats()).hasSize(7); // 7 天都有，trades=0
            assertThat(stats.getDayOfWeekStats().get(0).getTrades()).isEqualTo(0);

            PerformanceStats.DcaAnalysis dca = stats.getDcaAnalysis();
            assertThat(dca.getDcaTrades()).isEqualTo(0);
            assertThat(dca.getNoDcaTrades()).isEqualTo(0);
        }
    }

    // ==================== Profit Factor ====================

    @Nested
    @DisplayName("Profit Factor 計算")
    class ProfitFactorTest {

        @Test
        @DisplayName("grossWins=1000, grossLosses=500 → PF=2.0")
        void normalProfitFactor() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            // grossProfit > 0 的加總 = 600 + 400 = 1000
            // grossProfit < 0 的加總 = |(-300)| + |(-200)| = 500
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 580, 600, base, base.plusHours(1), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 380, 400, base, base.plusHours(2), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "SHORT", -310, -300, base, base.plusHours(3), 0, "STOP_LOSS"),
                    closedTrade("BTCUSDT", "SHORT", -210, -200, base, base.plusHours(4), 0, "STOP_LOSS")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getSummary().getProfitFactor()).isCloseTo(2.0, within(0.01));
        }

        @Test
        @DisplayName("全勝（無虧損） → PF=0（除以零保護）")
        void allWinsProfitFactor() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusHours(1), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 200, 210, base, base.plusHours(2), 0, "SIGNAL_CLOSE")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            // grossLosses = 0 → PF = 0（divide by zero protection）
            assertThat(stats.getSummary().getProfitFactor()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("全虧 → PF=0（grossWins=0）")
        void allLossesProfitFactor() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "SHORT", -100, -90, base, base.plusHours(1), 0, "STOP_LOSS"),
                    closedTrade("BTCUSDT", "SHORT", -200, -180, base, base.plusHours(2), 0, "STOP_LOSS")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getSummary().getProfitFactor()).isEqualTo(0.0);
        }
    }

    // ==================== 出場原因分布 ====================

    @Nested
    @DisplayName("出場原因分布 (ExitReasonBreakdown)")
    class ExitReasonBreakdownTest {

        @Test
        @DisplayName("多種出場原因 — 正確計數")
        void multipleExitReasons() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusHours(1), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 200, 210, base, base.plusHours(2), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "SHORT", -50, -45, base, base.plusHours(3), 0, "STOP_LOSS"),
                    closedTrade("BTCUSDT", "SHORT", -80, -70, base, base.plusHours(4), 0, "STOP_LOSS"),
                    closedTrade("BTCUSDT", "LONG", 150, 160, base, base.plusHours(5), 0, "STOP_LOSS"),
                    closedTrade("BTCUSDT", "LONG", 300, 310, base, base.plusHours(6), 0, "FAIL_SAFE")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getExitReasonBreakdown()).containsEntry("SIGNAL_CLOSE", 2L);
            assertThat(stats.getExitReasonBreakdown()).containsEntry("STOP_LOSS", 3L);
            assertThat(stats.getExitReasonBreakdown()).containsEntry("FAIL_SAFE", 1L);
        }

        @Test
        @DisplayName("exitReason 為 null 的交易 → 不計入分布")
        void nullExitReason() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusHours(1), 0, null),
                    closedTrade("BTCUSDT", "LONG", 200, 210, base, base.plusHours(2), 0, "SIGNAL_CLOSE")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getExitReasonBreakdown()).containsEntry("SIGNAL_CLOSE", 1L);
            assertThat(stats.getExitReasonBreakdown()).doesNotContainKey(null);
        }
    }

    // ==================== 訊號來源排名 ====================

    @Nested
    @DisplayName("訊號來源排名 (SignalSourceRanking)")
    class SignalSourceRankingTest {

        @Test
        @DisplayName("多來源 — 按 netProfit 降序排列")
        void multipleSourcesRanking() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTradeWithSource("BTCUSDT", "LONG", 500, 520, base, base.plusHours(1), "陳哥"),
                    closedTradeWithSource("BTCUSDT", "LONG", -100, -90, base, base.plusHours(2), "陳哥"),
                    closedTradeWithSource("ETHUSDT", "SHORT", 200, 210, base, base.plusHours(3), "老王"),
                    closedTradeWithSource("ETHUSDT", "SHORT", 300, 310, base, base.plusHours(4), "老王")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getSignalSourceRanking()).hasSize(2);
            // 老王: 200+300=500 → 排第一
            assertThat(stats.getSignalSourceRanking().get(0).getSource()).isEqualTo("老王");
            assertThat(stats.getSignalSourceRanking().get(0).getNetProfit()).isCloseTo(500.0, within(0.01));
            assertThat(stats.getSignalSourceRanking().get(0).getTrades()).isEqualTo(2);
            assertThat(stats.getSignalSourceRanking().get(0).getWinRate()).isCloseTo(100.0, within(0.01));
            // 陳哥: 500+(-100)=400
            assertThat(stats.getSignalSourceRanking().get(1).getSource()).isEqualTo("陳哥");
            assertThat(stats.getSignalSourceRanking().get(1).getNetProfit()).isCloseTo(400.0, within(0.01));
            assertThat(stats.getSignalSourceRanking().get(1).getWinRate()).isCloseTo(50.0, within(0.01));
        }

        @Test
        @DisplayName("sourceAuthorName 為空白 → 不計入排名")
        void blankSourceExcluded() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTradeWithSource("BTCUSDT", "LONG", 100, 110, base, base.plusHours(1), ""),
                    closedTradeWithSource("BTCUSDT", "LONG", 200, 210, base, base.plusHours(2), "陳哥")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getSignalSourceRanking()).hasSize(1);
            assertThat(stats.getSignalSourceRanking().get(0).getSource()).isEqualTo("陳哥");
        }
    }

    // ==================== 月統計 ====================

    @Nested
    @DisplayName("月統計 (MonthlyStats)")
    class MonthlyStatsTest {

        @Test
        @DisplayName("跨月交易 → 正確分組")
        void crossMonthTrades() {
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110,
                            LocalDateTime.of(2026, 1, 15, 10, 0),
                            LocalDateTime.of(2026, 1, 15, 14, 0), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 200, 210,
                            LocalDateTime.of(2026, 1, 20, 10, 0),
                            LocalDateTime.of(2026, 1, 20, 16, 0), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "SHORT", -50, -45,
                            LocalDateTime.of(2026, 2, 5, 10, 0),
                            LocalDateTime.of(2026, 2, 5, 12, 0), 0, "STOP_LOSS")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getMonthlyStats()).hasSize(2);
            // 2026-01: 2 trades, +300
            assertThat(stats.getMonthlyStats().get(0).getMonth()).isEqualTo("2026-01");
            assertThat(stats.getMonthlyStats().get(0).getTrades()).isEqualTo(2);
            assertThat(stats.getMonthlyStats().get(0).getNetProfit()).isCloseTo(300.0, within(0.01));
            // 2026-02: 1 trade, -50
            assertThat(stats.getMonthlyStats().get(1).getMonth()).isEqualTo("2026-02");
            assertThat(stats.getMonthlyStats().get(1).getTrades()).isEqualTo(1);
        }
    }

    // ==================== 週統計 ====================

    @Nested
    @DisplayName("週統計 (WeeklyStats)")
    class WeeklyStatsTest {

        @Test
        @DisplayName("跨週交易 → 分成多個週")
        void crossWeekTrades() {
            // 2026-01-12 = Monday, 2026-01-19 = next Monday
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110,
                            LocalDateTime.of(2026, 1, 12, 10, 0),
                            LocalDateTime.of(2026, 1, 12, 14, 0), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 200, 210,
                            LocalDateTime.of(2026, 1, 14, 10, 0),
                            LocalDateTime.of(2026, 1, 14, 16, 0), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "SHORT", -50, -45,
                            LocalDateTime.of(2026, 1, 19, 10, 0),
                            LocalDateTime.of(2026, 1, 19, 12, 0), 0, "STOP_LOSS")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getWeeklyStats()).hasSize(2);
            // 第一週 2 trades
            assertThat(stats.getWeeklyStats().get(0).getTrades()).isEqualTo(2);
            assertThat(stats.getWeeklyStats().get(0).getNetProfit()).isCloseTo(300.0, within(0.01));
            // 第二週 1 trade
            assertThat(stats.getWeeklyStats().get(1).getTrades()).isEqualTo(1);
        }
    }

    // ==================== 單筆交易 ====================

    @Nested
    @DisplayName("單筆交易邊界情境")
    class SingleTradeTest {

        @Test
        @DisplayName("只有一筆獲利 → 所有指標正確")
        void singleWin() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusHours(3), 0, "SIGNAL_CLOSE")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            PerformanceStats.Summary s = stats.getSummary();
            assertThat(s.getTotalTrades()).isEqualTo(1);
            assertThat(s.getWinningTrades()).isEqualTo(1);
            assertThat(s.getLosingTrades()).isEqualTo(0);
            assertThat(s.getWinRate()).isCloseTo(100.0, within(0.01));
            assertThat(s.getMaxWin()).isCloseTo(100.0, within(0.01));
            assertThat(s.getMaxLoss()).isCloseTo(100.0, within(0.01)); // min of [100] = 100
            assertThat(s.getMaxConsecutiveWins()).isEqualTo(1);
            assertThat(s.getMaxConsecutiveLosses()).isEqualTo(0);
            assertThat(s.getMaxDrawdown()).isEqualTo(0.0);
            assertThat(s.getAvgHoldingHours()).isCloseTo(3.0, within(0.01));
        }

        @Test
        @DisplayName("只有一筆虧損 → expectancy 為負")
        void singleLoss() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "SHORT", -150, -140, base, base.plusHours(1), 0, "STOP_LOSS")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            PerformanceStats.Summary s = stats.getSummary();
            assertThat(s.getTotalTrades()).isEqualTo(1);
            assertThat(s.getWinningTrades()).isEqualTo(0);
            assertThat(s.getWinRate()).isEqualTo(0.0);
            // expectancy = (0 × 0) - (1 × 150) = -150
            assertThat(s.getExpectancy()).isCloseTo(-150.0, within(0.01));
        }
    }

    // ==================== SideComparison 邊界 ====================

    @Nested
    @DisplayName("多空對比 — 邊界情境")
    class SideComparisonEdgeCases {

        @Test
        @DisplayName("只有 LONG 交易 → SHORT stats 全為 0")
        void onlyLongTrades() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusHours(1), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", 200, 210, base, base.plusHours(2), 0, "SIGNAL_CLOSE")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            assertThat(stats.getSideComparison().getLongStats().getTrades()).isEqualTo(2);
            assertThat(stats.getSideComparison().getShortStats().getTrades()).isEqualTo(0);
            assertThat(stats.getSideComparison().getShortStats().getWinRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Profit Factor — LONG 有虧損, SHORT 全勝")
        void profitFactorComparison() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusHours(1), 0, "SIGNAL_CLOSE"),
                    closedTrade("BTCUSDT", "LONG", -50, -40, base, base.plusHours(2), 0, "STOP_LOSS"),
                    closedTrade("BTCUSDT", "SHORT", 200, 220, base, base.plusHours(3), 0, "SIGNAL_CLOSE")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            // LONG: grossWins=110, grossLosses=40 → PF = 110/40 = 2.75
            assertThat(stats.getSideComparison().getLongStats().getProfitFactor()).isCloseTo(2.75, within(0.01));
            // SHORT: grossWins=220, grossLosses=0 → PF = 0 (divide by zero)
            assertThat(stats.getSideComparison().getShortStats().getProfitFactor()).isEqualTo(0.0);
        }
    }

    // ==================== netProfit null 處理 ====================

    @Nested
    @DisplayName("netProfit null 處理")
    class NullNetProfitTest {

        @Test
        @DisplayName("部分 Trade netProfit 為 null → 正確過濾，不影響計算")
        void someNullNetProfit() {
            LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
            List<Trade> trades = List.of(
                    closedTrade("BTCUSDT", "LONG", 100, 110, base, base.plusHours(1), 0, "SIGNAL_CLOSE"),
                    Trade.builder()
                            .symbol("BTCUSDT").side("LONG").status("CLOSED")
                            .netProfit(null).grossProfit(null)
                            .entryTime(base).exitTime(base.plusHours(2))
                            .exitReason("SIGNAL_CLOSE").dcaCount(0)
                            .build(),
                    closedTrade("BTCUSDT", "SHORT", -50, -45, base, base.plusHours(3), 0, "STOP_LOSS")
            );

            when(tradeRecordService.findAll()).thenReturn(trades);
            PerformanceStats stats = dashboardService.getPerformance("user1", 365);

            // netProfit null 的 trade 不算 winning（netProfit > 0 是 false）
            PerformanceStats.Summary s = stats.getSummary();
            assertThat(s.getTotalTrades()).isEqualTo(3);
            assertThat(s.getWinningTrades()).isEqualTo(1);
            assertThat(s.getLosingTrades()).isEqualTo(2); // null 算 loss
            // totalNetProfit: 100 + (-50) = 50（null 被過濾）
            assertThat(s.getTotalNetProfit()).isCloseTo(50.0, within(0.01));
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 建立帶有訊號來源的 CLOSED Trade
     */
    private Trade closedTradeWithSource(String symbol, String side, double netProfit, double grossProfit,
                                         LocalDateTime entryTime, LocalDateTime exitTime, String source) {
        return Trade.builder()
                .symbol(symbol)
                .side(side)
                .status("CLOSED")
                .netProfit(netProfit)
                .grossProfit(grossProfit)
                .entryTime(entryTime)
                .exitTime(exitTime)
                .dcaCount(0)
                .exitReason("SIGNAL_CLOSE")
                .sourceAuthorName(source)
                .build();
    }
}
