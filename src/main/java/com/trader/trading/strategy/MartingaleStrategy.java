package com.trader.trading.strategy;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.model.Order;
import com.trader.trading.risk.LayerPlan;
import com.trader.trading.risk.MarketFilter;
import com.trader.trading.risk.MarketRiskScorer;
import com.trader.trading.risk.RiskDecision;
import com.trader.trading.risk.RiskManager;
import com.trader.trading.risk.RiskScoreResult;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.model.MartingaleSession;
import com.trader.trading.model.PositionInfo;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.MarketIndicatorService;
import com.trader.trading.service.MartingaleSessionManager;
import com.trader.trading.service.LayerFillTracker;
import com.trader.trading.service.martingale.MartingaleStateStore;
import com.trader.trading.service.PositionService;
import com.trader.trading.service.PositionSizer;
import com.trader.trading.service.StartOfDayBalanceCache;
import com.trader.trading.service.SymbolLockRegistry;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Slf4j
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
    private final MarketRiskScorer marketRiskScorer;
    private final MartingaleStateStore stateStore;

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
            LayerFillTracker layerFillTracker,
            MarketRiskScorer marketRiskScorer,
            MartingaleStateStore stateStore
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
        this.marketRiskScorer = marketRiskScorer;
        this.stateStore = stateStore;
    }

    @Override
    public List<Order> execute(TradeSignal signal) {
        if (signal == null || signal.getSymbol() == null) {
            return List.of();
        }

        var lock = symbolLockRegistry.getLock(signal.getSymbol());
        lock.lock();
        try {
            var existingSession = sessionManager.getActiveSession(signal.getSymbol());
            if (existingSession.isPresent()) {
                return adjustExistingSession(existingSession.get(), signal);
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

        // 動態層數：有訊號 SL 時用 SL 距離 ÷ stepPercent，否則用 config
        int dynamicMaxLayers = computeDynamicMaxLayers(signal, baseEntryPrice, side);

        // 1) Build layer prices based on entry and side (LONG down, SHORT up).
        List<Double> layerPrices = buildLayerPrices(baseEntryPrice, side, signal.getSymbol(), dynamicMaxLayers);

        // 2) Gather runtime risk context.
        double accountBalance = binanceFuturesService.getAvailableBalance();
        String userId = tradeRecordService.getActiveUserId();
        double sodBalance = startOfDayBalanceCache.getOrCompute(userId, () -> accountBalance);
        double todayLoss = tradeRecordService.getTodayRealizedLoss();
        double unrealizedLoss = getUnrealizedLoss();
        double totalLoss = Math.abs(todayLoss) + Math.abs(unrealizedLoss);
        double drawdownPercent = (sodBalance > 0) ? totalLoss / sodBalance : 0.0;

        double currentPositionSize = position != null ? position.quantity() : 0.0;
        EffectiveTradeConfig effectiveConfig = tradeConfigResolver.resolve(userId);
        int leverage = Math.max(1, effectiveConfig.fixedLeverage());
        double effectiveMaxPositionUsdt = effectiveConfig.effectiveMaxPosition(accountBalance);

        if (position != null && position.isOpen()) {
            double markPrice = binanceFuturesService.getMarkPrice(signal.getSymbol());
            // SL 基準：有成交用加權均價，無成交用 baseEntryPrice
            var fill = layerFillTracker.getAggregatedFill(signal.getSymbol());
            double slBase = fill.avgPrice() > 0 ? fill.avgPrice() : baseEntryPrice;
            boolean stopLossTriggered = isGlobalStopLossTriggered(signal.getSymbol(), side, markPrice, slBase);
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
                config.getEffectiveSizeMultiplier(signal.getSymbol()),
                accountBalance,
                effectiveConfig.riskPercent(),
                leverage,
                effectiveMaxPositionUsdt,
                config.getMaxCapitalUsage()
        );

        if (layerPlans.isEmpty()) {
            return List.of();
        }

        // 3) Build market filter: multi-factor score (default) or legacy EMA.
        MarketFilter marketFilter = buildMarketFilter(signal.getSymbol(), side);

        RiskDecision decision = riskManager.evaluateMartingale(
                side,
                accountBalance,
                config.getMaxCapitalUsage(),
                dynamicMaxLayers,
                currentPositionSize,
                config.getMaxPositionSize(),
                leverage,
                drawdownPercent,
                MAX_DRAWDOWN_PERCENT,
                marketFilter,
                layerPlans
        );

        if (!decision.allowed()) {
            return List.of();
        }

        int allowedLayers = decision.allowedLayers();

        // 4) Generate actual orders only up to allowedLayers.
        //    - Compute weighted average entry price
        //    - Place a TAKE_PROFIT at averagePrice * (1 + TAKE_PROFIT_PERCENT)
        MartingaleSession newSession = sessionManager.startSession(signal.getSymbol(), side, allowedLayers, baseEntryPrice);
        applySignalTpSl(newSession, signal, side);
        stateStore.persistSession(newSession);

        var filled = layerFillTracker.getAggregatedFill(signal.getSymbol());
        double filledQty = filled.totalQty();
        double filledAvg = filled.avgPrice();

        return buildOrders(signal, side, layerPlans, allowedLayers, baseEntryPrice, filledQty, filledAvg, newSession);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 收到新訊號時，動態調整已有 session 的未成交掛單：
     * - filledLayers == 0：取消全部掛單 → 結束 session → 由外層建立新 session
     * - filledLayers > 0：取消未成交 ENTRY + 舊 TP → 以新價格重新掛出剩餘層 + 更新 TP
     * - filledLayers == plannedLayers：全部成交，無法調整
     */
    private List<Order> adjustExistingSession(MartingaleSession session, TradeSignal signal) {
        int filled = session.getFilledLayers();
        int planned = session.getPlannedLayers();
        String symbol = session.getSymbol();

        // 全部成交 → 不需要調整，等待 TP 觸發
        if (filled >= planned) {
            return List.of();
        }

        // 無任何成交 → 取消全部、結束 session，讓外層重新建立
        if (filled == 0) {
            try {
                binanceFuturesService.cancelAllOrders(symbol);
            } catch (Exception e) {
                log.warn("Adjust: 取消掛單失敗 symbol={} err={}", symbol, e.getMessage());
                return List.of();
            }
            layerFillTracker.clearSymbol(symbol);
            sessionManager.endSession(symbol);
            stateStore.removeSession(symbol);
            // 回傳空 → 外層 execute() 會因為 session 不存在而走正常建立流程
            // 但我們已在 lock 內，所以直接重新執行建立邏輯
            return executeNewSession(signal);
        }

        // 有成交 → 取消未成交 ENTRY + 舊 TP，以新訊號價格重新掛出剩餘層
        try {
            binanceFuturesService.cancelAllOrders(symbol);
        } catch (Exception e) {
            log.warn("Adjust: 取消掛單失敗 symbol={} err={}", symbol, e.getMessage());
            return List.of();
        }

        double newBasePrice = signal.getEntryPriceLow();
        if (newBasePrice <= 0) {
            return List.of();
        }

        TradeSignal.Side side = session.getSide();
        int remainingLayers = planned - filled;

        // 以新價格計算剩餘層的價格（從 Layer 1 開始，因為是新的佈局）
        List<Double> newPrices = new ArrayList<>(remainingLayers);
        double effectiveStep = resolveStepPercent(symbol);
        for (int i = 0; i < remainingLayers; i++) {
            double price = side == TradeSignal.Side.LONG
                    ? newBasePrice * Math.pow(1.0 - effectiveStep, i)
                    : newBasePrice * Math.pow(1.0 + effectiveStep, i);
            newPrices.add(price);
        }

        // 資金分配（為剩餘層）
        double accountBalance = binanceFuturesService.getAvailableBalance();
        String userId = tradeRecordService.getActiveUserId();
        EffectiveTradeConfig effectiveConfig = tradeConfigResolver.resolve(userId);
        int leverage = Math.max(1, effectiveConfig.fixedLeverage());
        double effectiveMaxPositionUsdt = effectiveConfig.effectiveMaxPosition(accountBalance);

        List<LayerPlan> layerPlans = positionSizer.sizeLayers(
                newPrices,
                config.getBaseSize(),
                config.getEffectiveSizeMultiplier(symbol),
                accountBalance,
                effectiveConfig.riskPercent(),
                leverage,
                effectiveMaxPositionUsdt,
                config.getMaxCapitalUsage()
        );

        if (layerPlans.isEmpty()) {
            return List.of();
        }

        int allowedLayers = Math.min(remainingLayers, layerPlans.size());

        // 建立 ENTRY 訂單（層號接續已成交的層）
        List<Order> orders = new ArrayList<>(allowedLayers + 1);
        for (int i = 0; i < allowedLayers; i++) {
            LayerPlan plan = layerPlans.get(i);
            orders.add(Order.builder()
                    .symbol(symbol)
                    .side(side)
                    .type(Order.OrderType.ENTRY)
                    .price(plan.price())
                    .quantity(plan.quantity())
                    .layer(filled + i + 1) // 接續已成交層號
                    .build());
        }

        // TP 基於現有成交數據
        var fill = layerFillTracker.getAggregatedFill(symbol);
        double tpQty = fill.totalQty();
        double tpAvgPrice = fill.avgPrice();
        if (tpQty <= 0 || tpAvgPrice <= 0) {
            return List.of();
        }

        // 訊號提供絕對 TP → 直接使用；否則用 config 百分比
        double tpPrice;
        if (session.getSignalTakeProfit() != null && session.getSignalTakeProfit() > 0) {
            tpPrice = session.getSignalTakeProfit();
        } else {
            tpPrice = side == TradeSignal.Side.LONG
                    ? tpAvgPrice * (1.0 + config.getEffectiveTakeProfitPercent(symbol))
                    : tpAvgPrice * (1.0 - config.getEffectiveTakeProfitPercent(symbol));
        }

        orders.add(Order.builder()
                .symbol(symbol)
                .side(side)
                .type(Order.OrderType.TAKE_PROFIT)
                .price(tpPrice)
                .quantity(tpQty)
                .layer(null)
                .build());

        log.info("Martingale session adjusted: symbol={} filled={} newEntries={} newBasePrice={}",
                symbol, filled, allowedLayers, newBasePrice);

        return orders;
    }

    /**
     * 正常建立新 session 的邏輯（從 execute() 抽取，供 adjustExistingSession 重用）。
     */
    private List<Order> executeNewSession(TradeSignal signal) {
        PositionInfo position = positionService.getPosition(signal.getSymbol()).orElse(null);
        TradeSignal.Side side = position != null ? position.side() : signal.getSide();
        if (side == null) return List.of();

        double baseEntryPrice = position != null && position.avgEntryPrice() > 0
                ? position.avgEntryPrice()
                : signal.getEntryPriceLow();
        if (baseEntryPrice <= 0) return List.of();

        int dynamicMaxLayers = computeDynamicMaxLayers(signal, baseEntryPrice, side);
        List<Double> layerPrices = buildLayerPrices(baseEntryPrice, side, signal.getSymbol(), dynamicMaxLayers);

        double accountBalance = binanceFuturesService.getAvailableBalance();
        String userId = tradeRecordService.getActiveUserId();
        double sodBalance = startOfDayBalanceCache.getOrCompute(userId, () -> accountBalance);
        double todayLoss = tradeRecordService.getTodayRealizedLoss();
        double unrealizedLoss = getUnrealizedLoss();
        double totalLoss = Math.abs(todayLoss) + Math.abs(unrealizedLoss);
        double drawdownPercent = (sodBalance > 0) ? totalLoss / sodBalance : 0.0;

        double currentPositionSize = position != null ? position.quantity() : 0.0;
        EffectiveTradeConfig effectiveConfig = tradeConfigResolver.resolve(userId);
        int leverage = Math.max(1, effectiveConfig.fixedLeverage());
        double effectiveMaxPositionUsdt = effectiveConfig.effectiveMaxPosition(accountBalance);

        List<LayerPlan> layerPlans = positionSizer.sizeLayers(
                layerPrices, config.getBaseSize(), config.getEffectiveSizeMultiplier(signal.getSymbol()),
                accountBalance, effectiveConfig.riskPercent(), leverage,
                effectiveMaxPositionUsdt, config.getMaxCapitalUsage()
        );
        if (layerPlans.isEmpty()) return List.of();

        MarketFilter marketFilter = buildMarketFilter(signal.getSymbol(), side);
        RiskDecision decision = riskManager.evaluateMartingale(
                side, accountBalance, config.getMaxCapitalUsage(), dynamicMaxLayers,
                currentPositionSize, config.getMaxPositionSize(), leverage,
                drawdownPercent, MAX_DRAWDOWN_PERCENT, marketFilter, layerPlans
        );
        if (!decision.allowed()) return List.of();

        int allowedLayers = decision.allowedLayers();
        MartingaleSession newSession = sessionManager.startSession(signal.getSymbol(), side, allowedLayers, baseEntryPrice);
        applySignalTpSl(newSession, signal, side);
        stateStore.persistSession(newSession);

        var filled = layerFillTracker.getAggregatedFill(signal.getSymbol());
        return buildOrders(signal, side, layerPlans, allowedLayers, baseEntryPrice, filled.totalQty(), filled.avgPrice(), newSession);
    }

    private List<Double> buildLayerPrices(double baseEntryPrice, TradeSignal.Side side, String symbol, int maxLayers) {
        double effectiveStep = resolveStepPercent(symbol);
        List<Double> prices = new ArrayList<>(maxLayers);
        for (int layer = 1; layer <= maxLayers; layer++) {
            double price = side == TradeSignal.Side.LONG
                    ? baseEntryPrice * Math.pow(1.0 - effectiveStep, layer - 1)
                    : baseEntryPrice * Math.pow(1.0 + effectiveStep, layer - 1);
            prices.add(price);
        }
        return prices;
    }

    /**
     * ATR 自適應層距：當 atrPeriod > 0 時，根據當前 ATR 動態調整 stepPercent。
     * effectiveStep = baseStep × (currentATR% / referenceATR%)
     * 限制範圍在 [baseStep × 0.5, baseStep × 3.0] 內，防止極端值。
     */
    private double resolveStepPercent(String symbol) {
        double baseStep = config.getEffectiveStepPercent(symbol);
        if (config.getAtrPeriod() <= 0 || config.getAtrReferencePercent() <= 0) {
            return baseStep;
        }

        double atrPercent = marketIndicatorService.getATRPercent(symbol, config.getAtrPeriod());
        if (Double.isNaN(atrPercent) || atrPercent <= 0) {
            return baseStep;
        }

        double ratio = atrPercent / config.getAtrReferencePercent();
        ratio = Math.max(0.5, Math.min(3.0, ratio)); // clamp to [0.5x, 3x]
        return baseStep * ratio;
    }

    private List<Order> buildOrders(TradeSignal signal, TradeSignal.Side side, List<LayerPlan> layers, int allowedLayers, double baseEntryPrice, double filledQty, double filledAvg, MartingaleSession session) {
        String symbol = signal != null ? signal.getSymbol() : null;
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
                    .symbol(symbol)
                    .side(side)
                    .type(Order.OrderType.ENTRY)
                    .price(plan.price())
                    .quantity(plan.quantity())
                    .layer(plan.layer())
                    .build());
        }

        // 初始 TP 只用 Layer 1（或已有成交）的數據計算。
        // Layer 1 LIMIT 掛在當前市價附近，幾乎立即成交；若用全部層的 totalQty，
        // 在動態 TP 更新前的窗口期 TP 數量會大於實際持倉，造成風險。
        // 後續層成交後由 MartingaleTpManager.updateTakeProfit() 動態修正。
        double tpQty;
        double tpAvgPrice;
        if (filledQty > 0.0 && filledAvg > 0.0) {
            // 已有成交（Recovery 等場景）→ 用實際成交數據
            tpQty = filledQty;
            tpAvgPrice = filledAvg;
        } else if (!layers.isEmpty()) {
            // 新 session → 只用 Layer 1
            LayerPlan firstLayer = layers.get(0);
            tpQty = firstLayer.quantity();
            tpAvgPrice = firstLayer.price();
        } else {
            tpQty = totalQuantity;
            tpAvgPrice = totalQuantity > 0.0 ? (weightedNotional / totalQuantity) : baseEntryPrice;
        }

        // 防禦：TP 數量或均價無效時不送 TP 單
        if (tpQty <= 0 || tpAvgPrice <= 0) {
            log.warn("Martingale TP 數量或均價無效，跳過 TP: symbol={} tpQty={} tpAvgPrice={}", symbol, tpQty, tpAvgPrice);
            return orders;
        }

        // 訊號提供絕對 TP → 直接使用；否則用 config 百分比計算
        double takeProfitPrice;
        if (session != null && session.getSignalTakeProfit() != null && session.getSignalTakeProfit() > 0) {
            takeProfitPrice = session.getSignalTakeProfit();
        } else {
            takeProfitPrice = side == TradeSignal.Side.LONG
                    ? tpAvgPrice * (1.0 + config.getEffectiveTakeProfitPercent(symbol))
                    : tpAvgPrice * (1.0 - config.getEffectiveTakeProfitPercent(symbol));
        }

        // 防禦：TP 價格無效（misconfigured percent ≥ 100% 導致負數）
        if (takeProfitPrice <= 0) {
            log.error("Martingale TP 價格無效（≤0），跳過 TP: symbol={} tpPrice={}", symbol, takeProfitPrice);
            return orders;
        }

        orders.add(Order.builder()
                .symbol(symbol)
                .side(side)
                .type(Order.OrderType.TAKE_PROFIT)
                .price(takeProfitPrice)
                .quantity(tpQty)
                .layer(null)
                .build());

        return orders;
    }

    /**
     * 根據配置選擇市場過濾模式：
     * 1. riskScoreThreshold > 0 且 !emaFilterEnabled → 多因子評分（推薦）
     * 2. emaFilterEnabled → 舊版 EMA 趨勢過濾
     * 3. 兩者都關閉 → 直接放行（不建議用於實盤）
     */
    private MarketFilter buildMarketFilter(String symbol, TradeSignal.Side side) {
        if (config.getRiskScoreThreshold() > 0) {
            RiskScoreResult scoreResult = marketRiskScorer.evaluate(symbol, side, config);
            return MarketFilter.riskScore(scoreResult, config.getRiskScoreThreshold());
        }

        if (config.isEmaFilterEnabled()) {
            double ema50 = marketIndicatorService.getEMA(symbol, 50);
            double ema200 = marketIndicatorService.getEMA(symbol, 200);
            if (Double.isNaN(ema50) || Double.isNaN(ema200)) {
                // EMA 無法取得 → 拒絕入場（安全起見）
                return s -> RiskDecision.reject("ema-data-unavailable");
            }
            return MarketFilter.ema(ema50, ema200);
        }

        return MarketFilter.passThrough();
    }

    private boolean isGlobalStopLossTriggered(String symbol, TradeSignal.Side side, double markPrice, double baseEntryPrice) {
        // 訊號提供絕對 SL → 直接比較
        var sessionOpt = sessionManager.getActiveSession(symbol);
        if (sessionOpt.isPresent()) {
            Double signalSl = sessionOpt.get().getSignalStopLoss();
            if (signalSl != null && signalSl > 0) {
                return side == TradeSignal.Side.LONG
                        ? markPrice <= signalSl
                        : markPrice >= signalSl;
            }
        }
        // fallback: config 百分比
        double sl = config.getEffectiveStopLossPercent(symbol);
        if (side == TradeSignal.Side.LONG) {
            return markPrice <= baseEntryPrice * (1.0 - sl);
        }
        return markPrice >= baseEntryPrice * (1.0 + sl);
    }

    /**
     * 動態層數：訊號有 SL 時 = SL 距離 ÷ stepPercent，否則用 config.maxLayers
     */
    private int computeDynamicMaxLayers(TradeSignal signal, double baseEntryPrice, TradeSignal.Side side) {
        if (signal.getStopLoss() > 0 && baseEntryPrice > 0) {
            double slDistance = Math.abs(baseEntryPrice - signal.getStopLoss()) / baseEntryPrice;
            double step = resolveStepPercent(signal.getSymbol());
            if (step > 0 && slDistance > 0) {
                int dynamic = (int) Math.floor(slDistance / step);
                int maxAllowed = config.getEffectiveMaxLayers(signal.getSymbol());
                return Math.max(1, Math.min(dynamic, maxAllowed));
            }
        }
        return config.getEffectiveMaxLayers(signal.getSymbol());
    }

    /**
     * 將訊號的絕對 TP/SL 存入 session，供後續 TpManager / StopLossWatcher 使用
     */
    private void applySignalTpSl(MartingaleSession session, TradeSignal signal, TradeSignal.Side side) {
        if (signal.getStopLoss() > 0) {
            session.setSignalStopLoss(signal.getStopLoss());
        }
        if (signal.getTakeProfits() != null && !signal.getTakeProfits().isEmpty()) {
            session.setSignalTakeProfit(signal.getTakeProfits().get(0));
        }
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
