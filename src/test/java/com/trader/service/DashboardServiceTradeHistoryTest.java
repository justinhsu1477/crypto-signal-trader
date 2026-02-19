package com.trader.service;

import com.trader.dashboard.dto.TradeHistoryResponse;
import com.trader.dashboard.service.DashboardService;
import com.trader.shared.config.RiskConfig;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.entity.Trade;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * DashboardService — TradeHistory 端點測試
 *
 * 測試 getTradeHistory() 方法的：
 * - 分頁邏輯（page、size、totalPages、totalElements）
 * - 欄位映射（Trade entity → TradeRecord DTO）
 * - 邊界情境（空列表、超出頁數、大量資料）
 */
class DashboardServiceTradeHistoryTest {

    private DashboardService dashboardService;
    private TradeRecordService tradeRecordService;

    @BeforeEach
    void setUp() {
        tradeRecordService = Mockito.mock(TradeRecordService.class);
        SubscriptionService subscriptionService = Mockito.mock(SubscriptionService.class);
        BinanceFuturesService binanceFuturesService = Mockito.mock(BinanceFuturesService.class);
        RiskConfig riskConfig = Mockito.mock(RiskConfig.class);
        UserRepository userRepository = Mockito.mock(UserRepository.class);

        dashboardService = new DashboardService(
                tradeRecordService, subscriptionService, binanceFuturesService, riskConfig, userRepository);
    }

    /**
     * 批次建立 CLOSED Trade 的工具方法
     */
    private List<Trade> createClosedTrades(int count) {
        List<Trade> trades = new ArrayList<>();
        LocalDateTime base = LocalDateTime.of(2026, 1, 10, 12, 0);
        for (int i = 0; i < count; i++) {
            trades.add(Trade.builder()
                    .tradeId("trade-" + i)
                    .symbol("BTCUSDT")
                    .side(i % 2 == 0 ? "LONG" : "SHORT")
                    .entryPrice(50000.0 + i * 100)
                    .exitPrice(51000.0 + i * 100)
                    .entryQuantity(0.01)
                    .netProfit(i % 3 == 0 ? -50.0 : 100.0)
                    .exitReason(i % 3 == 0 ? "STOP_LOSS" : "SIGNAL_CLOSE")
                    .sourceAuthorName("陳哥")
                    .dcaCount(i % 4 == 0 ? 1 : 0)
                    .entryTime(base.minusDays(count - i))
                    .exitTime(base.minusDays(count - i).plusHours(3))
                    .status("CLOSED")
                    .build());
        }
        return trades;
    }

    // ==================== 分頁邏輯 ====================

    @Nested
    @DisplayName("分頁邏輯")
    class PaginationTest {

        @Test
        @DisplayName("50 筆交易, page=0, size=20 → 回傳前 20 筆, totalPages=3")
        void firstPage() {
            List<Trade> allTrades = createClosedTrades(50);
            when(tradeRecordService.findByStatus("CLOSED")).thenReturn(allTrades);

            TradeHistoryResponse response = dashboardService.getTradeHistory("user-1", 0, 20);

            assertThat(response.getTrades()).hasSize(20);
            assertThat(response.getPagination().getPage()).isEqualTo(0);
            assertThat(response.getPagination().getSize()).isEqualTo(20);
            assertThat(response.getPagination().getTotalPages()).isEqualTo(3);
            assertThat(response.getPagination().getTotalElements()).isEqualTo(50);
        }

        @Test
        @DisplayName("50 筆交易, page=2, size=20 → 回傳最後 10 筆")
        void lastPage() {
            List<Trade> allTrades = createClosedTrades(50);
            when(tradeRecordService.findByStatus("CLOSED")).thenReturn(allTrades);

            TradeHistoryResponse response = dashboardService.getTradeHistory("user-1", 2, 20);

            assertThat(response.getTrades()).hasSize(10);
            assertThat(response.getPagination().getPage()).isEqualTo(2);
            assertThat(response.getPagination().getTotalPages()).isEqualTo(3);
        }

        @Test
        @DisplayName("50 筆交易, page=5（超出範圍） → 回傳空列表")
        void pageOutOfRange() {
            List<Trade> allTrades = createClosedTrades(50);
            when(tradeRecordService.findByStatus("CLOSED")).thenReturn(allTrades);

            TradeHistoryResponse response = dashboardService.getTradeHistory("user-1", 5, 20);

            assertThat(response.getTrades()).isEmpty();
            assertThat(response.getPagination().getTotalElements()).isEqualTo(50);
        }

        @Test
        @DisplayName("恰好整除 — 40 筆 / size=20 → totalPages=2")
        void exactDivision() {
            List<Trade> allTrades = createClosedTrades(40);
            when(tradeRecordService.findByStatus("CLOSED")).thenReturn(allTrades);

            TradeHistoryResponse response = dashboardService.getTradeHistory("user-1", 0, 20);

            assertThat(response.getTrades()).hasSize(20);
            assertThat(response.getPagination().getTotalPages()).isEqualTo(2);
        }

        @Test
        @DisplayName("只有 1 筆 → totalPages=1, size=20")
        void singleTrade() {
            List<Trade> allTrades = createClosedTrades(1);
            when(tradeRecordService.findByStatus("CLOSED")).thenReturn(allTrades);

            TradeHistoryResponse response = dashboardService.getTradeHistory("user-1", 0, 20);

            assertThat(response.getTrades()).hasSize(1);
            assertThat(response.getPagination().getTotalPages()).isEqualTo(1);
            assertThat(response.getPagination().getTotalElements()).isEqualTo(1);
        }

        @Test
        @DisplayName("size=10 的分頁 — 25 筆 → totalPages=3")
        void customPageSize() {
            List<Trade> allTrades = createClosedTrades(25);
            when(tradeRecordService.findByStatus("CLOSED")).thenReturn(allTrades);

            TradeHistoryResponse response = dashboardService.getTradeHistory("user-1", 0, 10);

            assertThat(response.getTrades()).hasSize(10);
            assertThat(response.getPagination().getSize()).isEqualTo(10);
            assertThat(response.getPagination().getTotalPages()).isEqualTo(3);
        }
    }

    // ==================== 空列表 ====================

    @Nested
    @DisplayName("空交易歷史")
    class EmptyHistory {

        @Test
        @DisplayName("無已平倉交易 → 空列表, totalPages=0, totalElements=0")
        void noClosedTrades() {
            when(tradeRecordService.findByStatus("CLOSED")).thenReturn(List.of());

            TradeHistoryResponse response = dashboardService.getTradeHistory("user-1", 0, 20);

            assertThat(response.getTrades()).isEmpty();
            assertThat(response.getPagination().getPage()).isEqualTo(0);
            assertThat(response.getPagination().getSize()).isEqualTo(20);
            assertThat(response.getPagination().getTotalPages()).isEqualTo(0);
            assertThat(response.getPagination().getTotalElements()).isEqualTo(0);
        }
    }

    // ==================== 欄位映射 ====================

    @Nested
    @DisplayName("Trade → TradeRecord 欄位映射")
    class FieldMapping {

        @Test
        @DisplayName("所有欄位正確映射")
        void allFieldsMapping() {
            LocalDateTime entryTime = LocalDateTime.of(2026, 1, 15, 14, 30);
            LocalDateTime exitTime = LocalDateTime.of(2026, 1, 15, 18, 45);
            Trade trade = Trade.builder()
                    .tradeId("trade-abc")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .entryPrice(50000.0)
                    .exitPrice(51000.0)
                    .entryQuantity(0.01)
                    .netProfit(100.0)
                    .exitReason("SIGNAL_CLOSE")
                    .sourceAuthorName("陳哥")
                    .dcaCount(2)
                    .entryTime(entryTime)
                    .exitTime(exitTime)
                    .status("CLOSED")
                    .build();

            when(tradeRecordService.findByStatus("CLOSED")).thenReturn(List.of(trade));

            TradeHistoryResponse response = dashboardService.getTradeHistory("user-1", 0, 20);

            assertThat(response.getTrades()).hasSize(1);
            TradeHistoryResponse.TradeRecord record = response.getTrades().get(0);
            assertThat(record.getTradeId()).isEqualTo("trade-abc");
            assertThat(record.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(record.getSide()).isEqualTo("LONG");
            assertThat(record.getEntryPrice()).isEqualTo(50000.0);
            assertThat(record.getExitPrice()).isEqualTo(51000.0);
            assertThat(record.getEntryQuantity()).isEqualTo(0.01);
            assertThat(record.getNetProfit()).isEqualTo(100.0);
            assertThat(record.getExitReason()).isEqualTo("SIGNAL_CLOSE");
            assertThat(record.getSignalSource()).isEqualTo("陳哥");
            assertThat(record.getDcaCount()).isEqualTo(2);
            assertThat(record.getEntryTime()).isEqualTo(entryTime.toString());
            assertThat(record.getExitTime()).isEqualTo(exitTime.toString());
            assertThat(record.getStatus()).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("null entryTime/exitTime → 映射為 null")
        void nullTimes() {
            Trade trade = Trade.builder()
                    .tradeId("trade-null-time")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .entryTime(null)
                    .exitTime(null)
                    .status("CLOSED")
                    .build();

            when(tradeRecordService.findByStatus("CLOSED")).thenReturn(List.of(trade));

            TradeHistoryResponse response = dashboardService.getTradeHistory("user-1", 0, 20);

            assertThat(response.getTrades().get(0).getEntryTime()).isNull();
            assertThat(response.getTrades().get(0).getExitTime()).isNull();
        }

        @Test
        @DisplayName("netProfit 為 null → DTO 也是 null")
        void nullNetProfit() {
            Trade trade = Trade.builder()
                    .tradeId("trade-null-profit")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .netProfit(null)
                    .status("CLOSED")
                    .build();

            when(tradeRecordService.findByStatus("CLOSED")).thenReturn(List.of(trade));

            TradeHistoryResponse response = dashboardService.getTradeHistory("user-1", 0, 20);

            assertThat(response.getTrades().get(0).getNetProfit()).isNull();
        }
    }

    // ==================== 訊號來源 ====================

    @Nested
    @DisplayName("訊號來源映射")
    class SignalSourceMapping {

        @Test
        @DisplayName("sourceAuthorName 為 null → signalSource 為 null")
        void nullSignalSource() {
            Trade trade = Trade.builder()
                    .tradeId("trade-no-source")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .sourceAuthorName(null)
                    .status("CLOSED")
                    .build();

            when(tradeRecordService.findByStatus("CLOSED")).thenReturn(List.of(trade));

            TradeHistoryResponse response = dashboardService.getTradeHistory("user-1", 0, 20);

            assertThat(response.getTrades().get(0).getSignalSource()).isNull();
        }
    }
}
