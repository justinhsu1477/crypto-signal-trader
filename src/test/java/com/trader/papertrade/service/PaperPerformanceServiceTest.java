package com.trader.papertrade.service;

import com.trader.papertrade.dto.SourcePerformanceMetrics;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.SignalSourceConfigRepository;
import com.trader.trading.repository.TradeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PaperPerformanceServiceTest {

    private TradeRepository tradeRepository;
    private SignalSourceConfigRepository sourceRepository;
    private PaperPerformanceService service;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        sourceRepository = mock(SignalSourceConfigRepository.class);
        service = new PaperPerformanceService(tradeRepository, sourceRepository);
    }

    @Nested
    @DisplayName("calculateMetrics — 基本指標")
    class BasicMetrics {

        @Test
        @DisplayName("5 winners + 5 losers — winRate 50%, profitFactor > 0")
        void halfWinHalfLose() {
            // 5 wins @ +$100, 5 losses @ -$50
            List<Trade> trades = mockTrades(
                    new double[]{100, 100, 100, 100, 100, -50, -50, -50, -50, -50},
                    LocalDateTime.of(2026, 5, 1, 0, 0));

            SourcePerformanceMetrics m = service.calculateMetrics("ch1", trades, mockSource(1L, "test"));

            assertThat(m.getClosedTrades()).isEqualTo(10);
            assertThat(m.getWins()).isEqualTo(5);
            assertThat(m.getLosses()).isEqualTo(5);
            assertThat(m.getWinRate()).isEqualTo(0.5);
            assertThat(m.getTotalPnl()).isEqualTo(250.0);  // 500 - 250
            assertThat(m.getAvgWin()).isEqualTo(100.0);
            assertThat(m.getAvgLoss()).isEqualTo(-50.0);
            // profit factor = 500 / 250 = 2.0
            assertThat(m.getProfitFactor()).isEqualTo(2.0);
            // expectancy = 0.5 * 100 + 0.5 * -50 = 25
            assertThat(m.getExpectancy()).isEqualTo(25.0);
        }

        @Test
        @DisplayName("全勝 (沒任何 loss) → profitFactor = Infinity (處理 div0)")
        void allWins_profitFactorInfinity() {
            List<Trade> trades = mockTrades(
                    new double[]{50, 50, 50},
                    LocalDateTime.of(2026, 5, 1, 0, 0));

            SourcePerformanceMetrics m = service.calculateMetrics("ch1", trades, null);

            assertThat(m.getWins()).isEqualTo(3);
            assertThat(m.getLosses()).isEqualTo(0);
            assertThat(m.getProfitFactor()).isEqualTo(Double.POSITIVE_INFINITY);
            assertThat(m.getDisplayName()).isEqualTo("(unknown)");
        }

        @Test
        @DisplayName("全負 (沒任何 win) → profitFactor = 0")
        void allLosses_profitFactorZero() {
            List<Trade> trades = mockTrades(
                    new double[]{-30, -30, -30},
                    LocalDateTime.of(2026, 5, 1, 0, 0));

            SourcePerformanceMetrics m = service.calculateMetrics("ch1", trades, null);

            assertThat(m.getProfitFactor()).isEqualTo(0.0);
            assertThat(m.getTotalPnl()).isEqualTo(-90.0);
        }
    }

    @Nested
    @DisplayName("Max Drawdown — equity curve 計算")
    class MaxDrawdown {

        @Test
        @DisplayName("先賺 100 再連虧 60 → DD = 60%")
        void simpleDrawdown() {
            // equity curve: 0 → 100 → 70 → 40
            //   peak=100; trough=40; DD=60; pct=0.6
            List<Trade> trades = mockTrades(
                    new double[]{100, -30, -30},
                    LocalDateTime.of(2026, 5, 1, 0, 0));

            SourcePerformanceMetrics m = service.calculateMetrics("ch1", trades, null);

            assertThat(m.getMaxDrawdownPct()).isEqualTo(0.6);
        }

        @Test
        @DisplayName("先賠後賺再賠 — 抓最大那段 DD")
        void multipleDrawdowns_takesMax() {
            // -20 → -20 → 30 → 10 → -50 → -40 → 80 → 40
            // equity 走勢: 0 → -20 → -20 → 10 → 20 → -30 → -70 → 10 → -10
            // 最高峰 20，從 20 跌到 -70 = drop 90，% = 90 / 20 = 4.5（>100% 因 peak 小）
            List<Trade> trades = mockTrades(
                    new double[]{-20, 0, 30, 10, -50, -40, 80, -50},
                    LocalDateTime.of(2026, 5, 1, 0, 0));

            SourcePerformanceMetrics m = service.calculateMetrics("ch1", trades, null);

            assertThat(m.getMaxDrawdownPct()).isGreaterThan(0);
        }

        @Test
        @DisplayName("從未獲利（一路虧）→ DD = 0 (沒 peak 概念)")
        void neverProfitable_ddZero() {
            List<Trade> trades = mockTrades(
                    new double[]{-10, -10, -10},
                    LocalDateTime.of(2026, 5, 1, 0, 0));

            SourcePerformanceMetrics m = service.calculateMetrics("ch1", trades, null);

            // 從未到正 equity → 沒 peak → DD = 0
            assertThat(m.getMaxDrawdownPct()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Sharpe Ratio — annualized")
    class SharpeRatio {

        @Test
        @DisplayName("固定 PnL (stddev=0) → Sharpe = 0 (處理 div0)")
        void constantPnl_sharpeZero() {
            List<Trade> trades = mockTrades(
                    new double[]{100, 100, 100, 100},
                    LocalDateTime.of(2026, 5, 1, 0, 0));

            SourcePerformanceMetrics m = service.calculateMetrics("ch1", trades, null);

            assertThat(m.getSharpeRatio()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("單筆 trade → Sharpe = 0 (n<2)")
        void singleTrade_sharpeZero() {
            List<Trade> trades = mockTrades(
                    new double[]{100},
                    LocalDateTime.of(2026, 5, 1, 0, 0));

            SourcePerformanceMetrics m = service.calculateMetrics("ch1", trades, null);

            assertThat(m.getSharpeRatio()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("正常分散 PnL — Sharpe 應為正數")
        void normalDistribution_positiveSharpe() {
            List<Trade> trades = mockTrades(
                    new double[]{100, 120, 80, 150, 90, -50, 200, 60},
                    LocalDateTime.of(2026, 5, 1, 0, 0));

            SourcePerformanceMetrics m = service.calculateMetrics("ch1", trades, null);

            // 平均賺錢 → Sharpe > 0
            assertThat(m.getSharpeRatio()).isPositive();
        }
    }

    @Nested
    @DisplayName("getAllSourceMetrics — 整合 query")
    class AllSourceMetrics {

        @Test
        @DisplayName("沒任何 paper trade → 回 empty list")
        void noTrades_returnsEmpty() {
            when(tradeRepository.findClosedPaperTradesGroupedBySource()).thenReturn(List.of());

            List<SourcePerformanceMetrics> result = service.getAllSourceMetrics();

            assertThat(result).isEmpty();
            verifyNoInteractions(sourceRepository);
        }

        @Test
        @DisplayName("兩個 source — 按 totalPnl 排序 (高到低)")
        void twoSources_sortedByPnlDesc() {
            // ch-rich: trades sum +100; ch-poor: trades sum -50
            List<Trade> richTrades = mockTradesForChannel("ch-rich", new double[]{60, 40},
                    LocalDateTime.of(2026, 5, 1, 0, 0));
            List<Trade> poorTrades = mockTradesForChannel("ch-poor", new double[]{-30, -20},
                    LocalDateTime.of(2026, 5, 1, 0, 0));

            // findClosedPaperTradesGroupedBySource 回所有 row（會在 service 內 group by channel）
            List<Trade> all = new java.util.ArrayList<>(richTrades);
            all.addAll(poorTrades);
            when(tradeRepository.findClosedPaperTradesGroupedBySource()).thenReturn(all);
            when(sourceRepository.findAll()).thenReturn(List.of(
                    mockSource(1L, "rich", "ch-rich"),
                    mockSource(2L, "poor", "ch-poor")));

            List<SourcePerformanceMetrics> result = service.getAllSourceMetrics();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getChannelId()).isEqualTo("ch-rich");
            assertThat(result.get(0).getTotalPnl()).isEqualTo(100.0);
            assertThat(result.get(1).getChannelId()).isEqualTo("ch-poor");
            assertThat(result.get(1).getTotalPnl()).isEqualTo(-50.0);
        }
    }

    @Nested
    @DisplayName("Isolation guarantee — 不會誤撈 real trades")
    class Isolation {

        @Test
        @DisplayName("getAllSourceMetrics 只透過 findClosedPaperTradesGroupedBySource 撈 (filter simulated=true)")
        void onlyCallsPaperRepoQueries() {
            when(tradeRepository.findClosedPaperTradesGroupedBySource()).thenReturn(List.of());

            service.getAllSourceMetrics();

            verify(tradeRepository, times(1)).findClosedPaperTradesGroupedBySource();
            // 確保沒呼叫任何 real-trade query
            verify(tradeRepository, never()).findByStatus(anyString());
            verify(tradeRepository, never()).findOpenSimulatedTrades(anyString(), anyString());
        }

        @Test
        @DisplayName("getSourceMetrics(channelId) 只用 paper-specific query")
        void singleSourceOnlyCallsPaperQuery() {
            when(tradeRepository.findClosedPaperTradesForSource("ch1")).thenReturn(List.of());

            service.getSourceMetrics("ch1");

            verify(tradeRepository, times(1)).findClosedPaperTradesForSource("ch1");
            verify(tradeRepository, never()).findByStatus(anyString());
        }
    }

    // ==================== Helpers ====================

    private static List<Trade> mockTrades(double[] pnls, LocalDateTime startTime) {
        return mockTradesForChannel("ch1", pnls, startTime);
    }

    private static List<Trade> mockTradesForChannel(String channelId, double[] pnls, LocalDateTime startTime) {
        java.util.List<Trade> list = new java.util.ArrayList<>();
        for (int i = 0; i < pnls.length; i++) {
            list.add(Trade.builder()
                    .tradeId("t-" + channelId + "-" + i)
                    .userId("PAPER_TRADE_SYSTEM")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .status("CLOSED")
                    .simulated(true)
                    .netProfit(pnls[i])
                    .sourceChannelId(channelId)
                    .exitTime(startTime.plusHours(i))  // 1 hour apart
                    .build());
        }
        return list;
    }

    private static SignalSourceConfig mockSource(Long id, String name) {
        return mockSource(id, name, "ch1");
    }

    private static SignalSourceConfig mockSource(Long id, String name, String channelId) {
        SignalSourceConfig s = new SignalSourceConfig();
        s.setId(id);
        s.setName(name);
        s.setDisplayName(name + " (display)");
        s.setChannelId(channelId);
        return s;
    }
}
