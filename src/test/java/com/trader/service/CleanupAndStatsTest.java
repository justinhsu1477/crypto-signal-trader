package com.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.TradeEventRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.TradeRecordService;
import com.trader.trading.config.MultiUserConfig;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 殭屍交易清理 + 統計查詢測試
 *
 * 測試重點：
 * 1. cleanupStaleTrades：幣安無持倉 → CANCELLED、有持倉 → 跳過、查詢失敗 → 跳過
 * 2. getStatsSummary：勝率、Profit Factor、平均盈虧
 * 3. getStatsForDateRange：日期區間統計
 * 4. getEntryPrice：查詢開倉價
 */
class CleanupAndStatsTest {

    private TradeRepository tradeRepository;
    private TradeEventRepository tradeEventRepository;
    private TradeRecordService service;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        tradeEventRepository = mock(TradeEventRepository.class);
        service = new TradeRecordService(tradeRepository, tradeEventRepository,
                new ObjectMapper(), new MultiUserConfig(), "system-trader");
    }

    // ==================== cleanupStaleTrades ====================

    @Nested
    @DisplayName("殭屍交易清理")
    class StaleTradeCleanup {

        @Test
        @DisplayName("幣安無持倉 → CANCELLED + STALE_CLEANUP")
        void cleanupStaleTradeWithNoPosition() {
            Trade staleTrade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).status("OPEN")
                    .build();

            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(staleTrade));

            Function<String, Double> positionChecker = symbol -> 0.0;

            Map<String, Object> result = service.cleanupStaleTrades(positionChecker);

            assertThat(staleTrade.getStatus()).isEqualTo("CANCELLED");
            assertThat(staleTrade.getExitReason()).isEqualTo("STALE_CLEANUP");
            assertThat(staleTrade.getExitTime()).isNotNull();
            assertThat(result.get("totalOpen")).isEqualTo(1);
            assertThat(result.get("cleaned")).isEqualTo(1);
            assertThat(result.get("skipped")).isEqualTo(0);
        }

        @Test
        @DisplayName("幣安有持倉 → 跳過不清理")
        void skipTradeWithActivePosition() {
            Trade activeTrade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).status("OPEN")
                    .build();

            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(activeTrade));

            Function<String, Double> positionChecker = symbol -> 0.5;

            Map<String, Object> result = service.cleanupStaleTrades(positionChecker);

            assertThat(activeTrade.getStatus()).isEqualTo("OPEN");
            assertThat(result.get("cleaned")).isEqualTo(0);
            assertThat(result.get("skipped")).isEqualTo(1);
        }

        @Test
        @DisplayName("查詢失敗 → 跳過不清理")
        void skipOnQueryFailure() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).status("OPEN")
                    .build();

            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));

            Function<String, Double> positionChecker = symbol -> {
                throw new RuntimeException("API 失敗");
            };

            Map<String, Object> result = service.cleanupStaleTrades(positionChecker);

            assertThat(trade.getStatus()).isEqualTo("OPEN");
            assertThat(result.get("cleaned")).isEqualTo(0);
            assertThat(result.get("skipped")).isEqualTo(1);
        }

        @Test
        @DisplayName("混合場景 — 1 筆殭屍 + 1 筆有效 + 1 筆查詢失敗")
        void mixedCleanupScenario() {
            Trade stale = Trade.builder().tradeId("t1").symbol("BTCUSDT").side("LONG").status("OPEN").build();
            Trade active = Trade.builder().tradeId("t2").symbol("ETHUSDT").side("SHORT").status("OPEN").build();
            Trade error = Trade.builder().tradeId("t3").symbol("SOLUSDT").side("LONG").status("OPEN").build();

            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(stale, active, error));

            Function<String, Double> positionChecker = symbol -> {
                return switch (symbol) {
                    case "BTCUSDT" -> 0.0;     // 無持倉
                    case "ETHUSDT" -> -1.0;    // 有持倉
                    case "SOLUSDT" -> throw new RuntimeException("timeout");
                    default -> 0.0;
                };
            };

            Map<String, Object> result = service.cleanupStaleTrades(positionChecker);

            assertThat(result.get("totalOpen")).isEqualTo(3);
            assertThat(result.get("cleaned")).isEqualTo(1);
            assertThat(result.get("skipped")).isEqualTo(2);
            assertThat(stale.getStatus()).isEqualTo("CANCELLED");
            assertThat(active.getStatus()).isEqualTo("OPEN");
            assertThat(error.getStatus()).isEqualTo("OPEN");
        }

        @Test
        @DisplayName("沒有 OPEN trade → 返回空結果")
        void noOpenTrades() {
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of());

            Map<String, Object> result = service.cleanupStaleTrades(s -> 0.0);

            assertThat(result.get("totalOpen")).isEqualTo(0);
            assertThat(result.get("cleaned")).isEqualTo(0);
        }
    }

    // ==================== getStatsSummary ====================

    @Nested
    @DisplayName("統計摘要")
    class StatsSummary {

        @Test
        @DisplayName("有勝有敗 — 勝率、Profit Factor 正確")
        void mixedWinsAndLosses() {
            // 全局模式
            when(tradeRepository.countClosedTrades()).thenReturn(10L);
            when(tradeRepository.countWinningTrades()).thenReturn(6L);
            when(tradeRepository.sumNetProfit()).thenReturn(500.0);
            when(tradeRepository.sumGrossWins()).thenReturn(1500.0);
            when(tradeRepository.sumGrossLosses()).thenReturn(1000.0);
            when(tradeRepository.sumCommission()).thenReturn(200.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of());

            Map<String, Object> stats = service.getStatsSummary();

            assertThat(stats.get("closedTrades")).isEqualTo(10L);
            assertThat(stats.get("winningTrades")).isEqualTo(6L);
            assertThat(stats.get("winRate")).isEqualTo("60.0%");
            assertThat(stats.get("totalNetProfit")).isEqualTo(500.0);
            // Profit Factor = 1500 / 1000 = 1.5
            assertThat(stats.get("profitFactor")).isEqualTo(1.5);
            // 平均盈虧 = 500 / 10 = 50
            assertThat(stats.get("avgProfitPerTrade")).isEqualTo(50.0);
            assertThat(stats.get("totalCommission")).isEqualTo(200.0);
        }

        @Test
        @DisplayName("沒有交易 → 勝率 0%, Profit Factor 0")
        void noTrades() {
            when(tradeRepository.countClosedTrades()).thenReturn(0L);
            when(tradeRepository.countWinningTrades()).thenReturn(0L);
            when(tradeRepository.sumNetProfit()).thenReturn(0.0);
            when(tradeRepository.sumGrossWins()).thenReturn(0.0);
            when(tradeRepository.sumGrossLosses()).thenReturn(0.0);
            when(tradeRepository.sumCommission()).thenReturn(0.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of());

            Map<String, Object> stats = service.getStatsSummary();

            assertThat(stats.get("winRate")).isEqualTo("0.0%");
            assertThat(stats.get("profitFactor")).isEqualTo(0.0);
            assertThat(stats.get("avgProfitPerTrade")).isEqualTo(0.0);
        }

        @Test
        @DisplayName("全勝 — grossLosses=0 → Profit Factor=0（分母不可為 0）")
        void allWinsNoProfitFactor() {
            when(tradeRepository.countClosedTrades()).thenReturn(5L);
            when(tradeRepository.countWinningTrades()).thenReturn(5L);
            when(tradeRepository.sumNetProfit()).thenReturn(1000.0);
            when(tradeRepository.sumGrossWins()).thenReturn(1000.0);
            when(tradeRepository.sumGrossLosses()).thenReturn(0.0);  // 全勝
            when(tradeRepository.sumCommission()).thenReturn(50.0);
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of());

            Map<String, Object> stats = service.getStatsSummary();

            assertThat(stats.get("winRate")).isEqualTo("100.0%");
            assertThat(stats.get("profitFactor")).isEqualTo(0.0);  // 除以 0 → 0
        }
    }

    // ==================== getStatsForDateRange ====================

    @Nested
    @DisplayName("日期區間統計")
    class StatsForDateRange {

        @Test
        @DisplayName("日期區間內有交易 — wins/losses/netProfit/commission")
        void statsWithTrades() {
            Trade t1 = Trade.builder().tradeId("t1").netProfit(100.0).commission(5.0).build();
            Trade t2 = Trade.builder().tradeId("t2").netProfit(-50.0).commission(3.0).build();
            Trade t3 = Trade.builder().tradeId("t3").netProfit(200.0).commission(8.0).build();

            LocalDateTime from = LocalDateTime.of(2025, 2, 1, 0, 0);
            LocalDateTime to = LocalDateTime.of(2025, 2, 2, 0, 0);

            when(tradeRepository.findClosedTradesBetween(from, to)).thenReturn(List.of(t1, t2, t3));
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of());

            Map<String, Object> stats = service.getStatsForDateRange(from, to);

            assertThat(stats.get("trades")).isEqualTo(3L);
            assertThat(stats.get("wins")).isEqualTo(2L);    // t1, t3
            assertThat(stats.get("losses")).isEqualTo(1L);   // t2
            assertThat(stats.get("netProfit")).isEqualTo(250.0);
            assertThat(stats.get("commission")).isEqualTo(16.0);
        }
    }

    // ==================== getEntryPrice ====================

    @Nested
    @DisplayName("查詢開倉價")
    class GetEntryPrice {

        @Test
        @DisplayName("有 OPEN trade → 返回入場價")
        void returnsEntryPrice() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT")
                    .entryPrice(95000.0).status("OPEN")
                    .build();
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(java.util.Optional.of(trade));

            Double price = service.getEntryPrice("BTCUSDT");

            assertThat(price).isEqualTo(95000.0);
        }

        @Test
        @DisplayName("無 OPEN trade → 返回 null")
        void returnsNullWhenNoOpenTrade() {
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(java.util.Optional.empty());

            Double price = service.getEntryPrice("BTCUSDT");

            assertThat(price).isNull();
        }
    }
}
