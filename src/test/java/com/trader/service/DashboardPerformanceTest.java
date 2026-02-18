package com.trader.service;

import com.trader.dashboard.dto.PerformanceStats;
import com.trader.dashboard.service.DashboardService;
import com.trader.shared.config.RiskConfig;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.entity.Trade;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.TradeRecordService;
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
                tradeRecordService, subscriptionService, binanceFuturesService, riskConfig);
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
}
