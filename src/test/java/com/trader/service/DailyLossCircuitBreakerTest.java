package com.trader.service;

import com.trader.shared.config.BinanceConfig;
import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.service.*;
import com.trader.user.service.UserApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 每日虧損熔斷機制 專項測試
 *
 * 測試重點：
 * 1. 精確到達上限邊界 → 拒絕
 * 2. 未達上限 → 允許
 * 3. 虧損為 0 → 允許
 * 4. maxDailyLoss=0 → 不啟用熔斷
 * 5. 多筆虧損累加恰好到達上限
 * 6. 熔斷後發 Discord 通知
 */
class DailyLossCircuitBreakerTest {

    private TradeRecordService mockTradeRecord;
    private DiscordWebhookService mockWebhook;
    private TradeConfigResolver mockTradeConfigResolver;
    private BinanceFuturesService service;

    @BeforeEach
    void setUp() {
        RiskConfig riskConfig = new RiskConfig(
                50000, 2000, true,
                0.20, 3, 2.0, 20,
                List.of("BTCUSDT"), "BTCUSDT"
        );
        mockTradeRecord = mock(TradeRecordService.class);
        SignalDeduplicationService mockDedup = mock(SignalDeduplicationService.class);
        mockWebhook = mock(DiscordWebhookService.class);
        UserApiKeyService mockApiKey = mock(UserApiKeyService.class);
        mockTradeConfigResolver = mock(TradeConfigResolver.class);

        // 預設 config：maxDailyLoss = 2000
        EffectiveTradeConfig defaultConfig = new EffectiveTradeConfig(
                0.20, 50000, 2000, 3, 2.0, 20,
                List.of("BTCUSDT"), true, "BTCUSDT"
        );
        when(mockTradeConfigResolver.resolve(any())).thenReturn(defaultConfig);

        service = spy(new BinanceFuturesService(
                null, new BinanceConfig("https://fake.test", null, "testkey", "testsecret"),
                riskConfig, mockTradeRecord, mockDedup, mockWebhook,
                new ObjectMapper(), new SymbolLockRegistry(), mockApiKey,
                mockTradeConfigResolver));

        when(mockDedup.isDuplicate(any())).thenReturn(false);
    }

    private void setupMocks(double balance, double todayLoss) {
        doReturn(balance).when(service).getAvailableBalance();
        doReturn(0.0).when(service).getCurrentPositionAmount(anyString());
        doReturn(0).when(service).getActivePositionCount();
        doReturn(false).when(service).hasOpenEntryOrders(anyString());
        doReturn(95000.0).when(service).getMarkPrice(anyString());
        doReturn("{}").when(service).setLeverage(anyString(), anyInt());
        try {
            doReturn("{}").when(service).setMarginType(anyString(), anyString());
        } catch (Exception e) { /* ignore */ }
        when(mockTradeRecord.getTodayRealizedLoss()).thenReturn(todayLoss);
    }

    private TradeSignal longEntry() {
        return TradeSignal.builder()
                .symbol("BTCUSDT")
                .side(TradeSignal.Side.LONG)
                .entryPriceLow(95000)
                .stopLoss(93000)
                .signalType(TradeSignal.SignalType.ENTRY)
                .build();
    }

    @Test
    @DisplayName("虧損恰好等於上限 → 拒絕 + 發通知")
    void rejectWhenLossExactlyAtLimit() {
        setupMocks(10000, -2000.0);  // |todayLoss| = 2000 = maxDailyLoss

        List<OrderResult> results = service.executeSignal(longEntry());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isSuccess()).isFalse();
        assertThat(results.get(0).getErrorMessage()).contains("虧損已達上限");
        verify(mockWebhook).sendNotification(contains("熔斷"), anyString(), anyInt());
    }

    @Test
    @DisplayName("虧損超過上限 → 拒絕")
    void rejectWhenLossExceedsLimit() {
        setupMocks(10000, -2500.0);

        List<OrderResult> results = service.executeSignal(longEntry());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isSuccess()).isFalse();
    }

    @Test
    @DisplayName("虧損未達上限 → 允許繼續（不被熔斷擋住）")
    void allowWhenLossUnderLimit() {
        setupMocks(10000, -1999.99);

        // 需要 mock 後續的下單操作
        OrderResult entryOrder = OrderResult.builder()
                .success(true).orderId("E1").symbol("BTCUSDT")
                .side("BUY").type("LIMIT").price(95000).quantity(0.01)
                .build();
        OrderResult slOrder = OrderResult.builder()
                .success(true).orderId("SL1").symbol("BTCUSDT")
                .side("SELL").type("STOP_MARKET").price(93000).quantity(0.01)
                .build();

        doReturn(entryOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
        doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());

        List<OrderResult> results = service.executeSignal(longEntry());

        // 不應該被熔斷擋住
        assertThat(results).isNotEmpty();
        assertThat(results.get(0).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("今日無虧損 → 允許")
    void allowWhenNoLossToday() {
        setupMocks(10000, 0.0);

        OrderResult entryOrder = OrderResult.builder()
                .success(true).orderId("E1").symbol("BTCUSDT")
                .side("BUY").type("LIMIT").price(95000).quantity(0.01)
                .build();
        OrderResult slOrder = OrderResult.builder()
                .success(true).orderId("SL1").symbol("BTCUSDT")
                .side("SELL").type("STOP_MARKET").price(93000).quantity(0.01)
                .build();

        doReturn(entryOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
        doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());

        List<OrderResult> results = service.executeSignal(longEntry());

        assertThat(results.get(0).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("maxDailyLoss=0 → 熔斷不啟用")
    void disabledWhenMaxDailyLossIsZero() {
        // 使用 maxDailyLoss=0 的 config
        EffectiveTradeConfig disabledConfig = new EffectiveTradeConfig(
                0.20, 50000, 0, 3, 2.0, 20,
                List.of("BTCUSDT"), true, "BTCUSDT"
        );
        when(mockTradeConfigResolver.resolve(any())).thenReturn(disabledConfig);

        setupMocks(10000, -99999.0);  // 巨大虧損但 maxDailyLoss=0 不啟用

        OrderResult entryOrder = OrderResult.builder()
                .success(true).orderId("E1").symbol("BTCUSDT")
                .side("BUY").type("LIMIT").price(95000).quantity(0.01)
                .build();
        OrderResult slOrder = OrderResult.builder()
                .success(true).orderId("SL1").symbol("BTCUSDT")
                .side("SELL").type("STOP_MARKET").price(93000).quantity(0.01)
                .build();

        doReturn(entryOrder).when(service).placeLimitOrder(anyString(), anyString(), anyDouble(), anyDouble());
        doReturn(slOrder).when(service).placeStopLoss(anyString(), anyString(), anyDouble(), anyDouble());

        List<OrderResult> results = service.executeSignal(longEntry());

        // 不應該被熔斷擋住（maxDailyLoss=0 表示關閉）
        assertThat(results.get(0).isSuccess()).isTrue();
    }

    @Test
    @DisplayName("熔斷是固定上限，不因餘額縮水而變鬆")
    void fixedLimitNotAffectedByBalanceShrinkage() {
        // 帳戶從 10000 虧到 7000，但上限仍是 2000
        setupMocks(7000, -2000.0);

        List<OrderResult> results = service.executeSignal(longEntry());

        assertThat(results).hasSize(1);
        assertThat(results.get(0).isSuccess()).isFalse();
        assertThat(results.get(0).getErrorMessage()).contains("虧損已達上限");
    }
}
