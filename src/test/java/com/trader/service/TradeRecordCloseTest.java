package com.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.trading.entity.Trade;
import com.trader.trading.entity.TradeEvent;
import com.trader.trading.repository.TradeEventRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.TradeRecordService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.shared.model.OrderResult;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.Optional;
import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * TradeRecordService 平倉記錄測試
 *
 * 測試重點：
 * 1. recordClose：全平盈虧計算（做多/做空、有手續費/無手續費）
 * 2. recordPartialClose：累加已平量 + 剩餘量 + 維持 OPEN
 * 3. 多次部分平倉後全平：數量追蹤正確
 * 4. calculateProfit 邊界案例
 * 5. recordCloseFromStream 全平 vs 部分判斷
 */
class TradeRecordCloseTest {

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

    // ==================== recordClose 盈虧計算 ====================

    @Nested
    @DisplayName("recordClose 盈虧計算")
    class RecordCloseProfit {

        @Test
        @DisplayName("做多獲利 — 有真實出場手續費")
        void longProfitWithRealCommission() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)  // maker 0.02%
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("MARKET")
                    .price(97000).quantity(0.5)
                    .commission(19.4)  // 真實出場手續費
                    .build();

            service.recordClose("BTCUSDT", closeOrder, "SIGNAL_CLOSE");

            // 毛利 = (97000 - 95000) * 0.5 * 1 = 1000
            assertThat(trade.getGrossProfit()).isEqualTo(1000.0);
            // 手續費 = 入場 9.5 + 出場 19.4 = 28.9
            assertThat(trade.getCommission()).isEqualTo(28.9);
            // 淨利 = 1000 - 28.9 = 971.1
            assertThat(trade.getNetProfit()).isEqualTo(971.1);
            assertThat(trade.getStatus()).isEqualTo("CLOSED");
            assertThat(trade.getExitReason()).isEqualTo("SIGNAL_CLOSE");
        }

        @Test
        @DisplayName("做空獲利 — (entry - exit) × qty")
        void shortProfitCalculation() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("SHORT")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("BUY").type("MARKET")
                    .price(93000).quantity(0.5)
                    .commission(18.6)
                    .build();

            service.recordClose("BTCUSDT", closeOrder, "SIGNAL_CLOSE");

            // 毛利 = (93000 - 95000) * 0.5 * (-1) = 1000（做空獲利）
            assertThat(trade.getGrossProfit()).isEqualTo(1000.0);
            assertThat(trade.getCommission()).isEqualTo(28.1);  // 9.5 + 18.6
            assertThat(trade.getNetProfit()).isEqualTo(971.9);  // 1000 - 28.1
        }

        @Test
        @DisplayName("做多虧損 — 淨利為負")
        void longLossCalculation() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("MARKET")
                    .price(93000).quantity(0.5)
                    .commission(18.6)
                    .build();

            service.recordClose("BTCUSDT", closeOrder, "SL_TRIGGERED");

            // 毛利 = (93000 - 95000) * 0.5 * 1 = -1000
            assertThat(trade.getGrossProfit()).isEqualTo(-1000.0);
            assertThat(trade.getNetProfit()).isEqualTo(-1028.1);  // -1000 - 28.1
        }

        @Test
        @DisplayName("無真實出場手續費 → fallback 估算 (taker 0.04%)")
        void commissionFallbackToEstimate() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("MARKET")
                    .price(97000).quantity(0.5)
                    .commission(0)  // 無真實手續費
                    .build();

            service.recordClose("BTCUSDT", closeOrder, "SIGNAL_CLOSE");

            // 出場手續費估算 = 97000 * 0.5 * 0.0004 = 19.4
            // 總手續費 = 9.5 + 19.4 = 28.9
            assertThat(trade.getCommission()).isEqualTo(28.9);
        }

        @Test
        @DisplayName("找不到 OPEN trade → 靜默跳過，不拋例外")
        void closeWithNoOpenTrade() {
            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.empty());

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("MARKET").price(97000).quantity(0.5)
                    .build();

            assertThatCode(() ->
                service.recordClose("BTCUSDT", closeOrder, "SIGNAL_CLOSE")
            ).doesNotThrowAnyException();

            verify(tradeRepository, never()).save(any(Trade.class));
        }

        @Test
        @DisplayName("recordClose 全平 — remainingQuantity 歸 0 / totalClosedQuantity = entryQuantity (2026-05-26 prod bug regression)")
        void recordCloseResetsTrackingFields() {
            // 模擬已經部分平倉過的 trade（陳哥訊號 partial close → 後續全平 MARKET 單）
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("SHORT")
                    .entryPrice(77600.0).entryQuantity(0.638)
                    .remainingQuantity(0.319)  // 之前已平 50%
                    .totalClosedQuantity(0.319)
                    .partialProfit(225.6)
                    .entryCommission(4.96)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C-FINAL").symbol("BTCUSDT")
                    .side("BUY").type("MARKET")
                    .price(76800).quantity(0.319)
                    .commission(4.90)
                    .build();

            service.recordClose("BTCUSDT", closeOrder, "SIGNAL_CLOSE");

            assertThat(trade.getStatus()).isEqualTo("CLOSED");
            // 累計平倉量 = 之前 0.319 + 本次 0.319 = 0.638 = entryQuantity
            assertThat(trade.getTotalClosedQuantity())
                    .as("recordClose 全平也要把 totalClosed 累加到 entryQuantity")
                    .isEqualTo(0.638);
            // 剩餘 = 0（不能停在部分平倉時的 0.319）
            assertThat(trade.getRemainingQuantity())
                    .as("recordClose 全平後 remainingQuantity 必須歸 0")
                    .isEqualTo(0.0);
        }
    }

    // ==================== recordPartialClose ====================

    @Nested
    @DisplayName("recordPartialClose 部分平倉")
    class RecordPartialClose {

        @Test
        @DisplayName("首次部分平倉 50% — 追蹤剩餘量 + 累加 partialProfit + 維持 OPEN")
        void firstPartialClose() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult closeOrder = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("LIMIT").price(97000).quantity(0.5)
                    .build();

            service.recordPartialClose("BTCUSDT", closeOrder, 0.5, "SIGNAL_CLOSE");

            assertThat(trade.getStatus()).isEqualTo("OPEN");  // 維持 OPEN
            assertThat(trade.getTotalClosedQuantity()).isEqualTo(0.5);
            assertThat(trade.getRemainingQuantity()).isEqualTo(0.5);
            assertThat(trade.getExitReason()).isEqualTo("SIGNAL_CLOSE_PARTIAL");
            assertThat(trade.getExitPrice()).isEqualTo(97000.0);
            assertThat(trade.getExitQuantity()).isEqualTo(0.5);
            // partialProfit = (97000 - 95000) * 0.5 * 1 = 1000
            assertThat(trade.getPartialProfit()).isEqualTo(1000.0);
        }

        @Test
        @DisplayName("連續兩次部分平倉 — totalClosedQuantity + partialProfit 累加正確")
        void twoPartialCloses() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            // 第一次部分平倉 30% @ 96000
            OrderResult close1 = OrderResult.builder()
                    .success(true).orderId("C1").symbol("BTCUSDT")
                    .side("SELL").type("LIMIT").price(96000).quantity(0.3)
                    .build();
            service.recordPartialClose("BTCUSDT", close1, 0.3, "SIGNAL_CLOSE");

            assertThat(trade.getTotalClosedQuantity()).isEqualTo(0.3);
            assertThat(trade.getRemainingQuantity()).isEqualTo(0.7);
            // partialProfit = (96000 - 95000) * 0.3 * 1 = 300
            assertThat(trade.getPartialProfit()).isEqualTo(300.0);

            // 第二次部分平倉 20% @ 97000
            OrderResult close2 = OrderResult.builder()
                    .success(true).orderId("C2").symbol("BTCUSDT")
                    .side("SELL").type("LIMIT").price(97000).quantity(0.2)
                    .build();
            service.recordPartialClose("BTCUSDT", close2, 0.2, "SIGNAL_CLOSE");

            assertThat(trade.getTotalClosedQuantity()).isEqualTo(0.5);
            assertThat(trade.getRemainingQuantity()).isEqualTo(0.5);
            assertThat(trade.getStatus()).isEqualTo("OPEN");
            // partialProfit = 300 + (97000 - 95000) * 0.2 * 1 = 300 + 400 = 700
            assertThat(trade.getPartialProfit()).isEqualTo(700.0);
        }
    }

    // ==================== recordCloseFromStream 全平 vs 部分 ====================

    @Nested
    @DisplayName("WebSocket Stream 平倉判斷")
    class StreamCloseParsing {

        @Test
        @DisplayName("做空止損 — status=CLOSED, 方向因子 direction=-1")
        void shortSlTriggered() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("SHORT")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            service.recordCloseFromStream("BTCUSDT", 97000.0, 0.5,
                    19.4, -1000.0, "123", "SL_TRIGGERED", 1700000000000L);

            // 毛利 = (97000 - 95000) * 0.5 * (-1) = -1000（做空止損虧損）
            assertThat(trade.getGrossProfit()).isEqualTo(-1000.0);
            assertThat(trade.getStatus()).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("部分平倉判斷 — exitQty < effectiveQty * 0.999 → PARTIAL")
        void partialCloseDetection() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .entryCommission(19.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // 出場 0.5 BTC < 1.0 * 0.999 = 0.999 → 部分平倉
            service.recordCloseFromStream("BTCUSDT", 96000.0, 0.5,
                    9.6, 500.0, "456", "TP_TRIGGERED", 1700000000000L);

            assertThat(trade.getStatus()).isEqualTo("OPEN");
            assertThat(trade.getExitReason()).isEqualTo("TP_TRIGGERED_PARTIAL");
            assertThat(trade.getTotalClosedQuantity()).isEqualTo(0.5);
            assertThat(trade.getRemainingQuantity()).isEqualTo(0.5);
            // 部分平倉不算最終淨利，但累加 partialProfit
            assertThat(trade.getNetProfit()).isNull();
            // partialProfit = (96000 - 95000) * 0.5 * 1 = 500
            assertThat(trade.getPartialProfit()).isEqualTo(500.0);
        }

        @Test
        @DisplayName("容差判斷 — exitQty = effectiveQty * 0.9995 → 視為全平")
        void toleranceFullClose() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.500)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // 出場 0.4999 BTC ≈ 0.5 * 0.9998 → 在 0.999 容差內 → 全平
            service.recordCloseFromStream("BTCUSDT", 96000.0, 0.4999,
                    9.6, 499.9, "789", "SL_TRIGGERED", 1700000000000L);

            assertThat(trade.getStatus()).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("有 remainingQuantity 的全平判斷 — 部分平倉過的 trade，grossProfit 含 partialProfit")
        void fullCloseAfterPartialClose() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .remainingQuantity(0.5)  // 已部分平倉
                    .totalClosedQuantity(0.5)
                    .partialProfit(500.0)    // 之前部分平倉累計毛利 (96000-95000)*0.5 = 500
                    .entryCommission(19.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // 出場 0.5 BTC = remainingQuantity → 全平 @ 93000
            service.recordCloseFromStream("BTCUSDT", 93000.0, 0.5,
                    9.3, -1000.0, "101", "SL_TRIGGERED", 1700000000000L);

            assertThat(trade.getStatus()).isEqualTo("CLOSED");
            // 最終部分毛利 = (93000 - 95000) * 0.5 * 1 = -1000
            // 總毛利 = -1000 + 500 (partialProfit) = -500
            assertThat(trade.getGrossProfit()).isEqualTo(-500.0);
            // 累計平倉量歸位（2026-05-26 prod bug regression）：
            // remainingQuantity 必須歸 0，否則 dashboard 顯示「status=CLOSED 但仍有部分倉位」
            assertThat(trade.getRemainingQuantity())
                    .as("全平後 remainingQuantity 應該歸 0（不能留著部分平倉時的值）")
                    .isEqualTo(0.0);
            // totalClosedQuantity 應該累加本次 fill：0.5(prev) + 0.5(this) = 1.0 = entryQuantity
            assertThat(trade.getTotalClosedQuantity())
                    .as("全平後 totalClosedQuantity 應該累加到 entryQuantity")
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("沒部分平倉過的直接全平 — remainingQuantity 也要正確設成 0 / totalClosed 設成 entryQuantity")
        void directFullCloseResetsTracking() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("SHORT")
                    .entryPrice(77600.0).entryQuantity(0.5)
                    .entryCommission(7.76)
                    .status("OPEN")
                    // 沒設 remainingQuantity / totalClosedQuantity（null）
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // 出場 0.5 BTC = entryQuantity → 全平 @ 76000
            service.recordCloseFromStream("BTCUSDT", 76000.0, 0.5,
                    7.6, 800.0, "202", "SL_TRIGGERED", 1700000000000L);

            assertThat(trade.getStatus()).isEqualTo("CLOSED");
            assertThat(trade.getRemainingQuantity())
                    .as("直接全平也應該寫 remainingQuantity=0，不能維持 null")
                    .isEqualTo(0.0);
            assertThat(trade.getTotalClosedQuantity())
                    .as("直接全平應該記錄 totalClosed=entryQuantity")
                    .isEqualTo(0.5);
        }
    }

    // ==================== Issue #52 Phase 2：Binance 倉位 double-check ====================

    @Nested
    @DisplayName("recordCloseFromStream Phase 2 — Binance 倉位 double-check")
    class Phase2BinancePositionDoubleCheck {

        /**
         * 重現 5/29 chen-ge bug：
         * 已部分平倉 entry=1.0 / remaining=0.5（樂觀 accounting 已寫入），
         * 同一筆 fill 的 STREAM event 進來 (exitQty=0.5 == effectiveQty 0.5) → 判 FULL。
         * Binance 卻還有 0.5 倉位 → Phase 2 應降級成 partial，避免誤標 CLOSED。
         */
        @Test
        @DisplayName("Binance 仍有倉位 + 判全平 → 降級 partial，用 Binance 倉位為權威")
        void binanceStillHasPosition_judgedFull_downgradeToPartial() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .remainingQuantity(0.5)         // 樂觀 accounting 已扣
                    .totalClosedQuantity(0.5)
                    .partialProfit(500.0)
                    .entryCommission(19.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // exitQty 0.5 == remaining 0.5 → 既有邏輯會判 FULL
            // 但 Binance 顯示還有 0.5 倉位（即此 STREAM event 是樂觀 accounting 已算過的同筆 fill）
            boolean fullClose = service.recordCloseFromStream("BTCUSDT", 96000.0, 0.5,
                    9.6, 500.0, "456", "SIGNAL_CLOSE", 1700000000000L,
                    OptionalDouble.of(0.5));

            assertThat(fullClose)
                    .as("Phase 2 降級後不算全平 → caller 不該 cancel SL/TP")
                    .isFalse();
            assertThat(trade.getStatus())
                    .as("status 應維持 OPEN，不能被誤標 CLOSED")
                    .isEqualTo("OPEN");
            assertThat(trade.getExitReason())
                    .as("應標為 PARTIAL")
                    .isEqualTo("SIGNAL_CLOSE_PARTIAL");
            assertThat(trade.getRemainingQuantity())
                    .as("用 Binance 倉位為權威 (0.5)，不能用 effectiveQty-exitQty=0")
                    .isEqualTo(0.5);
            assertThat(trade.getTotalClosedQuantity())
                    .as("用 entry - binance = 1.0 - 0.5 = 0.5 為權威")
                    .isEqualTo(0.5);
        }

        @Test
        @DisplayName("Binance 倉位 = 0 + 判全平 → 不降級，走 FULL 分支標 CLOSED")
        void binanceZeroPosition_judgedFull_stayFull() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // 完美 case：判全平 + Binance 也確認 0 → 真正全平
            boolean fullClose = service.recordCloseFromStream("BTCUSDT", 96000.0, 0.5,
                    9.6, 500.0, "789", "SIGNAL_CLOSE", 1700000000000L,
                    OptionalDouble.of(0.0));

            assertThat(fullClose).isTrue();
            assertThat(trade.getStatus()).isEqualTo("CLOSED");
            assertThat(trade.getRemainingQuantity()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("Binance 倉位 < 0.0001 容差 + 判全平 → 不降級（避免浮點誤差誤觸發）")
        void binancePositionWithinTolerance_judgedFull_stayFull() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // 0.00005 < 0.0001 容差 → 視為 0
            boolean fullClose = service.recordCloseFromStream("BTCUSDT", 96000.0, 0.5,
                    9.6, 500.0, "789", "TP_TRIGGERED", 1700000000000L,
                    OptionalDouble.of(0.00005));

            assertThat(fullClose).isTrue();
            assertThat(trade.getStatus()).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("OptionalDouble.empty() → 走 legacy 判定，等同 8-arg 版本")
        void emptyHint_fallbackToLegacy() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // hint 不存在 → 完全走原邏輯
            boolean fullClose = service.recordCloseFromStream("BTCUSDT", 96000.0, 0.5,
                    9.6, 500.0, "abc", "SIGNAL_CLOSE", 1700000000000L,
                    OptionalDouble.empty());

            assertThat(fullClose).isTrue();
            assertThat(trade.getStatus()).isEqualTo("CLOSED");
        }

        @Test
        @DisplayName("已經判 partial 的 case + hint 有值 → 不做額外動作（hint 只在判 FULL 時生效）")
        void alreadyPartial_hintIgnored() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .entryCommission(19.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // exitQty 0.5 < effective 1.0 * 0.999 → 已是 partial
            boolean fullClose = service.recordCloseFromStream("BTCUSDT", 96000.0, 0.5,
                    9.6, 500.0, "xyz", "TP_TRIGGERED", 1700000000000L,
                    OptionalDouble.of(0.5));

            assertThat(fullClose).isFalse();
            assertThat(trade.getStatus()).isEqualTo("OPEN");
            // 走 legacy partial 分支 — total_closed=0+0.5=0.5, remaining=1.0-0.5=0.5
            assertThat(trade.getTotalClosedQuantity()).isEqualTo(0.5);
            assertThat(trade.getRemainingQuantity()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("SHORT 倉位 hint 為負數 → 取絕對值判斷")
        void shortPositionNegativeHint_useAbsoluteValue() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("SHORT")
                    .entryPrice(95000.0).entryQuantity(1.0)
                    .remainingQuantity(0.5)
                    .totalClosedQuantity(0.5)
                    .entryCommission(19.0)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // SHORT 倉位 Binance 回 -0.5 → 取 abs 後判斷
            boolean fullClose = service.recordCloseFromStream("BTCUSDT", 94000.0, 0.5,
                    9.4, 500.0, "456", "SIGNAL_CLOSE", 1700000000000L,
                    OptionalDouble.of(-0.5));

            assertThat(fullClose).isFalse();
            assertThat(trade.getStatus()).isEqualTo("OPEN");
            assertThat(trade.getRemainingQuantity()).isEqualTo(0.5);
            assertThat(trade.getTotalClosedQuantity()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("8-arg 舊簽名應委派至 9-arg 版本 (empty hint) — 行為一致")
        void oldSignatureDelegatesToNewWithEmpty() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).entryQuantity(0.5)
                    .entryCommission(9.5)
                    .status("OPEN")
                    .build();

            when(tradeRepository.findOpenOrPendingCloseTrade("BTCUSDT")).thenReturn(java.util.List.of(trade));

            // 8-arg 版（既有測試也走這條）
            boolean fullClose = service.recordCloseFromStream("BTCUSDT", 96000.0, 0.5,
                    9.6, 500.0, "abc", "SIGNAL_CLOSE", 1700000000000L);

            assertThat(fullClose).isTrue();
            assertThat(trade.getStatus()).isEqualTo("CLOSED");
        }
    }

    // ==================== 其他記錄方法 ====================

    @Nested
    @DisplayName("其他記錄方法")
    class OtherRecordMethods {

        @Test
        @DisplayName("recordCancel — 標記 CANCELLED")
        void recordCancelMarksAsCancelled() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            service.recordCancel("BTCUSDT");

            assertThat(trade.getStatus()).isEqualTo("CANCELLED");
            assertThat(trade.getExitReason()).isEqualTo("CANCEL");
            verify(tradeRepository).save(trade);
        }

        @Test
        @DisplayName("recordMoveSL — 更新止損價 + 寫事件")
        void recordMoveSLUpdatesStopLoss() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG")
                    .entryPrice(95000.0).stopLoss(93000.0).status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            OrderResult slOrder = OrderResult.builder()
                    .success(true).orderId("SL2").symbol("BTCUSDT")
                    .side("SELL").type("STOP_MARKET").price(94500).quantity(0.5)
                    .build();

            service.recordMoveSL("BTCUSDT", slOrder, 93000, 94500);

            assertThat(trade.getStopLoss()).isEqualTo(94500.0);
            verify(tradeRepository).save(trade);

            ArgumentCaptor<TradeEvent> eventCaptor = ArgumentCaptor.forClass(TradeEvent.class);
            verify(tradeEventRepository).save(eventCaptor.capture());
            TradeEvent event = eventCaptor.getValue();
            assertThat(event.getEventType()).isEqualTo("MOVE_SL");
            assertThat(event.getPrice()).isEqualTo(94500.0);
        }

        @Test
        @DisplayName("recordProtectionLost — 記錄 SL_LOST 事件")
        void recordSLLost() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG").status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            service.recordProtectionLost("BTCUSDT", "STOP_MARKET", "SL123", "CANCELED");

            ArgumentCaptor<TradeEvent> eventCaptor = ArgumentCaptor.forClass(TradeEvent.class);
            verify(tradeEventRepository).save(eventCaptor.capture());
            TradeEvent event = eventCaptor.getValue();
            assertThat(event.getEventType()).isEqualTo("SL_LOST");
            assertThat(event.getBinanceOrderId()).isEqualTo("SL123");
        }

        @Test
        @DisplayName("recordProtectionLost — 記錄 TP_LOST 事件")
        void recordTPLost() {
            Trade trade = Trade.builder()
                    .tradeId("t1").symbol("BTCUSDT").side("LONG").status("OPEN")
                    .build();

            when(tradeRepository.findOpenTrade("BTCUSDT")).thenReturn(Optional.of(trade));

            service.recordProtectionLost("BTCUSDT", "TAKE_PROFIT_MARKET", "TP456", "EXPIRED");

            ArgumentCaptor<TradeEvent> eventCaptor = ArgumentCaptor.forClass(TradeEvent.class);
            verify(tradeEventRepository).save(eventCaptor.capture());
            TradeEvent event = eventCaptor.getValue();
            assertThat(event.getEventType()).isEqualTo("TP_LOST");
        }
    }
}
