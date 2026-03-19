package com.trader.papertrade.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.dto.SignalScore;
import com.trader.papertrade.config.PaperTradingConfig;
import com.trader.shared.model.SignalSource;
import com.trader.shared.model.TradeRequest;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.TradeRepository;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PaperTradeService 單元測試
 *
 * 覆蓋：建立模擬交易、平倉 PnL 計算、移動止損、邊界條件
 */
class PaperTradeServiceTest {

    private TradeRepository tradeRepository;
    private PaperTradingConfig config;
    private ObjectMapper objectMapper;
    private PaperTradeService service;

    @BeforeEach
    void setUp() {
        tradeRepository = mock(TradeRepository.class);
        config = new PaperTradingConfig(1000, 10, 90000);
        objectMapper = new ObjectMapper();
        service = new PaperTradeService(tradeRepository, config, objectMapper);
    }

    private TradeRequest createRequest(String symbol, String action, String side, Double entryPrice) {
        TradeRequest req = new TradeRequest();
        req.setSymbol(symbol);
        req.setAction(action);
        req.setSide(side);
        req.setEntryPrice(entryPrice);
        return req;
    }

    // ==================== createPaperTrade ====================

    @Test
    @DisplayName("ENTRY 訊號建立 LONG 模擬交易 — 欄位正確")
    void createPaperTrade_long_success() {
        TradeRequest request = createRequest("BTCUSDT", "ENTRY", "LONG", 50000.0);
        request.setStopLoss(49000.0);
        request.setTakeProfit(52000.0);
        request.setSource(SignalSource.builder()
                .platform("DISCORD")
                .channelId("ch1")
                .guildId("g1")
                .authorName("TestAuthor")
                .messageId("msg1")
                .build());

        when(tradeRepository.save(any(Trade.class))).thenAnswer(inv -> inv.getArgument(0));

        Trade result = service.createPaperTrade(request, null);

        assertThat(result.getUserId()).isEqualTo("PAPER_TRADE_SYSTEM");
        assertThat(result.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(result.getSide()).isEqualTo("LONG");
        assertThat(result.getEntryPrice()).isEqualTo(50000.0);
        assertThat(result.getStatus()).isEqualTo("OPEN");
        assertThat(result.isSimulated()).isTrue();
        assertThat(result.getEntryOrderId()).isEqualTo("PAPER");
        assertThat(result.getLeverage()).isEqualTo(10);
        assertThat(result.getRiskAmount()).isEqualTo(1000.0);

        // qty = 1000 * 10 / 50000 = 0.2
        assertThat(result.getEntryQuantity()).isEqualTo(0.2);

        // entryCommission = 50000 * 0.2 * 0.0002 = 2.0
        assertThat(result.getEntryCommission()).isEqualTo(2.0);

        // 來源欄位
        assertThat(result.getSourcePlatform()).isEqualTo("DISCORD");
        assertThat(result.getSourceChannelId()).isEqualTo("ch1");
        assertThat(result.getSourceAuthorName()).isEqualTo("TestAuthor");

        // 止損止盈
        assertThat(result.getStopLoss()).isEqualTo(49000.0);
        assertThat(result.getTakeProfits()).contains("52000");

        verify(tradeRepository).save(any(Trade.class));
    }

    @Test
    @DisplayName("ENTRY 含 AI 評分 — aiConfidence 和 aiReasoning 寫入")
    void createPaperTrade_withAiScore() {
        TradeRequest request = createRequest("ETHUSDT", "ENTRY", "SHORT", 3500.0);

        SignalScore score = SignalScore.builder()
                .confidence(85)
                .reasoning("Strong bearish signal")
                .build();

        when(tradeRepository.save(any(Trade.class))).thenAnswer(inv -> inv.getArgument(0));

        Trade result = service.createPaperTrade(request, score);

        assertThat(result.getAiConfidence()).isEqualTo(85);
        assertThat(result.getAiReasoning()).isEqualTo("Strong bearish signal");
    }

    @Test
    @DisplayName("entryPrice 為 null 時拋出 IllegalArgumentException")
    void createPaperTrade_nullEntryPrice_throws() {
        TradeRequest request = createRequest("BTCUSDT", "ENTRY", "LONG", null);

        assertThatThrownBy(() -> service.createPaperTrade(request, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模擬交易需要有效的入場價");
    }

    @Test
    @DisplayName("entryPrice 為 0 時拋出 IllegalArgumentException")
    void createPaperTrade_zeroEntryPrice_throws() {
        TradeRequest request = createRequest("BTCUSDT", "ENTRY", "LONG", 0.0);

        assertThatThrownBy(() -> service.createPaperTrade(request, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ==================== closePaperTrade ====================

    @Test
    @DisplayName("LONG 平倉 — PnL 計算正確（獲利）")
    void closePaperTrade_long_profit() {
        Trade openTrade = Trade.builder()
                .tradeId("t1")
                .userId("PAPER_TRADE_SYSTEM")
                .symbol("BTCUSDT")
                .side("LONG")
                .entryPrice(50000.0)
                .entryQuantity(0.2)
                .entryCommission(2.0)
                .status("OPEN")
                .simulated(true)
                .build();

        when(tradeRepository.findOpenSimulatedTrades("BTCUSDT", "ch1"))
                .thenReturn(List.of(openTrade));
        when(tradeRepository.save(any(Trade.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Trade> result = service.closePaperTrade("BTCUSDT", "ch1", 52000.0, "TAKE_PROFIT");

        assertThat(result).isPresent();
        Trade closed = result.get();
        assertThat(closed.getStatus()).isEqualTo("CLOSED");
        assertThat(closed.getExitPrice()).isEqualTo(52000.0);
        assertThat(closed.getExitReason()).isEqualTo("TAKE_PROFIT");
        assertThat(closed.getExitOrderId()).isEqualTo("PAPER");

        // grossProfit = (52000 - 50000) * 0.2 * 1 = 400
        assertThat(closed.getGrossProfit()).isEqualTo(400.0);

        // commission = 2.0 (entry) + 52000 * 0.2 * 0.0004 (exit taker) = 2.0 + 4.16 = 6.16
        assertThat(closed.getCommission()).isEqualTo(6.16);

        // netProfit = 400 - 6.16 = 393.84
        assertThat(closed.getNetProfit()).isEqualTo(393.84);
    }

    @Test
    @DisplayName("SHORT 平倉 — PnL 計算正確（獲利）")
    void closePaperTrade_short_profit() {
        Trade openTrade = Trade.builder()
                .tradeId("t2")
                .userId("PAPER_TRADE_SYSTEM")
                .symbol("ETHUSDT")
                .side("SHORT")
                .entryPrice(3500.0)
                .entryQuantity(2.857143)
                .entryCommission(2.0)
                .status("OPEN")
                .simulated(true)
                .build();

        when(tradeRepository.findOpenSimulatedTrades("ETHUSDT", "ch2"))
                .thenReturn(List.of(openTrade));
        when(tradeRepository.save(any(Trade.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Trade> result = service.closePaperTrade("ETHUSDT", "ch2", 3300.0, "STOP_LOSS");

        assertThat(result).isPresent();
        Trade closed = result.get();

        // grossProfit = (3300 - 3500) * 2.857143 * (-1) = 571.43
        assertThat(closed.getGrossProfit()).isGreaterThan(0);
        assertThat(closed.getNetProfit()).isGreaterThan(0);
    }

    @Test
    @DisplayName("找不到模擬持倉時返回 empty")
    void closePaperTrade_notFound_returnsEmpty() {
        when(tradeRepository.findOpenSimulatedTrades("BTCUSDT", "ch1"))
                .thenReturn(List.of());

        Optional<Trade> result = service.closePaperTrade("BTCUSDT", "ch1", 52000.0, "SIGNAL_CLOSE");

        assertThat(result).isEmpty();
        verify(tradeRepository, never()).saveAll(any());
    }

    // ==================== movePaperStopLoss ====================

    @Test
    @DisplayName("移動止損 — SL 和 TP 都更新")
    void movePaperStopLoss_updatesBoth() {
        Trade openTrade = Trade.builder()
                .tradeId("t3")
                .symbol("BTCUSDT")
                .side("LONG")
                .stopLoss(49000.0)
                .status("OPEN")
                .simulated(true)
                .build();

        when(tradeRepository.findOpenSimulatedTrades("BTCUSDT", "ch1"))
                .thenReturn(List.of(openTrade));
        when(tradeRepository.save(any(Trade.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Trade> result = service.movePaperStopLoss("BTCUSDT", "ch1", 50500.0, 55000.0);

        assertThat(result).isPresent();
        Trade updated = result.get();
        assertThat(updated.getStopLoss()).isEqualTo(50500.0);
        assertThat(updated.getTakeProfits()).contains("55000");
        verify(tradeRepository).saveAll(any());
    }

    @Test
    @DisplayName("移動止損 — 只更新 SL（TP 為 null）")
    void movePaperStopLoss_onlySl() {
        Trade openTrade = Trade.builder()
                .tradeId("t4")
                .symbol("BTCUSDT")
                .side("LONG")
                .stopLoss(49000.0)
                .takeProfits("{\"targets\":[52000.0]}")
                .status("OPEN")
                .simulated(true)
                .build();

        when(tradeRepository.findOpenSimulatedTrades("BTCUSDT", "ch1"))
                .thenReturn(List.of(openTrade));
        when(tradeRepository.save(any(Trade.class))).thenAnswer(inv -> inv.getArgument(0));

        Optional<Trade> result = service.movePaperStopLoss("BTCUSDT", "ch1", 50500.0, null);

        assertThat(result).isPresent();
        assertThat(result.get().getStopLoss()).isEqualTo(50500.0);
        // TP 不變
        assertThat(result.get().getTakeProfits()).contains("52000");
    }

    @Test
    @DisplayName("移動止損 — 找不到持倉返回 empty")
    void movePaperStopLoss_notFound_returnsEmpty() {
        when(tradeRepository.findOpenSimulatedTrades("BTCUSDT", "ch1"))
                .thenReturn(List.of());

        Optional<Trade> result = service.movePaperStopLoss("BTCUSDT", "ch1", 50500.0, null);

        assertThat(result).isEmpty();
        verify(tradeRepository, never()).saveAll(any());
    }
}
