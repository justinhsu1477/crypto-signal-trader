package com.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.entity.TradeEvent;
import com.trader.trading.repository.TradeEventRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.TradeRecordService;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 測試 TradeRecordService 新增功能：
 * 1. recordOrderEvent() 通用事件記錄
 * 2. recordDcaEntry() 部分平倉後 DCA 數量修正
 * 3. calculateProfit() 使用真實手續費
 * 4. recordEntry() 存 takeProfits
 */
class TradeRecordServiceEventTest {

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

    // ==================== recordOrderEvent 測試 ====================

    @Nested
    @DisplayName("recordOrderEvent 通用事件記錄")
    class RecordOrderEventTests {

        @Test
        @DisplayName("有 OPEN Trade 時 — tradeId 正確關聯")
        void withOpenTrade() {
            Trade openTrade = Trade.builder()
                    .tradeId("trade-123")
                    .symbol("BTCUSDT")
                    .status("OPEN")
                    .build();
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            OrderResult failedOrder = OrderResult.fail("insufficient margin");

            service.recordOrderEvent("BTCUSDT", "ENTRY_FAILED", failedOrder, null);

            ArgumentCaptor<TradeEvent> captor = ArgumentCaptor.forClass(TradeEvent.class);
            verify(tradeEventRepository).save(captor.capture());

            TradeEvent saved = captor.getValue();
            assertThat(saved.getTradeId()).isEqualTo("trade-123");
            assertThat(saved.getEventType()).isEqualTo("ENTRY_FAILED");
            assertThat(saved.getSuccess()).isFalse();
            assertThat(saved.getErrorMessage()).isEqualTo("insufficient margin");
        }

        @Test
        @DisplayName("無 OPEN Trade 時 — tradeId 為 UNKNOWN")
        void withoutOpenTrade() {
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.empty());

            OrderResult failedOrder = OrderResult.fail("order rejected");

            service.recordOrderEvent("BTCUSDT", "CLOSE_FAILED", failedOrder, null);

            ArgumentCaptor<TradeEvent> captor = ArgumentCaptor.forClass(TradeEvent.class);
            verify(tradeEventRepository).save(captor.capture());

            TradeEvent saved = captor.getValue();
            assertThat(saved.getTradeId()).isEqualTo("UNKNOWN");
            assertThat(saved.getEventType()).isEqualTo("CLOSE_FAILED");
            assertThat(saved.getSuccess()).isFalse();
        }

        @Test
        @DisplayName("成功的 OrderResult — success=true, 無 errorMessage")
        void withSuccessfulOrder() {
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.empty());

            OrderResult successOrder = OrderResult.builder()
                    .success(true)
                    .orderId("12345")
                    .side("SELL")
                    .type("TAKE_PROFIT_MARKET")
                    .price(68400.0)
                    .quantity(0.5)
                    .build();

            service.recordOrderEvent("BTCUSDT", "TP_PLACED", successOrder, null);

            ArgumentCaptor<TradeEvent> captor = ArgumentCaptor.forClass(TradeEvent.class);
            verify(tradeEventRepository).save(captor.capture());

            TradeEvent saved = captor.getValue();
            assertThat(saved.getSuccess()).isTrue();
            assertThat(saved.getErrorMessage()).isNull();
            assertThat(saved.getBinanceOrderId()).isEqualTo("12345");
            assertThat(saved.getPrice()).isEqualTo(68400.0);
        }

        @Test
        @DisplayName("OrderResult 為 null — success=false, 保留 detail")
        void withNullOrder() {
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.empty());

            service.recordOrderEvent("BTCUSDT", "SL_REHUNG_FAILED", null,
                    "{\"reason\":\"no_sl_price\"}");

            ArgumentCaptor<TradeEvent> captor = ArgumentCaptor.forClass(TradeEvent.class);
            verify(tradeEventRepository).save(captor.capture());

            TradeEvent saved = captor.getValue();
            assertThat(saved.getSuccess()).isFalse();
            assertThat(saved.getDetail()).contains("no_sl_price");
            assertThat(saved.getBinanceOrderId()).isNull();
        }
    }

    // ==================== DCA 數量修正測試 ====================

    @Nested
    @DisplayName("recordDcaEntry — 部分平倉後 DCA 數量修正")
    class DcaQuantityFixTests {

        @Test
        @DisplayName("正常 DCA（無部分平倉）— entryQuantity 正確累加")
        void normalDcaNoPartialClose() {
            Trade openTrade = Trade.builder()
                    .tradeId("trade-dca-1")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .entryPrice(70000.0)
                    .entryQuantity(1.0)
                    .remainingQuantity(null)  // 未部分平倉
                    .totalClosedQuantity(null)
                    .entryCommission(14.0)
                    .status("OPEN")
                    .build();
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            OrderResult dcaOrder = OrderResult.builder()
                    .success(true)
                    .orderId("dca-order-1")
                    .price(68000.0)
                    .quantity(0.5)
                    .build();

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .isDca(true)
                    .build();

            service.recordDcaEntry("BTCUSDT", signal, dcaOrder, 500.0);

            // entryQuantity = 1.0 + 0.5 = 1.5
            assertThat(openTrade.getEntryQuantity()).isEqualTo(1.5);
            // 均價 = (70000*1.0 + 68000*0.5) / 1.5 = 69333.33
            assertThat(openTrade.getEntryPrice()).isCloseTo(69333.33, within(0.01));
        }

        @Test
        @DisplayName("部分平倉後 DCA — 用 remainingQuantity 計算，不膨脹")
        void dcaAfterPartialClose() {
            // 場景：入場 1.0 BTC → 部分平倉 0.5 BTC → DCA 0.3 BTC
            Trade openTrade = Trade.builder()
                    .tradeId("trade-dca-2")
                    .symbol("BTCUSDT")
                    .side("LONG")
                    .entryPrice(70000.0)
                    .entryQuantity(1.0)           // 原始入場量
                    .remainingQuantity(0.5)        // 部分平倉後剩 0.5
                    .totalClosedQuantity(0.5)      // 已平 0.5
                    .entryCommission(14.0)
                    .status("OPEN")
                    .build();
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(openTrade));

            OrderResult dcaOrder = OrderResult.builder()
                    .success(true)
                    .orderId("dca-order-2")
                    .price(68000.0)
                    .quantity(0.3)
                    .build();

            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .isDca(true)
                    .build();

            service.recordDcaEntry("BTCUSDT", signal, dcaOrder, 300.0);

            // 修正後：entryQuantity = remainingQuantity(0.5) + 0.3 = 0.8（而非 1.0 + 0.3 = 1.3）
            assertThat(openTrade.getEntryQuantity()).isEqualTo(0.8);

            // 均價 = (70000 * 0.5 + 68000 * 0.3) / 0.8 = (35000 + 20400) / 0.8 = 69250.0
            assertThat(openTrade.getEntryPrice()).isCloseTo(69250.0, within(0.01));

            // DCA 後重置部分平倉追蹤
            assertThat(openTrade.getRemainingQuantity()).isNull();
            assertThat(openTrade.getTotalClosedQuantity()).isNull();

            // DCA 計數遞增
            assertThat(openTrade.getDcaCount()).isEqualTo(1);
        }
    }

    // ==================== calculateProfit 真實手續費測試 ====================

    @Nested
    @DisplayName("calculateProfit — 真實手續費 vs 估算")
    class ProfitWithRealCommission {

        private void invokeCalculateProfit(Trade trade, double realExitCommission) throws Exception {
            Method method = TradeRecordService.class.getDeclaredMethod("calculateProfit", Trade.class, double.class);
            method.setAccessible(true);
            method.invoke(service, trade, realExitCommission);
        }

        @Test
        @DisplayName("有真實出場手續費 — 用真實值計算")
        void withRealCommission() throws Exception {
            Trade trade = Trade.builder()
                    .side("LONG")
                    .entryPrice(95000.0)
                    .exitPrice(98000.0)
                    .entryQuantity(0.5)
                    .entryCommission(9.5) // 入場 maker 0.02%
                    .build();

            // 真實出場手續費 = 15.0 USDT（非估算的 98000*0.5*0.0004=19.6）
            invokeCalculateProfit(trade, 15.0);

            assertThat(trade.getGrossProfit()).isEqualTo(1500.0);
            // 手續費 = 9.5 (入場) + 15.0 (出場真實) = 24.5（而非估算的 29.1）
            assertThat(trade.getCommission()).isEqualTo(24.5);
            assertThat(trade.getNetProfit()).isEqualTo(1475.5);
        }

        @Test
        @DisplayName("無真實出場手續費（0）— fallback 到估算")
        void withoutRealCommission() throws Exception {
            Trade trade = Trade.builder()
                    .side("LONG")
                    .entryPrice(95000.0)
                    .exitPrice(98000.0)
                    .entryQuantity(0.5)
                    .entryCommission(9.5)
                    .build();

            invokeCalculateProfit(trade, 0.0); // 0 = fallback 到估算

            // 出場手續費估算 = 98000 * 0.5 * 0.0004 = 19.6
            assertThat(trade.getCommission()).isEqualTo(29.1); // 9.5 + 19.6
        }
    }

    // ==================== recordEntry takeProfits 存儲測試 ====================

    @Nested
    @DisplayName("recordEntry — takeProfits 存入 Trade")
    class EntryTakeProfitsTests {

        @Test
        @DisplayName("訊號有 takeProfits — JSON 存入 Trade")
        void entryWithTakeProfits() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .stopLoss(64800.0)
                    .takeProfits(List.of(68400.0, 70000.0))
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            OrderResult entryOrder = OrderResult.builder()
                    .success(true)
                    .orderId("entry-1")
                    .price(66500.0)
                    .quantity(1.0)
                    .build();

            OrderResult slOrder = OrderResult.builder()
                    .success(true)
                    .orderId("sl-1")
                    .price(64800.0)
                    .quantity(1.0)
                    .build();

            service.recordEntry(signal, entryOrder, slOrder, 20, 1000.0, "hash-abc");

            ArgumentCaptor<Trade> captor = ArgumentCaptor.forClass(Trade.class);
            verify(tradeRepository).save(captor.capture());

            Trade saved = captor.getValue();
            assertThat(saved.getTakeProfits()).isNotNull();
            assertThat(saved.getTakeProfits()).contains("68400");
            assertThat(saved.getTakeProfits()).contains("70000");
        }

        @Test
        @DisplayName("訊號無 takeProfits — Trade.takeProfits 為 null")
        void entryWithoutTakeProfits() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .stopLoss(64800.0)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            OrderResult entryOrder = OrderResult.builder()
                    .success(true)
                    .orderId("entry-2")
                    .price(66500.0)
                    .quantity(1.0)
                    .build();

            service.recordEntry(signal, entryOrder, null, 20, 1000.0, null);

            ArgumentCaptor<Trade> captor = ArgumentCaptor.forClass(Trade.class);
            verify(tradeRepository).save(captor.capture());

            Trade saved = captor.getValue();
            assertThat(saved.getTakeProfits()).isNull();
        }

        @Test
        @DisplayName("入場手續費 — 優先用 Binance 真實值")
        void entryWithRealCommission() {
            TradeSignal signal = TradeSignal.builder()
                    .symbol("BTCUSDT")
                    .side(TradeSignal.Side.LONG)
                    .stopLoss(64800.0)
                    .signalType(TradeSignal.SignalType.ENTRY)
                    .build();

            OrderResult entryOrder = OrderResult.builder()
                    .success(true)
                    .orderId("entry-3")
                    .price(66500.0)
                    .quantity(1.0)
                    .commission(10.5)  // Binance 回傳的真實值
                    .build();

            service.recordEntry(signal, entryOrder, null, 20, 1000.0, null);

            ArgumentCaptor<Trade> captor = ArgumentCaptor.forClass(Trade.class);
            verify(tradeRepository).save(captor.capture());

            Trade saved = captor.getValue();
            // 用真實值 10.5，而非估算值 66500*1.0*0.0002=13.3
            assertThat(saved.getEntryCommission()).isEqualTo(10.5);
        }
    }
}
