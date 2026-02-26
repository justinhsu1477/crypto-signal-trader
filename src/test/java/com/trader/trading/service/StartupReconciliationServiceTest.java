package com.trader.trading.service;

import com.trader.trading.entity.Trade;
import com.trader.trading.repository.TradeRepository;
import com.trader.notification.service.DiscordWebhookService;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StartupReconciliationService 單元測試
 *
 * 測試重點：
 * 1. reconcileZombieOpenTrades — 有掛單時不清理
 * 2. reconcileZombieOpenTrades — 無持倉+無掛單時標 CANCELLED
 * 3. reconcileZombieOpenTrades — 查詢掛單失敗時保守跳過
 * 4. reconcileZombieOpenTrades — 有持倉時不做處理
 */
class StartupReconciliationServiceTest {

    private TradeRepository tradeRepository;
    private BinanceFuturesService binanceFuturesService;
    private DiscordWebhookService discordWebhookService;
    private StartupReconciliationService service;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        binanceFuturesService = mock(BinanceFuturesService.class);
        discordWebhookService = mock(DiscordWebhookService.class);
        service = new StartupReconciliationService(tradeRepository, binanceFuturesService, discordWebhookService);
    }

    // ==================== reconcileZombieOpenTrades ====================

    @Nested
    @DisplayName("殭屍 OPEN 交易清理")
    class ZombieCleanupTests {

        @Test
        @DisplayName("無 OPEN 交易 → 直接回傳 0")
        void noOpenTrades_returnsZero() {
            when(tradeRepository.findByStatus("OPEN")).thenReturn(Collections.emptyList());

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isZero();
            assertThat(report).isEmpty();
            verify(binanceFuturesService, never()).getCurrentPositionAmount(any());
        }

        @Test
        @DisplayName("Binance 有持倉 → 保留 OPEN，不做任何處理")
        void hasPosition_keepOpen() {
            Trade trade = createOpenTrade("trade-1", "BTCUSDT", "LONG");
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(binanceFuturesService.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.143);

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isZero();
            assertThat(report).isEmpty();
            assertThat(trade.getStatus()).isEqualTo("OPEN");
            verify(tradeRepository, never()).save(any());
            verify(binanceFuturesService, never()).hasOpenEntryOrders(any());
        }

        @Test
        @DisplayName("Binance 無持倉 + 無掛單 → 標為 CANCELLED")
        void noPositionNoOrders_markCancelled() {
            Trade trade = createOpenTrade("trade-2", "ETHUSDT", "SHORT");
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(binanceFuturesService.getCurrentPositionAmount("ETHUSDT")).thenReturn(0.0);
            when(binanceFuturesService.hasOpenEntryOrders("ETHUSDT")).thenReturn(false);

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isEqualTo(1);
            assertThat(trade.getStatus()).isEqualTo("CANCELLED");
            assertThat(trade.getExitReason()).isEqualTo("STALE_CLEANUP_STARTUP");
            assertThat(trade.getExitTime()).isNotNull();
            verify(tradeRepository).save(trade);
            assertThat(report).hasSize(1);
            assertThat(report.get(0)).contains("CANCELLED").contains("無持倉且無掛單");
        }

        @Test
        @DisplayName("Binance 無持倉 + 有未成交掛單 → 保留 OPEN（不清理）")
        void noPositionButHasOrders_keepOpen() {
            Trade trade = createOpenTrade("trade-3", "BTCUSDT", "LONG");
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(binanceFuturesService.getCurrentPositionAmount("BTCUSDT")).thenReturn(0.0);
            when(binanceFuturesService.hasOpenEntryOrders("BTCUSDT")).thenReturn(true);

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isZero();
            assertThat(trade.getStatus()).isEqualTo("OPEN");
            verify(tradeRepository, never()).save(any());
            assertThat(report).hasSize(1);
            assertThat(report.get(0)).contains("未成交掛單").contains("保留 OPEN");
        }

        @Test
        @DisplayName("Binance 無持倉 + 查詢掛單失敗 → 保守跳過（不清理）")
        void noPositionOrderQueryFails_skipConservatively() {
            Trade trade = createOpenTrade("trade-4", "SOLUSDT", "LONG");
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(binanceFuturesService.getCurrentPositionAmount("SOLUSDT")).thenReturn(0.0);
            when(binanceFuturesService.hasOpenEntryOrders("SOLUSDT"))
                    .thenThrow(new RuntimeException("API error"));

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isZero();
            assertThat(trade.getStatus()).isEqualTo("OPEN");
            verify(tradeRepository, never()).save(any());
            assertThat(report).hasSize(1);
            assertThat(report.get(0)).contains("查詢掛單失敗").contains("保守跳過");
        }

        @Test
        @DisplayName("查詢 Binance 持倉失敗 → 跳過不處理")
        void positionQueryFails_skip() {
            Trade trade = createOpenTrade("trade-5", "XRPUSDT", "SHORT");
            when(tradeRepository.findByStatus("OPEN")).thenReturn(List.of(trade));
            when(binanceFuturesService.getCurrentPositionAmount("XRPUSDT"))
                    .thenThrow(new RuntimeException("Network error"));

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isZero();
            assertThat(trade.getStatus()).isEqualTo("OPEN");
            verify(tradeRepository, never()).save(any());
            assertThat(report).hasSize(1);
            assertThat(report.get(0)).contains("查詢失敗").contains("跳過");
        }

        @Test
        @DisplayName("多筆 OPEN 交易 — 混合場景：一筆清理一筆保留")
        void multipleOpenTrades_mixedScenarios() {
            Trade zombieTrade = createOpenTrade("trade-6", "ETHUSDT", "LONG");
            Trade liveTrade = createOpenTrade("trade-7", "BTCUSDT", "SHORT");
            Trade pendingTrade = createOpenTrade("trade-8", "SOLUSDT", "LONG");

            when(tradeRepository.findByStatus("OPEN"))
                    .thenReturn(List.of(zombieTrade, liveTrade, pendingTrade));

            // ETHUSDT: 無持倉+無掛單 → 清理
            when(binanceFuturesService.getCurrentPositionAmount("ETHUSDT")).thenReturn(0.0);
            when(binanceFuturesService.hasOpenEntryOrders("ETHUSDT")).thenReturn(false);

            // BTCUSDT: 有持倉 → 保留
            when(binanceFuturesService.getCurrentPositionAmount("BTCUSDT")).thenReturn(-0.5);

            // SOLUSDT: 無持倉+有掛單 → 保留
            when(binanceFuturesService.getCurrentPositionAmount("SOLUSDT")).thenReturn(0.0);
            when(binanceFuturesService.hasOpenEntryOrders("SOLUSDT")).thenReturn(true);

            List<String> report = new ArrayList<>();
            int result = service.reconcileZombieOpenTrades(report);

            assertThat(result).isEqualTo(1);
            assertThat(zombieTrade.getStatus()).isEqualTo("CANCELLED");
            assertThat(liveTrade.getStatus()).isEqualTo("OPEN");
            assertThat(pendingTrade.getStatus()).isEqualTo("OPEN");
            verify(tradeRepository, times(1)).save(zombieTrade);
        }
    }

    // ==================== Helper ====================

    private Trade createOpenTrade(String tradeId, String symbol, String side) {
        Trade trade = new Trade();
        trade.setTradeId(tradeId);
        trade.setSymbol(symbol);
        trade.setSide(side);
        trade.setStatus("OPEN");
        return trade;
    }
}
