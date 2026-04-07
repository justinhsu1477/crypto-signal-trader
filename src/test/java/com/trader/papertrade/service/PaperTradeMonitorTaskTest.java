package com.trader.papertrade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.TradeRepository;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PaperTradeMonitorTask 單元測試
 *
 * 覆蓋：TP/SL 觸發平倉、無持倉跳過、查價失敗容錯、LONG/SHORT 方向
 */
class PaperTradeMonitorTaskTest {

    private TradeRepository tradeRepository;
    private PaperTradeService paperTradeService;
    private BinancePriceClient binancePriceClient;
    private DiscordWebhookService discordWebhookService;
    private ObjectMapper objectMapper;
    private PaperTradeMonitorTask task;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        paperTradeService = mock(PaperTradeService.class);
        binancePriceClient = mock(BinancePriceClient.class);
        discordWebhookService = mock(DiscordWebhookService.class);
        objectMapper = new ObjectMapper();

        task = new PaperTradeMonitorTask(
                tradeRepository, paperTradeService, binancePriceClient,
                discordWebhookService, objectMapper);
    }

    @Test
    @DisplayName("無 OPEN 模擬持倉 — 直接返回，不查價")
    void noOpenTrades_skips() {
        when(tradeRepository.findAllOpenSimulatedTrades()).thenReturn(List.of());

        task.monitorOpenPaperTrades();

        verify(binancePriceClient, never()).getAllMarkPrices();
        verify(paperTradeService, never()).closePaperTrade(any(), any(), anyDouble(), any());
    }

    @Test
    @DisplayName("LONG 觸及止損 — 自動平倉")
    void long_stopLossHit_closes() {
        Trade trade = Trade.builder()
                .tradeId("t1")
                .symbol("BTCUSDT")
                .side("LONG")
                .entryPrice(50000.0)
                .entryQuantity(0.2)
                .stopLoss(49000.0)
                .takeProfits("{\"targets\":[52000.0]}")
                .sourceChannelId("ch1")
                .sourceAuthorName("Author1")
                .status("OPEN")
                .simulated(true)
                .build();

        when(tradeRepository.findAllOpenSimulatedTrades()).thenReturn(List.of(trade));
        when(binancePriceClient.getAllMarkPrices()).thenReturn(Map.of("BTCUSDT", 48500.0));
        when(paperTradeService.closePaperTrade(eq("BTCUSDT"), eq("ch1"), eq(49000.0), eq("STOP_LOSS")))
                .thenReturn(Optional.of(trade));

        task.monitorOpenPaperTrades();

        verify(paperTradeService).closePaperTrade("BTCUSDT", "ch1", 49000.0, "STOP_LOSS");
        verify(discordWebhookService).sendNotificationToAdmins(contains("STOP_LOSS"), anyString(), anyInt());
    }

    @Test
    @DisplayName("LONG 觸及止盈 — 自動平倉")
    void long_takeProfitHit_closes() {
        Trade trade = Trade.builder()
                .tradeId("t2")
                .symbol("ETHUSDT")
                .side("LONG")
                .entryPrice(3500.0)
                .entryQuantity(2.857)
                .stopLoss(3300.0)
                .takeProfits("{\"targets\":[3800.0]}")
                .sourceChannelId("ch2")
                .status("OPEN")
                .simulated(true)
                .build();

        when(tradeRepository.findAllOpenSimulatedTrades()).thenReturn(List.of(trade));
        when(binancePriceClient.getAllMarkPrices()).thenReturn(Map.of("ETHUSDT", 3850.0));
        when(paperTradeService.closePaperTrade(eq("ETHUSDT"), eq("ch2"), eq(3800.0), eq("TAKE_PROFIT")))
                .thenReturn(Optional.of(trade));

        task.monitorOpenPaperTrades();

        verify(paperTradeService).closePaperTrade("ETHUSDT", "ch2", 3800.0, "TAKE_PROFIT");
    }

    @Test
    @DisplayName("SHORT 觸及止損（價格上漲）— 自動平倉")
    void short_stopLossHit_closes() {
        Trade trade = Trade.builder()
                .tradeId("t3")
                .symbol("BTCUSDT")
                .side("SHORT")
                .entryPrice(50000.0)
                .entryQuantity(0.2)
                .stopLoss(51000.0)
                .takeProfits("{\"targets\":[48000.0]}")
                .sourceChannelId("ch3")
                .status("OPEN")
                .simulated(true)
                .build();

        when(tradeRepository.findAllOpenSimulatedTrades()).thenReturn(List.of(trade));
        when(binancePriceClient.getAllMarkPrices()).thenReturn(Map.of("BTCUSDT", 51500.0));
        when(paperTradeService.closePaperTrade(eq("BTCUSDT"), eq("ch3"), eq(51000.0), eq("STOP_LOSS")))
                .thenReturn(Optional.of(trade));

        task.monitorOpenPaperTrades();

        verify(paperTradeService).closePaperTrade("BTCUSDT", "ch3", 51000.0, "STOP_LOSS");
    }

    @Test
    @DisplayName("SHORT 觸及止盈（價格下跌）— 自動平倉")
    void short_takeProfitHit_closes() {
        Trade trade = Trade.builder()
                .tradeId("t4")
                .symbol("BTCUSDT")
                .side("SHORT")
                .entryPrice(50000.0)
                .entryQuantity(0.2)
                .stopLoss(51000.0)
                .takeProfits("{\"targets\":[48000.0]}")
                .sourceChannelId("ch4")
                .status("OPEN")
                .simulated(true)
                .build();

        when(tradeRepository.findAllOpenSimulatedTrades()).thenReturn(List.of(trade));
        when(binancePriceClient.getAllMarkPrices()).thenReturn(Map.of("BTCUSDT", 47500.0));
        when(paperTradeService.closePaperTrade(eq("BTCUSDT"), eq("ch4"), eq(48000.0), eq("TAKE_PROFIT")))
                .thenReturn(Optional.of(trade));

        task.monitorOpenPaperTrades();

        verify(paperTradeService).closePaperTrade("BTCUSDT", "ch4", 48000.0, "TAKE_PROFIT");
    }

    @Test
    @DisplayName("價格未觸及 TP/SL — 不平倉")
    void priceInRange_noClose() {
        Trade trade = Trade.builder()
                .tradeId("t5")
                .symbol("BTCUSDT")
                .side("LONG")
                .entryPrice(50000.0)
                .entryQuantity(0.2)
                .stopLoss(49000.0)
                .takeProfits("{\"targets\":[52000.0]}")
                .sourceChannelId("ch5")
                .status("OPEN")
                .simulated(true)
                .build();

        when(tradeRepository.findAllOpenSimulatedTrades()).thenReturn(List.of(trade));
        when(binancePriceClient.getAllMarkPrices()).thenReturn(Map.of("BTCUSDT", 50500.0));

        task.monitorOpenPaperTrades();

        verify(paperTradeService, never()).closePaperTrade(any(), any(), anyDouble(), any());
    }

    @Test
    @DisplayName("批次查價失敗 — 跳過本輪，不平倉")
    void priceApiFailure_skipsRound() {
        Trade trade = Trade.builder()
                .tradeId("t6")
                .symbol("BTCUSDT")
                .side("LONG")
                .stopLoss(49000.0)
                .sourceChannelId("ch6")
                .status("OPEN")
                .simulated(true)
                .build();

        when(tradeRepository.findAllOpenSimulatedTrades()).thenReturn(List.of(trade));
        when(binancePriceClient.getAllMarkPrices()).thenThrow(new RuntimeException("API timeout"));

        task.monitorOpenPaperTrades();

        verify(paperTradeService, never()).closePaperTrade(any(), any(), anyDouble(), any());
    }

    @Test
    @DisplayName("幣種不在價格表中 — 跳過該持倉")
    void symbolNotInPrices_skipped() {
        Trade trade = Trade.builder()
                .tradeId("t7")
                .symbol("SOLUSDT")
                .side("LONG")
                .stopLoss(100.0)
                .sourceChannelId("ch7")
                .status("OPEN")
                .simulated(true)
                .build();

        when(tradeRepository.findAllOpenSimulatedTrades()).thenReturn(List.of(trade));
        when(binancePriceClient.getAllMarkPrices()).thenReturn(Map.of("BTCUSDT", 50000.0));

        task.monitorOpenPaperTrades();

        verify(paperTradeService, never()).closePaperTrade(any(), any(), anyDouble(), any());
    }

    @Test
    @DisplayName("平倉失敗 — 不中斷其他持倉檢查")
    void closeFailure_continuesOthers() {
        Trade trade1 = Trade.builder()
                .tradeId("t8")
                .symbol("BTCUSDT")
                .side("LONG")
                .entryPrice(50000.0)
                .entryQuantity(0.2)
                .stopLoss(49000.0)
                .sourceChannelId("ch8")
                .status("OPEN")
                .simulated(true)
                .build();

        Trade trade2 = Trade.builder()
                .tradeId("t9")
                .symbol("ETHUSDT")
                .side("LONG")
                .entryPrice(3500.0)
                .entryQuantity(2.857)
                .stopLoss(3300.0)
                .sourceChannelId("ch9")
                .status("OPEN")
                .simulated(true)
                .build();

        when(tradeRepository.findAllOpenSimulatedTrades()).thenReturn(List.of(trade1, trade2));
        when(binancePriceClient.getAllMarkPrices()).thenReturn(
                Map.of("BTCUSDT", 48500.0, "ETHUSDT", 3200.0));

        // 第一筆平倉失敗
        when(paperTradeService.closePaperTrade(eq("BTCUSDT"), eq("ch8"), eq(49000.0), eq("STOP_LOSS")))
                .thenThrow(new RuntimeException("DB error"));
        // 第二筆平倉成功
        when(paperTradeService.closePaperTrade(eq("ETHUSDT"), eq("ch9"), eq(3300.0), eq("STOP_LOSS")))
                .thenReturn(Optional.of(trade2));

        task.monitorOpenPaperTrades();

        // 兩筆都嘗試平倉
        verify(paperTradeService).closePaperTrade("BTCUSDT", "ch8", 49000.0, "STOP_LOSS");
        verify(paperTradeService).closePaperTrade("ETHUSDT", "ch9", 3300.0, "STOP_LOSS");
    }
}
