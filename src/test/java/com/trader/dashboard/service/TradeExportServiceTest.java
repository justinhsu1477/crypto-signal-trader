package com.trader.dashboard.service;

import com.trader.trading.entity.Trade;
import com.trader.trading.repository.TradeRepository;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TradeExportService 單元測試
 */
class TradeExportServiceTest {

    private TradeRepository tradeRepository;
    private TradeExportService service;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        service = new TradeExportService(tradeRepository);
    }

    @Nested
    @DisplayName("generateCsv")
    class GenerateCsv {

        @Test
        @DisplayName("CSV 包含正確的 header")
        void hasCorrectHeader() {
            when(tradeRepository.findUserClosedTradesAfter(anyString(), any()))
                    .thenReturn(Collections.emptyList());

            String csv = service.generateCsv("user-1", 30, 500);
            String headerLine = csv.split("\n")[0];

            assertThat(headerLine).isEqualTo(
                    "Date,Symbol,Side,Entry Price,Exit Price,Quantity,Leverage,Gross Profit,Commission,Net Profit,Exit Reason,DCA Count");
        }

        @Test
        @DisplayName("空資料只有 header")
        void emptyTradesOnlyHeader() {
            when(tradeRepository.findUserClosedTradesAfter(anyString(), any()))
                    .thenReturn(Collections.emptyList());

            String csv = service.generateCsv("user-1", 30, 500);
            String[] lines = csv.split("\n");

            assertThat(lines).hasSize(1); // header only
        }

        @Test
        @DisplayName("有資料時每行欄位數正確（12 欄）")
        void correctColumnCount() {
            Trade trade = Trade.builder()
                    .tradeId("t-1")
                    .userId("user-1")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .entryPrice(50000.0)
                    .exitPrice(52000.0)
                    .entryQuantity(0.01)
                    .leverage(10)
                    .grossProfit(20.0)
                    .commission(1.5)
                    .netProfit(18.5)
                    .exitReason("TAKE_PROFIT")
                    .dcaCount(0)
                    .status("CLOSED")
                    .exitTime(LocalDateTime.of(2026, 3, 1, 10, 30, 0))
                    .build();

            when(tradeRepository.findUserClosedTradesAfter(anyString(), any()))
                    .thenReturn(List.of(trade));

            String csv = service.generateCsv("user-1", 30, 500);
            String[] lines = csv.split("\n");

            assertThat(lines).hasSize(2); // header + 1 data row
            String[] fields = lines[1].split(",");
            assertThat(fields).hasSize(12);
        }

        @Test
        @DisplayName("CSV 包含正確資料值")
        void containsCorrectValues() {
            Trade trade = Trade.builder()
                    .tradeId("t-1").userId("user-1")
                    .symbol("ETHUSDT").side("SHORT")
                    .entryPrice(3000.0).exitPrice(2900.0)
                    .entryQuantity(0.5).leverage(5)
                    .grossProfit(50.0).commission(2.0).netProfit(48.0)
                    .exitReason("STOP_LOSS").dcaCount(1)
                    .status("CLOSED")
                    .exitTime(LocalDateTime.of(2026, 2, 15, 14, 0, 0))
                    .build();

            when(tradeRepository.findUserClosedTradesAfter(anyString(), any()))
                    .thenReturn(List.of(trade));

            String csv = service.generateCsv("user-1", 30, 500);

            assertThat(csv).contains("ETHUSDT");
            assertThat(csv).contains("SHORT");
            assertThat(csv).contains("3000.0");
            assertThat(csv).contains("2900.0");
            assertThat(csv).contains("48.0");
            assertThat(csv).contains("STOP_LOSS");
            assertThat(csv).contains("2026-02-15 14:00:00");
        }

        @Test
        @DisplayName("maxRows 限制生效")
        void respectsMaxRows() {
            List<Trade> trades = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                trades.add(Trade.builder()
                        .tradeId("t-" + i).userId("user-1")
                        .symbol("BTCUSDT").side("LONG")
                        .entryPrice(50000.0).exitPrice(51000.0)
                        .entryQuantity(0.01).leverage(10)
                        .grossProfit(10.0).commission(1.0).netProfit(9.0)
                        .exitReason("TAKE_PROFIT").dcaCount(0)
                        .status("CLOSED")
                        .exitTime(LocalDateTime.of(2026, 3, 1, i, 0, 0))
                        .build());
            }

            when(tradeRepository.findUserClosedTradesAfter(anyString(), any()))
                    .thenReturn(trades);

            String csv = service.generateCsv("user-1", 30, 3);
            String[] lines = csv.split("\n");

            assertThat(lines).hasSize(4); // header + 3 data rows
        }

        @Test
        @DisplayName("null 欄位不報錯")
        void handlesNullFields() {
            Trade trade = Trade.builder()
                    .tradeId("t-1").userId("user-1")
                    .symbol("BTCUSDT").side("LONG")
                    .entryPrice(50000.0)
                    .exitPrice(null) // null
                    .entryQuantity(0.01)
                    .leverage(null) // null
                    .grossProfit(null) // null
                    .commission(null) // null
                    .netProfit(null) // null
                    .exitReason(null) // null
                    .dcaCount(null) // null
                    .status("CLOSED")
                    .exitTime(null) // null
                    .build();

            when(tradeRepository.findUserClosedTradesAfter(anyString(), any()))
                    .thenReturn(List.of(trade));

            assertThatCode(() -> service.generateCsv("user-1", 30, 500))
                    .doesNotThrowAnyException();
        }
    }
}
