package com.trader.trading.strategy;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.model.Order;
import com.trader.trading.risk.LayerPlan;
import com.trader.trading.risk.RiskDecision;
import com.trader.trading.risk.RiskManager;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.model.PositionInfo;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.MarketIndicatorService;
import com.trader.trading.service.MartingaleSessionManager;
import com.trader.trading.service.LayerFillTracker;
import com.trader.trading.service.PositionService;
import com.trader.trading.service.PositionSizer;
import com.trader.trading.service.StartOfDayBalanceCache;
import com.trader.trading.service.SymbolLockRegistry;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MartingaleStrategy implements TradingStrategy {

    // === Strategy parameters (hardcoded for now as per requirements) ===
    private static final double MAX_DRAWDOWN_PERCENT = 0.20; // 20% drawdown

    private final RiskManager riskManager;
    private final MartingaleStrategyConfig config;
    private final BinanceFuturesService binanceFuturesService;
    private final TradeRecordService tradeRecordService;
    private final StartOfDayBalanceCache startOfDayBalanceCache;
    private final MarketIndicatorService marketIndicatorService;
    private final TradeConfigResolver tradeConfigResolver;
    private final PositionService positionService;
    private final PositionSizer positionSizer;
    private final MartingaleSessionManager sessionManager;
    private final SymbolLockRegistry symbolLockRegistry;
    private final LayerFillTracker layerFillTracker;

    public MartingaleStrategy(
            RiskManager riskManager,
            MartingaleStrategyConfig config,
            BinanceFuturesService binanceFuturesService,
            TradeRecordService tradeRecordService,
            StartOfDayBalanceCache startOfDayBalanceCache,
            MarketIndicatorService marketIndicatorService,
            TradeConfigResolver tradeConfigResolver,
            PositionService positionService,
            PositionSizer positionSizer,
            MartingaleSessionManager sessionManager,
            SymbolLockRegistry symbolLockRegistry,
            LayerFillTracker layerFillTracker
    ) {
        this.riskManager = riskManager;
        this.config = config;
        this.binanceFuturesService = binanceFuturesService;
        this.tradeRecordService = tradeRecordService;
        this.startOfDayBalanceCache = startOfDayBalanceCache;
        this.marketIndicatorService = marketIndicatorService;
        this.tradeConfigResolver = tradeConfigResolver;
        this.positionService = positionService;
        this.positionSizer = positionSizer;
        this.sessionManager = sessionManager;
        this.symbolLockRegistry = symbolLockRegistry;
        this.layerFillTracker = layerFillTracker;
    }

    @Override
    public List<Order> execute(TradeSignal signal) {
        if (signal == null || signal.getSymbol() == null) {
            return List.of();
        }

        var lock = symbolLockRegistry.getLock(signal.getSymbol());
        lock.lock();
        try {
            if (sessionManager.getActiveSession(signal.getSymbol()).isPresent()) {
                return List.of();
            }
            if (sessionManager.getActiveSessionCount() >= config.getMaxConcurrentSessions()) {
                return List.of();
            }

        PositionInfo position = positionService.getPosition(signal.getSymbol()).orElse(null);
        TradeSignal.Side side = position != null ? position.side() : signal.getSide();
        if (side == null) {
            return List.of();
        }

        double baseEntryPrice = position != null && position.avgEntryPrice() > 0
                ? position.avgEntryPrice()
                : signal.getEntryPriceLow();
        if (baseEntryPrice <= 0) {
            return List.of();
        }

        // 1) Build layer prices based on entry and side (LONG down, SHORT up).
        List<Double> layerPrices = buildLayerPrices(baseEntryPrice, side);

        // 2) Gather runtime risk context.
        double accountBalance = binanceFuturesService.getAvailableBalance();
        String userId = tradeRecordService.getActiveUserId();
        double sodBalance = startOfDayBalanceCache.getOrCompute(userId, () -> accountBalance);
        double todayLoss = tradeRecordService.getTodayRealizedLoss();
        double unrealizedLoss = getUnrealizedLoss();
        double totalLoss = Math.abs(todayLoss) + Math.abs(unrealizedLoss);
        double drawdownPercent = (sodBalance > 0) ? totalLoss / sodBalance : 0.0;

        double ema50 = marketIndicatorService.getEMA(signal.getSymbol(), 50);
        double ema200 = marketIndicatorService.getEMA(signal.getSymbol(), 200);
        if (Double.isNaN(ema50) || Double.isNaN(ema200)) {
            return List.of();
        }

        double currentPositionSize = position != null ? position.quantity() : 0.0;
        EffectiveTradeConfig effectiveConfig = tradeConfigResolver.resolve(userId);
        int leverage = Math.max(1, effectiveConfig.fixedLeverage());
        double effectiveMaxPositionUsdt = effectiveConfig.effectiveMaxPosition(accountBalance);

        if (position != null && position.isOpen()) {
            double markPrice = binanceFuturesService.getMarkPrice(signal.getSymbol());
            boolean stopLossTriggered = isGlobalStopLossTriggered(side, markPrice, baseEntryPrice);
            if (stopLossTriggered) {
                return List.of(Order.builder()
                        .symbol(signal.getSymbol())
                        .side(side)
                        .type(Order.OrderType.CLOSE)
                        .price(null)
                        .quantity(currentPositionSize)
                        .layer(null)
                        .build());
            }
        }

        List<LayerPlan> layerPlans = positionSizer.sizeLayers(
                layerPrices,
                config.getBaseSize(),
                config.getSizeMultiplier(),
                accountBalance,
                effectiveConfig.riskPercent(),
                leverage,
                effectiveMaxPositionUsdt,
                config.getMaxCapitalUsage()
        );

        if (layerPlans.isEmpty()) {
            return List.of();
        }

        // 3) Apply risk controls and decide how many layers are allowed.
        RiskDecision decision = riskManager.evaluateMartingale(
                side,
                accountBalance,
                config.getMaxCapitalUsage(),
                config.getMaxLayers(),
                currentPositionSize,
                config.getMaxPositionSize(),
                leverage,
                drawdownPercent,
                MAX_DRAWDOWN_PERCENT,
                ema50,
                ema200,
                layerPlans
        );

        if (!decision.allowed()) {
            return List.of();
        }

        int allowedLayers = decision.allowedLayers();

        // 4) Generate actual orders only up to allowedLayers.
        //    - Compute weighted average entry price
        //    - Place a TAKE_PROFIT at averagePrice * (1 + TAKE_PROFIT_PERCENT)
        sessionManager.startSession(signal.getSymbol(), side, allowedLayers, baseEntryPrice);

        var filled = layerFillTracker.getAggregatedFill(signal.getSymbol());
        double filledQty = filled.totalQty();
        double filledAvg = filled.avgPrice();

        return buildOrders(signal, side, layerPlans, allowedLayers, baseEntryPrice, filledQty, filledAvg);
        } finally {
            lock.unlock();
        }
    }

    private List<Double> buildLayerPrices(double baseEntryPrice, TradeSignal.Side side) {
        List<Double> prices = new ArrayList<>(config.getMaxLayers());
        for (int layer = 1; layer <= config.getMaxLayers(); layer++) {
            double stepFactor = Math.pow(1.0 + config.getStepPercent(), layer - 1);
            double price = side == TradeSignal.Side.LONG
                    ? baseEntryPrice * Math.pow(1.0 - config.getStepPercent(), layer - 1)
                    : baseEntryPrice * stepFactor;
            prices.add(price);
        }
        return prices;
    }

    private List<Order> buildOrders(TradeSignal signal, TradeSignal.Side side, List<LayerPlan> layers, int allowedLayers, double baseEntryPrice, double filledQty, double filledAvg) {
        List<Order> orders = new ArrayList<>(allowedLayers + 1);

        double totalQuantity = 0.0;
        double weightedNotional = 0.0;

        for (int i = 0; i < allowedLayers; i++) {
            LayerPlan plan = layers.get(i);

            // Accumulate weighted average components:
            // average = sum(price_i * qty_i) / sum(qty_i)
            totalQuantity += plan.quantity();
            weightedNotional += plan.price() * plan.quantity();

            orders.add(Order.builder()
                    .symbol(signal != null ? signal.getSymbol() : null)
                    .side(side)
                    .type(Order.OrderType.ENTRY)
                    .price(plan.price())
                    .quantity(plan.quantity())
                    .layer(plan.layer())
                    .build());
        }

        // Compute weighted average entry price.
        double plannedAvg = totalQuantity > 0.0 ? (weightedNotional / totalQuantity) : baseEntryPrice;
        double averagePrice;
        if (filledQty > 0.0 && filledAvg > 0.0) {
            averagePrice = filledAvg;
            totalQuantity = filledQty;
        } else {
            averagePrice = plannedAvg;
        }

        // Set take-profit above the weighted average price.
        double takeProfitPrice = side == TradeSignal.Side.LONG
                ? averagePrice * (1.0 + config.getTakeProfitPercent())
                : averagePrice * (1.0 - config.getTakeProfitPercent());

        orders.add(Order.builder()
                .symbol(signal != null ? signal.getSymbol() : null)
                .side(side)
                .type(Order.OrderType.TAKE_PROFIT)
                .price(takeProfitPrice)
                .quantity(totalQuantity)
                .layer(null)
                .build());

        return orders;
    }

    private boolean isGlobalStopLossTriggered(TradeSignal.Side side, double markPrice, double baseEntryPrice) {
        if (side == TradeSignal.Side.LONG) {
            return markPrice <= baseEntryPrice * (1.0 - config.getStopLossPercent());
        }
        return markPrice >= baseEntryPrice * (1.0 + config.getStopLossPercent());
    }

    /**
     * 取得所有持倉的未實現虧損總和（只計入虧損部分，獲利不計）。
     */
    private double getUnrealizedLoss() {
        try {
            double totalLoss = 0.0;
            for (var session : sessionManager.getSessionsSnapshot()) {
                var posOpt = positionService.getPosition(session.getSymbol());
                if (posOpt.isPresent() && posOpt.get().unrealizedPnl() < 0) {
                    totalLoss += posOpt.get().unrealizedPnl();
                }
            }
            return totalLoss;
        } catch (Exception e) {
            return 0.0;
        }
    }
}
