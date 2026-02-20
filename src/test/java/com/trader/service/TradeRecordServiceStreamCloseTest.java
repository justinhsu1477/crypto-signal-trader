package com.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.trading.entity.Trade;
import com.trader.trading.entity.TradeEvent;
import com.trader.trading.repository.TradeEventRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.TradeRecordService;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * recordCloseFromStream() 測試
 * 驗證 WebSocket User Data Stream SL/TP 觸發後的 DB 更新邏輯
 */
class TradeRecordServiceStreamCloseTest {

    private TradeRepository tradeRepository;
    private TradeEventRepository tradeEventRepository;
    private TradeRecordService service;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        tradeEventRepository = mock(TradeEventRepository.class);
        service = new TradeRecordService(tradeRepository, tradeEventRepository, new ObjectMapper(), new com.trader.trading.config.MultiUserConfig());
    }

    @Nested
    @DisplayName("SL 觸發（STOP_MARKET FILLED）")
    class SlTriggered {

        @Test
        @DisplayName("做多止損 — status=CLOSED, exitReason=SL_TRIGGERED, 盈虧計算正確")
        void longSlTriggered() {
            Trade openTrade = Trade.builder()
                    .tradeId("test-long-sl")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .entryPrice(95000.0)
                    .entryQuantity(0.5)
                    .entryCommission(9.5) // maker 0.02%: 95000 * 0.5 * 0.0002
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            service.recordCloseFromStream(
                    "BTCUSDT", 93000.0, 0.5,
                    18.6,       // 真實出場手續費
                    -1000.0,    // 幣安 realizedProfit
                    "123456789", "SL_TRIGGERED",
                    1700000000000L);

            // 狀態
            assertThat(openTrade.getStatus()).isEqualTo("CLOSED");
            assertThat(openTrade.getExitReason()).isEqualTo("SL_TRIGGERED");

            // 出場資訊
            assertThat(openTrade.getExitPrice()).isEqualTo(93000.0);
            assertThat(openTrade.getExitQuantity()).isEqualTo(0.5);
            assertThat(openTrade.getExitOrderId()).isEqualTo("123456789");
            assertThat(openTrade.getExitTime()).isNotNull();

            // 手續費 = entry (9.5) + exit (18.6) = 28.1
            assertThat(openTrade.getCommission()).isEqualTo(28.1);

            // grossProfit = (93000 - 95000) * 0.5 * 1(LONG) = -1000
            assertThat(openTrade.getGrossProfit()).isEqualTo(-1000.0);

            // netProfit = -1000 - 28.1 = -1028.1
            assertThat(openTrade.getNetProfit()).isEqualTo(-1028.1);

            verify(tradeRepository).save(openTrade);
            verify(tradeEventRepository).save(argThat(event ->
                    "STREAM_CLOSE".equals(event.getEventType())
                            && "123456789".equals(event.getBinanceOrderId())));
        }

        @Test
        @DisplayName("做空止損 — 方向因子 direction=-1 正確")
        void shortSlTriggered() {
            Trade openTrade = Trade.builder()
                    .tradeId("test-short-sl")
                    .symbol("BTCUSDT")
                    .side("SHORT")
                    .entryPrice(95000.0)
                    .entryQuantity(0.2)
                    .entryCommission(3.8)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            service.recordCloseFromStream(
                    "BTCUSDT", 97000.0, 0.2,
                    7.76, -400.0,
                    "987654321", "SL_TRIGGERED",
                    1700000000000L);

            // grossProfit = (97000 - 95000) * 0.2 * (-1)(SHORT) = -400
            assertThat(openTrade.getGrossProfit()).isEqualTo(-400.0);
            assertThat(openTrade.getStatus()).isEqualTo("CLOSED");
            assertThat(openTrade.getExitReason()).isEqualTo("SL_TRIGGERED");
        }
    }

    @Nested
    @DisplayName("TP 觸發（TAKE_PROFIT_MARKET FILLED）")
    class TpTriggered {

        @Test
        @DisplayName("做多止盈 — 正盈利計算正確")
        void longTpTriggered() {
            Trade openTrade = Trade.builder()
                    .tradeId("test-long-tp")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .entryPrice(95000.0)
                    .entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            service.recordCloseFromStream(
                    "BTCUSDT", 98000.0, 0.5,
                    19.6, 1500.0,
                    "111222333", "TP_TRIGGERED",
                    1700000000000L);

            assertThat(openTrade.getExitReason()).isEqualTo("TP_TRIGGERED");

            // grossProfit = (98000 - 95000) * 0.5 * 1 = 1500
            assertThat(openTrade.getGrossProfit()).isEqualTo(1500.0);

            // commission = 9.5 + 19.6 = 29.1
            assertThat(openTrade.getCommission()).isEqualTo(29.1);

            // netProfit = 1500 - 29.1 = 1470.9
            assertThat(openTrade.getNetProfit()).isEqualTo(1470.9);
        }
    }

    @Nested
    @DisplayName("邊界情境")
    class EdgeCases {

        @Test
        @DisplayName("找不到 OPEN Trade → 安全忽略，不 save")
        void noOpenTradeFound() {
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.empty());

            // 不該拋出例外
            service.recordCloseFromStream(
                    "BTCUSDT", 93000.0, 0.5,
                    18.6, -1000.0,
                    "123456789", "SL_TRIGGERED",
                    1700000000000L);

            verify(tradeRepository, never()).save(any());
            verify(tradeEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("entryCommission 為 null — 用 0 替代，不 NPE")
        void nullEntryCommission() {
            Trade openTrade = Trade.builder()
                    .tradeId("test-null-com")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .entryPrice(95000.0)
                    .entryQuantity(0.5)
                    .entryCommission(null)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            service.recordCloseFromStream(
                    "BTCUSDT", 93000.0, 0.5,
                    18.6, -1000.0,
                    "123", "SL_TRIGGERED",
                    1700000000000L);

            // commission = 0 + 18.6 = 18.6
            assertThat(openTrade.getCommission()).isEqualTo(18.6);
        }

        @Test
        @DisplayName("exitTime 從 transactionTime (millis) 正確轉換為台灣時間")
        void transactionTimeConversion() {
            Trade openTrade = Trade.builder()
                    .tradeId("test-time")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .entryPrice(95000.0)
                    .entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            service.recordCloseFromStream(
                    "BTCUSDT", 93000.0, 0.5,
                    18.6, -1000.0,
                    "123", "SL_TRIGGERED", 1700000000000L);

            assertThat(openTrade.getExitTime()).isNotNull();
            // 1700000000000L = 2023-11-14T22:13:20Z = 2023-11-15 06:13:20 Taipei
            assertThat(openTrade.getExitTime().getYear()).isEqualTo(2023);
        }

        @Test
        @DisplayName("STREAM_CLOSE 事件包含正確的 detail JSON")
        void streamCloseEventDetail() {
            Trade openTrade = Trade.builder()
                    .tradeId("test-event")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .entryPrice(95000.0)
                    .entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            service.recordCloseFromStream(
                    "BTCUSDT", 93000.0, 0.5,
                    18.6, -1000.0,
                    "ord-123", "SL_TRIGGERED",
                    1700000000000L);

            ArgumentCaptor<TradeEvent> eventCaptor = ArgumentCaptor.forClass(TradeEvent.class);
            verify(tradeEventRepository).save(eventCaptor.capture());

            TradeEvent event = eventCaptor.getValue();
            assertThat(event.getEventType()).isEqualTo("STREAM_CLOSE");
            assertThat(event.getTradeId()).isEqualTo("test-event");
            assertThat(event.getBinanceOrderId()).isEqualTo("ord-123");
            assertThat(event.getPrice()).isEqualTo(93000.0);
            assertThat(event.getQuantity()).isEqualTo(0.5);
            assertThat(event.getSuccess()).isTrue();
            assertThat(event.getDetail()).contains("SL_TRIGGERED");
            assertThat(event.getDetail()).contains("18.6");
        }
    }
}
