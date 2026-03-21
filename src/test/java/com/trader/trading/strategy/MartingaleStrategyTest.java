package com.trader.trading.strategy;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.model.Order;
import com.trader.trading.risk.RiskManager;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.MarketIndicatorService;
import com.trader.trading.service.MartingaleSessionManager;
import com.trader.trading.service.PositionService;
import com.trader.trading.service.PositionSizer;
import com.trader.trading.service.StartOfDayBalanceCache;
import com.trader.trading.service.SymbolLockRegistry;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import com.trader.trading.service.LayerFillTracker;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.MockMakers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;
import static org.mockito.Mockito.when;

class MartingaleStrategyTest {

    private static final double ENTRY_PRICE = 60000.0;
    private static final double PRICE_STEP_PERCENT = 0.02;
    private static final int MAX_LAYERS = 5;
    private static final double SIZE_MULTIPLIER = 2.0;
    private static final double BASE_SIZE = 100.0;
    private static final double TAKE_PROFIT_PERCENT = 0.01;
    private static final double MAX_CAPITAL_USAGE = 0.30;
    private static final double MAX_POSITION_USDT = 10_000.0;
    private static final double RISK_PERCENT = 0.2;
    private static final int LEVERAGE = 1;

    @Test
    void testLayerGeneration() {
        MartingaleStrategy strategy = buildStrategy(new RiskManager(), 600_000_000.0, 0.0);

        List<Order> orders = strategy.execute(sampleSignal());

        // 5 entry layers + 1 take profit
        assertEquals(6, orders.size());

        List<Double> prices = layerPrices();
        List<Double> expectedQuantities = expectedQuantities(prices, 600_000_000.0);

        for (int layer = 1; layer <= MAX_LAYERS; layer++) {
            Order order = orders.get(layer - 1);
            assertEquals(Order.OrderType.ENTRY, order.getType());
            assertEquals(layer, order.getLayer());

            double expectedPrice = priceAtLayer(layer);
            double expectedQty = expectedQuantities.get(layer - 1);

            assertEquals(expectedPrice, order.getPrice(), 0.0001);
            assertEquals(expectedQty, order.getQuantity(), 0.0001);
        }
    }

    @Test
    void testInitialTpUsesLayer1Only() {
        MartingaleStrategy strategy = buildStrategy(new RiskManager(), 600_000_000.0, 0.0);

        List<Order> orders = strategy.execute(sampleSignal());

        // 初始 TP 應只用 Layer 1 的 price 和 quantity（避免數量不匹配風險）
        // 後續層成交後由 MartingaleTpManager 動態更新
        Order tp = orders.get(orders.size() - 1);
        double actualAverage = tp.getPrice() / (1.0 + TAKE_PROFIT_PERCENT);

        // Layer 1 price = ENTRY_PRICE (no step applied)
        assertEquals(ENTRY_PRICE, actualAverage, 0.0001);
    }

    @Test
    void testTakeProfitCalculation() {
        MartingaleStrategy strategy = buildStrategy(new RiskManager(), 600_000_000.0, 0.0);

        List<Order> orders = strategy.execute(sampleSignal());

        Order tp = orders.get(orders.size() - 1);
        assertEquals(Order.OrderType.TAKE_PROFIT, tp.getType());

        // 初始 TP 只用 Layer 1
        Order layer1 = orders.get(0);
        double expectedTp = layer1.getPrice() * (1.0 + TAKE_PROFIT_PERCENT);

        assertEquals(expectedTp, tp.getPrice(), 0.0001);
        assertEquals(layer1.getQuantity(), tp.getQuantity(), 0.0001);
    }

    @Test
    void testMaxLayerLimit() {
        MartingaleStrategy strategy = buildStrategy(new RiskManager(), 600_000_000.0, 0.0);

        List<Order> orders = strategy.execute(sampleSignal());

        long entryCount = orders.stream().filter(o -> o.getType() == Order.OrderType.ENTRY).count();
        assertEquals(MAX_LAYERS, entryCount);
    }

    @Test
    void testCapitalLimit() {
        MartingaleStrategy strategy = buildStrategy(new RiskManager(), 30_000_000.0, 0.0);

        List<Order> orders = strategy.execute(sampleSignal());

        double maxNotional = expectedMaxNotional(30_000_000.0);
        double totalNotional = orders.stream()
                .filter(o -> o.getType() == Order.OrderType.ENTRY)
                .mapToDouble(o -> o.getPrice() * o.getQuantity())
                .sum();
        assertEquals(maxNotional, totalNotional, 0.01);

        // Take-profit should still exist
        Order tp = orders.get(orders.size() - 1);
        assertEquals(Order.OrderType.TAKE_PROFIT, tp.getType());
        assertNotNull(tp.getPrice());
    }

    private MartingaleStrategy buildStrategy(RiskManager riskManager, double balance, double todayLoss) {
        BinanceFuturesService binanceFuturesService = mock(BinanceFuturesService.class,
                withSettings().mockMaker(MockMakers.SUBCLASS));
        TradeRecordService tradeRecordService = mock(TradeRecordService.class,
                withSettings().mockMaker(MockMakers.SUBCLASS));
        StartOfDayBalanceCache startOfDayBalanceCache = mock(StartOfDayBalanceCache.class,
                withSettings().mockMaker(MockMakers.SUBCLASS));
        MarketIndicatorService marketIndicatorService = mock(MarketIndicatorService.class,
                withSettings().mockMaker(MockMakers.SUBCLASS));
        TradeConfigResolver tradeConfigResolver = mock(TradeConfigResolver.class,
                withSettings().mockMaker(MockMakers.SUBCLASS));
        PositionService positionService = mock(PositionService.class,
                withSettings().mockMaker(MockMakers.SUBCLASS));
        PositionSizer positionSizer = new PositionSizer();
        MartingaleSessionManager sessionManager = new MartingaleSessionManager();
        SymbolLockRegistry symbolLockRegistry = new SymbolLockRegistry();
        LayerFillTracker layerFillTracker = new LayerFillTracker();

        when(binanceFuturesService.getAvailableBalance()).thenReturn(balance);
        when(binanceFuturesService.getCurrentPositionAmount(ArgumentMatchers.anyString())).thenReturn(0.0);
        when(tradeRecordService.getActiveUserId()).thenReturn("test-user");
        when(tradeRecordService.getTodayRealizedLoss()).thenReturn(todayLoss);
        when(startOfDayBalanceCache.getOrCompute(ArgumentMatchers.anyString(), ArgumentMatchers.any()))
                .thenReturn(balance);
        when(marketIndicatorService.getEMA(ArgumentMatchers.anyString(), ArgumentMatchers.eq(50))).thenReturn(ENTRY_PRICE + 1);
        when(marketIndicatorService.getEMA(ArgumentMatchers.anyString(), ArgumentMatchers.eq(200))).thenReturn(ENTRY_PRICE);
        when(tradeConfigResolver.resolve(ArgumentMatchers.anyString())).thenReturn(new EffectiveTradeConfig(
                RISK_PERCENT, MAX_POSITION_USDT, 0, 0, 0, 3, 2.0, LEVERAGE, List.of("BTCUSDT"), true, "BTCUSDT"
        ));
        when(positionService.getPosition(ArgumentMatchers.anyString()))
                .thenReturn(java.util.Optional.empty());

        MartingaleStrategyConfig config = new MartingaleStrategyConfig(
                MAX_LAYERS,
                PRICE_STEP_PERCENT,
                BASE_SIZE,
                SIZE_MULTIPLIER,
                TAKE_PROFIT_PERCENT,
                MAX_CAPITAL_USAGE,
                MAX_POSITION_USDT,
                0.15,
                30,
                60000,
                5000,
                3
        );

        return new MartingaleStrategy(
                riskManager,
                config,
                binanceFuturesService,
                tradeRecordService,
                startOfDayBalanceCache,
                marketIndicatorService,
                tradeConfigResolver,
                positionService,
                positionSizer,
                sessionManager,
                symbolLockRegistry,
                layerFillTracker
        );
    }

    private TradeSignal sampleSignal() {
        return TradeSignal.builder()
                .symbol("BTCUSDT")
                .side(TradeSignal.Side.LONG)
                .entryPriceLow(ENTRY_PRICE)
                .entryPriceHigh(ENTRY_PRICE)
                .signalType(TradeSignal.SignalType.ENTRY)
                .build();
    }

    private double priceAtLayer(int layer) {
        return ENTRY_PRICE * Math.pow(1.0 - PRICE_STEP_PERCENT, layer - 1);
    }

    private double qtyAtLayer(int layer) {
        return BASE_SIZE * Math.pow(SIZE_MULTIPLIER, layer - 1);
    }

    private double expectedAveragePrice(int layers, double balance) {
        double totalQty = 0.0;
        double totalNotional = 0.0;
        List<Double> prices = layerPrices();
        List<Double> quantities = expectedQuantities(prices, balance);
        for (int layer = 1; layer <= layers; layer++) {
            double price = priceAtLayer(layer);
            double qty = quantities.get(layer - 1);
            totalQty += qty;
            totalNotional += price * qty;
        }
        return totalNotional / totalQty;
    }

    private double expectedTotalQuantity(int layers, double balance) {
        double totalQty = 0.0;
        List<Double> quantities = expectedQuantities(layerPrices(), balance);
        for (int layer = 1; layer <= layers; layer++) {
            totalQty += quantities.get(layer - 1);
        }
        return totalQty;
    }

    private List<Double> layerPrices() {
        return List.of(
                priceAtLayer(1),
                priceAtLayer(2),
                priceAtLayer(3),
                priceAtLayer(4),
                priceAtLayer(5)
        );
    }

    private List<Double> expectedQuantities(List<Double> prices, double balance) {
        double maxNotional = expectedMaxNotional(balance);
        double totalWeight = 0.0;
        double[] weights = new double[MAX_LAYERS];
        for (int i = 0; i < MAX_LAYERS; i++) {
            weights[i] = BASE_SIZE * Math.pow(SIZE_MULTIPLIER, i);
            totalWeight += weights[i];
        }
        List<Double> quantities = new java.util.ArrayList<>();
        for (int i = 0; i < MAX_LAYERS; i++) {
            double notional = maxNotional * (weights[i] / totalWeight);
            quantities.add(notional / prices.get(i));
        }
        return quantities;
    }

    private double expectedMaxNotional(double balance) {
        double riskAmount = balance * RISK_PERCENT;
        double maxNotionalByRisk = riskAmount * LEVERAGE;
        double maxNotionalByCapitalUsage = balance * MAX_CAPITAL_USAGE * LEVERAGE;
        double maxNotional = Math.min(maxNotionalByRisk, maxNotionalByCapitalUsage);
        if (MAX_POSITION_USDT > 0) {
            maxNotional = Math.min(maxNotional, MAX_POSITION_USDT);
        }
        return maxNotional;
    }
}
