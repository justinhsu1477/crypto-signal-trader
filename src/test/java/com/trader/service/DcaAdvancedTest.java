package com.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.trading.entity.Trade;
import com.trader.trading.entity.TradeEvent;
import com.trader.trading.repository.TradeEventRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.TradeRecordService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DCA 進階測試
 *
 * 測試重點：
 * 1. DCA 加權平均價格計算正確性
 * 2. DCA 後部分平倉 → 再 DCA → remainingQuantity 正確處理
 * 3. DCA 入場手續費累加
 * 4. DCA count 正確遞增
 * 5. DCA 新 SL 更新
 * 6. DCA 找不到 OPEN trade 的容錯
 */
class DcaAdvancedTest {

    private TradeRepository tradeRepository;
    private TradeEventRepository tradeEventRepository;
    private TradeRecordService service;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        tradeEventRepository = mock(TradeEventRepository.class);
        service = new TradeRecordService(tradeRepository, tradeEventRepository,
                new ObjectMapper(), new MultiUserConfig());
    }

    private OrderResult dcaOrder(double price, double qty, double commission) {
        return OrderResult.builder()
                .success(true).orderId("DCA1").symbol("BTCUSDT")
                .side("BUY").type("LIMIT").price(price).quantity(qty)
                .commission(commission)
                .build();
    }

    // ==================== 加權平均價格 ====================

    @Nested
    @DisplayName("DCA 加權平均價格")
    class WeightedAveragePrice {

        @Test
        @DisplayName("首次 DCA — 從未部分平倉：用 entryQuantity 計算均價")
        void firstDcaUsesEntryQuantity() {
            Trade openTrade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .dcaCount(0).riskAmount(100.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true).newStopLoss(92000.0)
                    .build();

            service.recordDcaEntry("BTCUSDT", signal, dcaOrder(93000, 0.3, 5.58), 200);

            // 加權均價 = (95000 * 0.5 + 93000 * 0.3) / (0.5 + 0.3) = 94250
            assertThat(openTrade.getEntryPrice()).isEqualTo(94250.0);
            assertThat(openTrade.getEntryQuantity()).isEqualTo(0.8);
            assertThat(openTrade.getDcaCount()).isEqualTo(1);
            assertThat(openTrade.getStopLoss()).isEqualTo(92000.0);
            assertThat(openTrade.getRiskAmount()).isEqualTo(300.0);  // 100 + 200
        }

        @Test
        @DisplayName("部分平倉後 DCA — 用 remainingQuantity 計算均價")
        void dcaAfterPartialCloseUsesRemainingQuantity() {
            Trade openTrade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .remainingQuantity(0.5)  // 已部分平倉，剩餘 0.5
                    .totalClosedQuantity(0.5)
                    .entryCommission(19.0)
                    .dcaCount(0).riskAmount(200.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true)
                    .build();

            service.recordDcaEntry("BTCUSDT", signal, dcaOrder(93000, 0.3, 5.58), 200);

            // 加權均價 = (95000 * 0.5 + 93000 * 0.3) / (0.5 + 0.3) = 94250
            // 注意：用 remainingQuantity(0.5) 而非 entryQuantity(1.0)
            assertThat(openTrade.getEntryPrice()).isEqualTo(94250.0);
            assertThat(openTrade.getEntryQuantity()).isEqualTo(0.8);
            // DCA 後 remaining 和 totalClosed 應被 reset
            assertThat(openTrade.getRemainingQuantity()).isNull();
            assertThat(openTrade.getTotalClosedQuantity()).isNull();
        }

        @Test
        @DisplayName("連續兩次 DCA — 均價正確更新")
        void twoDcaInSequence() {
            Trade openTrade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .dcaCount(0).riskAmount(100.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            // 第一次 DCA
            TradeSignal signal1 = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true).newStopLoss(92000.0).build();
            service.recordDcaEntry("BTCUSDT", signal1, dcaOrder(93000, 0.3, 5.58), 200);

            // 第一次後：均價 94250, 數量 0.8, dcaCount 1
            assertThat(openTrade.getEntryPrice()).isEqualTo(94250.0);
            assertThat(openTrade.getEntryQuantity()).isEqualTo(0.8);
            assertThat(openTrade.getDcaCount()).isEqualTo(1);

            // 第二次 DCA（注意：remainingQuantity 已被 reset 為 null）
            TradeSignal signal2 = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true).newStopLoss(91000.0).build();
            service.recordDcaEntry("BTCUSDT", signal2, dcaOrder(91000, 0.2, 3.64), 200);

            // 第二次後：均價 = (94250 * 0.8 + 91000 * 0.2) / (0.8 + 0.2) = 93600
            assertThat(openTrade.getEntryPrice()).isEqualTo(93600.0);
            assertThat(openTrade.getEntryQuantity()).isEqualTo(1.0);
            assertThat(openTrade.getDcaCount()).isEqualTo(2);
            assertThat(openTrade.getStopLoss()).isEqualTo(91000.0);
        }
    }

    // ==================== 手續費累加 ====================

    @Nested
    @DisplayName("DCA 手續費處理")
    class DcaCommission {

        @Test
        @DisplayName("DCA 入場手續費累加到原始手續費上")
        void commissionAccumulates() {
            Trade openTrade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)  // 首次入場手續費
                    .dcaCount(0).riskAmount(100.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true).build();

            // DCA 手續費 5.58
            service.recordDcaEntry("BTCUSDT", signal, dcaOrder(93000, 0.3, 5.58), 200);

            // 累加手續費 = 9.5 + 5.58 = 15.08
            assertThat(openTrade.getEntryCommission()).isEqualTo(15.08);
        }

        @Test
        @DisplayName("DCA 無真實手續費 → fallback 估算 (0.02%)")
        void commissionFallbackToEstimate() {
            Trade openTrade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .dcaCount(0).riskAmount(100.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true).build();

            // commission = 0 → fallback to 93000 * 0.3 * 0.0002 = 5.58
            service.recordDcaEntry("BTCUSDT", signal, dcaOrder(93000, 0.3, 0), 200);

            assertThat(openTrade.getEntryCommission()).isEqualTo(15.08);
        }
    }

    // ==================== 容錯 ====================

    @Nested
    @DisplayName("DCA 容錯處理")
    class DcaErrorHandling {

        @Test
        @DisplayName("DCA 找不到 OPEN trade → 不拋例外，靜默跳過")
        void dcaWithNoOpenTrade() {
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.empty());

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true).build();

            // 不應拋例外
            assertThatCode(() ->
                service.recordDcaEntry("BTCUSDT", signal, dcaOrder(93000, 0.3, 5.58), 200)
            ).doesNotThrowAnyException();

            // 不應有任何 save 操作
            verify(tradeRepository, never()).save(any(Trade.class));
        }

        @Test
        @DisplayName("DCA 首次（dcaCount=null）→ 正確初始化為 1")
        void dcaCountNullInitializesToOne() {
            Trade openTrade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .dcaCount(null)  // null
                    .riskAmount(null)  // null
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true).build();

            service.recordDcaEntry("BTCUSDT", signal, dcaOrder(93000, 0.3, 5.58), 200);

            assertThat(openTrade.getDcaCount()).isEqualTo(1);
            assertThat(openTrade.getRiskAmount()).isEqualTo(200.0);
        }

        @Test
        @DisplayName("DCA 不帶新 SL → 不更新 SL")
        void dcaWithoutNewSLPreservesExisting() {
            Trade openTrade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .stopLoss(93000.0)  // 現有 SL
                    .entryCommission(9.5)
                    .dcaCount(0).riskAmount(100.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                    .isDca(true)
                    // 不帶 newStopLoss
                    .build();

            service.recordDcaEntry("BTCUSDT", signal, dcaOrder(93000, 0.3, 5.58), 200);

            // SL 不變
            assertThat(openTrade.getStopLoss()).isEqualTo(93000.0);
        }
    }

    // ==================== DCA 事件記錄 ====================

    @Test
    @DisplayName("DCA 成功記錄 DCA_ENTRY 事件")
    void dcaRecordsDcaEntryEvent() {
        Trade openTrade = Trade.builder()
                .tradeId("t1").symbol("BTCUSDT").side("LONG")
                .entryPrice(95000.0).entryQuantity(0.5)
                .entryCommission(9.5)
                .dcaCount(0).riskAmount(100.0)
                .status("OPEN")
                .build();

        when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

        TradeSignal signal = TradeSignal.builder()
                .symbol("BTCUSDT").signalType(TradeSignal.SignalType.ENTRY)
                .isDca(true).build();

        service.recordDcaEntry("BTCUSDT", signal, dcaOrder(93000, 0.3, 5.58), 200);

        // 驗證 DCA_ENTRY 事件被記錄
        ArgumentCaptor<TradeEvent> eventCaptor = ArgumentCaptor.forClass(TradeEvent.class);
        verify(tradeEventRepository).save(eventCaptor.capture());

        TradeEvent event = eventCaptor.getValue();
        assertThat(event.getTradeId()).isEqualTo("t1");
        assertThat(event.getEventType()).isEqualTo("DCA_ENTRY");
        assertThat(event.getPrice()).isEqualTo(93000.0);
        assertThat(event.getQuantity()).isEqualTo(0.3);
    }
}
