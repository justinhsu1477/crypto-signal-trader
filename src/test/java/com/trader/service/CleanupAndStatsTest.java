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
 * 2. cleanupStaleTrades 冷卻期：建立未滿 30 分鐘的 Trade 不清理（避免誤殺剛廣播的交易）
 * 3. getStatsSummary：勝率、Profit Factor、平均盈虧
 * 4. getStatsForDateRange：日期區間統計
 * 5. getEntryPrice：查詢開倉價
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

        // ===== 冷卻期保護測試 =====

        @Test
        @DisplayName("冷卻期 — 建立 5 分鐘的 Trade + 無持倉 → 跳過不清理")
        void cooldown_recentTrade_skipped() {
            Trade recentTrade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("SHORT")
                    .entryPrice(95000.0).status("OPEN")
                    .createdAt(LocalDateTime.now().minusMinutes(5))  // 5 分鐘前建立
                    .build();

            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(recentTrade));

            // positionChecker 回傳 0 — 但因冷卻期保護，不應該被清理
            Map<String, Object> result = service.cleanupStaleTrades(symbol -> 0.0);

            assertThat(recentTrade.getStatus()).isEqualTo("OPEN");  // 仍然 OPEN
            assertThat(result.get("totalOpen")).isEqualTo(1);
            assertThat(result.get("cleaned")).isEqualTo(0);
            assertThat(result.get("skipped")).isEqualTo(1);
        }

        @Test
        @DisplayName("冷卻期 — 建立 2 小時的 Trade + 無持倉 → 正常清理")
        void cooldown_oldTrade_cleaned() {
            Trade oldTrade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).status("OPEN")
                    .createdAt(LocalDateTime.now().minusHours(2))  // 2 小時前建立
                    .build();

            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(oldTrade));

            Map<String, Object> result = service.cleanupStaleTrades(symbol -> 0.0);

            assertThat(oldTrade.getStatus()).isEqualTo("CANCELLED");
            assertThat(oldTrade.getExitReason()).isEqualTo("STALE_CLEANUP");
            assertThat(result.get("cleaned")).isEqualTo(1);
            assertThat(result.get("skipped")).isEqualTo(0);
        }

        @Test
        @DisplayName("冷卻期 — 混合場景：1 筆冷卻期內 + 1 筆超過冷卻期殭屍 + 1 筆有持倉")
        void cooldown_mixedScenario() {
            Trade recentTrade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("SHORT")
                    .status("OPEN")
                    .createdAt(LocalDateTime.now().minusMinutes(10))  // 10 分鐘 — 冷卻期內
                    .build();
            Trade oldZombie = Trade.builder()
                    .tradeId("t2").symbol("ETHUSDT").side("LONG")
                    .status("OPEN")
                    .createdAt(LocalDateTime.now().minusHours(3))  // 3 小時 — 超過冷卻期
                    .build();
            Trade activePosition = Trade.builder()
                    .tradeId("t3").symbol("SOLUSDT").side("LONG")
                    .status("OPEN")
                    .createdAt(LocalDateTime.now().minusHours(1))  // 1 小時 — 超過冷卻期但有持倉
                    .build();

            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(recentTrade, oldZombie, activePosition));

            Function<String, Double> positionChecker = symbol -> switch (symbol) {
                case "ETHUSDT" -> 0.0;   // 無持倉 → 殭屍
                case "SOLUSDT" -> 1.5;   // 有持倉 → 跳過
                default -> 0.0;
            };

            Map<String, Object> result = service.cleanupStaleTrades(positionChecker);

            assertThat(result.get("totalOpen")).isEqualTo(3);
            assertThat(result.get("cleaned")).isEqualTo(1);   // 只有 t2 被清理
            assertThat(result.get("skipped")).isEqualTo(2);   // t1（冷卻期）+ t3（有持倉）
            assertThat(recentTrade.getStatus()).isEqualTo("OPEN");       // 冷卻期保護
            assertThat(oldZombie.getStatus()).isEqualTo("CANCELLED");    // 正常清理
            assertThat(activePosition.getStatus()).isEqualTo("OPEN");    // 有持倉跳過
        }

        @Test
        @DisplayName("冷卻期 — createdAt 為 null（舊資料）→ 不觸發冷卻期，正常走清理流程")
        void cooldown_nullCreatedAt_normalCleanup() {
            Trade oldDataTrade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).status("OPEN")
                    // createdAt 未設定 → null
                    .build();

            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(oldDataTrade));

            Map<String, Object> result = service.cleanupStaleTrades(symbol -> 0.0);

            assertThat(oldDataTrade.getStatus()).isEqualTo("CANCELLED");
            assertThat(result.get("cleaned")).isEqualTo(1);
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
