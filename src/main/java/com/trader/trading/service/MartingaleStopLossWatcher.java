package com.trader.trading.service;

import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.model.MartingaleSession;
import com.trader.trading.model.PositionInfo;
import com.trader.trading.service.martingale.MartingaleNotifier;
import com.trader.trading.service.martingale.MartingaleStateStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class MartingaleStopLossWatcher {

    private final MartingaleSessionManager sessionManager;
    private final BinanceFuturesService binanceFuturesService;
    private final PositionService positionService;
    private final LayerFillTracker layerFillTracker;
    private final SymbolLockRegistry symbolLockRegistry;
    private final MartingaleStrategyConfig config;
    private final MartingaleNotifier notifier;
    private final MartingaleStateStore stateStore;

    public MartingaleStopLossWatcher(MartingaleSessionManager sessionManager,
                                     BinanceFuturesService binanceFuturesService,
                                     PositionService positionService,
                                     LayerFillTracker layerFillTracker,
                                     SymbolLockRegistry symbolLockRegistry,
                                     MartingaleStrategyConfig config,
                                     MartingaleNotifier notifier,
                                     MartingaleStateStore stateStore) {
        this.sessionManager = sessionManager;
        this.binanceFuturesService = binanceFuturesService;
        this.positionService = positionService;
        this.layerFillTracker = layerFillTracker;
        this.symbolLockRegistry = symbolLockRegistry;
        this.config = config;
        this.notifier = notifier;
        this.stateStore = stateStore;
    }

    @Scheduled(fixedDelayString = "${trading.strategy.martingale.stop-loss-check-interval-millis:5000}")
    public void checkStopLoss() {
        for (MartingaleSession session : sessionManager.getSessionsSnapshot()) {
            if (session.getStatus() != MartingaleSession.Status.ACTIVE) {
                continue;
            }

            String symbol = session.getSymbol();
            if (symbol == null || symbol.isBlank()) {
                continue;
            }

            try {
                double markPrice = binanceFuturesService.getMarkPrice(symbol);
                if (markPrice <= 0) {
                    continue;
                }

                // 有成交時用加權均價做 SL 基準，確保不同成交深度的風險比一致
                double slBasePrice = resolveSlBasePrice(session);
                if (slBasePrice <= 0) {
                    continue;
                }

                // 1) SL 檢查
                if (isStopLossTriggered(symbol, session.getSide(), markPrice, slBasePrice)) {
                    executeStopLoss(session, markPrice, slBasePrice);
                    continue;
                }

                // 2) 階梯式 Trailing Stop（只在有成交且 breakevenTriggerPercent > 0 時啟用）
                if (session.getFilledLayers() > 0 && config.getBreakevenTriggerPercent() > 0) {
                    checkTrailingStop(session, markPrice);
                }

                // 3) TP 時間衰減（只在有成交、Trailing 未啟動、衰減已開啟時）
                if (session.getFilledLayers() > 0
                        && session.getTrailingLevel() == 0
                        && config.getTpDecayStartMinutes() > 0) {
                    checkTpDecay(session);
                }
            } catch (Exception e) {
                log.error("Stop loss check failed: symbol={} err={}", symbol, e.getMessage(), e);
            }
        }
    }

    private boolean isStopLossTriggered(String symbol, TradeSignal.Side side, double markPrice, double baseEntryPrice) {
        if (baseEntryPrice <= 0) {
            return false;
        }
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
     * 階梯式 Trailing Stop：markPrice 每突破一個階段，TP 跟隨上移鎖利。
     * 每個階段的 offset = 上一階段的 trigger（自然階梯），保證不可回退。
     *
     * 以 breakevenTriggerPercent=0.008 為例（LONG）：
     *   Tier 1: markPrice >= avg×1.008 → TP = avg×1.002（保本）
     *   Tier 2: markPrice >= avg×1.015 → TP = avg×1.008（鎖定 0.8%）
     *   Tier 3: markPrice >= avg×1.025 → TP = avg×1.015（鎖定 1.5%）
     *   Tier 4: markPrice >= avg×1.040 → TP = avg×1.025（鎖定 2.5%）
     */
    private static final double[] TRAILING_TRIGGER_MULTIPLIERS = {1.0, 1.875, 3.125, 5.0};

    private void checkTrailingStop(MartingaleSession session, double markPrice) {
        String symbol = session.getSymbol();

        LayerFillTracker.AggregatedFill fill = layerFillTracker.getAggregatedFill(symbol);
        if (fill.totalQty() <= 0 || fill.avgPrice() <= 0) {
            return;
        }

        double avgPrice = fill.avgPrice();
        double baseTrigger = config.getBreakevenTriggerPercent();
        double baseOffset = config.getBreakevenOffsetPercent();
        int currentLevel = session.getTrailingLevel();

        // 找出目前 markPrice 能達到的最高階段
        int qualifiedLevel = 0;
        for (int i = 0; i < TRAILING_TRIGGER_MULTIPLIERS.length; i++) {
            double triggerPercent = baseTrigger * TRAILING_TRIGGER_MULTIPLIERS[i];
            boolean triggered;
            if (session.getSide() == TradeSignal.Side.LONG) {
                triggered = markPrice >= avgPrice * (1.0 + triggerPercent);
            } else {
                triggered = markPrice <= avgPrice * (1.0 - triggerPercent);
            }
            if (triggered) {
                qualifiedLevel = i + 1;
            } else {
                break;
            }
        }

        // 只在提升階段時才移動 TP（不可回退）
        if (qualifiedLevel <= currentLevel) {
            return;
        }

        // 計算新的 TP offset：tier 1 用 baseOffset，tier 2+ 用前一個 tier 的 trigger
        double offsetPercent;
        if (qualifiedLevel == 1) {
            offsetPercent = baseOffset;
        } else {
            offsetPercent = baseTrigger * TRAILING_TRIGGER_MULTIPLIERS[qualifiedLevel - 2];
        }

        ReentrantLock lock = symbolLockRegistry.getLock(symbol);
        if (!lock.tryLock()) {
            return;
        }
        try {
            Optional<MartingaleSession> active = sessionManager.getActiveSession(symbol);
            if (active.isEmpty() || active.get().getStatus() != MartingaleSession.Status.ACTIVE) {
                return;
            }
            // 再次確認（可能在等鎖期間被其他線程更新）
            if (active.get().getTrailingLevel() >= qualifiedLevel) {
                return;
            }

            // 先掛新 TP → 再取消舊 TP（消除無保護窗口）
            String oldTpId = active.get().getCurrentTpOrderId();

            double newTpPrice = session.getSide() == TradeSignal.Side.LONG
                    ? avgPrice * (1.0 + offsetPercent)
                    : avgPrice * (1.0 - offsetPercent);

            String closeSide = session.getSide() == TradeSignal.Side.SHORT ? "BUY" : "SELL";
            OrderResult result = binanceFuturesService.placeTakeProfit(symbol, closeSide, newTpPrice, fill.totalQty());

            if (result != null && result.isSuccess() && result.getOrderId() != null) {
                active.get().setCurrentTpOrderId(result.getOrderId());
                active.get().setTrailingLevel(qualifiedLevel);

                // 新 TP 成功後才取消舊 TP
                if (oldTpId != null && !oldTpId.isBlank()) {
                    try {
                        binanceFuturesService.cancelAlgoOrder(symbol, Long.parseLong(oldTpId));
                    } catch (Exception e) {
                        log.warn("Trailing: 取消舊 TP 失敗 symbol={} err={}", symbol, e.getMessage());
                    }
                }

                log.info("Martingale trailing TP level {}: symbol={} avgPrice={} tp={} markPrice={}",
                        qualifiedLevel, symbol, avgPrice, newTpPrice, markPrice);
                stateStore.persistSession(active.get());
                notifier.notifyTrailingStopAdvanced(symbol, qualifiedLevel, newTpPrice, fill.totalQty());
            } else {
                // 新 TP 失敗 → 保留舊 TP，不更新 level
                String err = result != null ? result.getErrorMessage() : "null result";
                log.error("Martingale trailing TP 下單失敗（保留舊 TP）: symbol={} level={} err={}", symbol, qualifiedLevel, err);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * TP 時間衰減：持倉超過 tpDecayStartMinutes 後，每 tpDecayIntervalMinutes 降低一階 TP，
     * 從 takeProfitPercent 線性衰減到 tpDecayFloorPercent，避免長時間浮虧。
     * 只在 Trailing Stop 未啟動時生效（Trailing 啟動後由 Trailing 管理 TP）。
     */
    private void checkTpDecay(MartingaleSession session) {
        String symbol = session.getSymbol();
        long holdingMinutes = Duration.between(session.getCreatedAt(), Instant.now()).toMinutes();

        if (holdingMinutes < config.getTpDecayStartMinutes()) {
            return;
        }

        long minutesPastStart = holdingMinutes - config.getTpDecayStartMinutes();
        int decayLevel = (int) (minutesPastStart / Math.max(1, config.getTpDecayIntervalMinutes())) + 1;

        if (decayLevel <= session.getTpDecayLevel()) {
            return;
        }

        // 計算衰減後的 TP 百分比
        double baseTp = config.getEffectiveTakeProfitPercent(symbol);
        double floor = config.getTpDecayFloorPercent();
        // 每階段等量衰減，預估最多衰減到 floor
        double decayPerStep = (baseTp - floor) / Math.max(1, 4); // 分 4 階段衰減到 floor
        double decayedTpPercent = Math.max(floor, baseTp - decayPerStep * decayLevel);

        LayerFillTracker.AggregatedFill fill = layerFillTracker.getAggregatedFill(symbol);
        if (fill.totalQty() <= 0 || fill.avgPrice() <= 0) {
            return;
        }

        ReentrantLock lock = symbolLockRegistry.getLock(symbol);
        if (!lock.tryLock()) {
            return;
        }
        try {
            Optional<MartingaleSession> active = sessionManager.getActiveSession(symbol);
            if (active.isEmpty() || active.get().getStatus() != MartingaleSession.Status.ACTIVE) {
                return;
            }
            // Trailing 已啟動 → 交由 Trailing 管理
            if (active.get().getTrailingLevel() > 0) {
                return;
            }
            if (active.get().getTpDecayLevel() >= decayLevel) {
                return;
            }

            double avgPrice = fill.avgPrice();
            String oldTpId = active.get().getCurrentTpOrderId();

            double newTpPrice = session.getSide() == TradeSignal.Side.LONG
                    ? avgPrice * (1.0 + decayedTpPercent)
                    : avgPrice * (1.0 - decayedTpPercent);

            String closeSide = session.getSide() == TradeSignal.Side.SHORT ? "BUY" : "SELL";
            OrderResult result = binanceFuturesService.placeTakeProfit(symbol, closeSide, newTpPrice, fill.totalQty());

            if (result != null && result.isSuccess() && result.getOrderId() != null) {
                active.get().setCurrentTpOrderId(result.getOrderId());
                active.get().setTpDecayLevel(decayLevel);

                if (oldTpId != null && !oldTpId.isBlank()) {
                    try {
                        binanceFuturesService.cancelAlgoOrder(symbol, Long.parseLong(oldTpId));
                    } catch (Exception e) {
                        log.warn("TpDecay: 取消舊 TP 失敗 symbol={} err={}", symbol, e.getMessage());
                    }
                }

                log.info("Martingale TP decay level {}: symbol={} holdingMin={} tpPercent={} tpPrice={}",
                        decayLevel, symbol, holdingMinutes, decayedTpPercent, newTpPrice);
                stateStore.persistSession(active.get());
                notifier.notifyTpDecay(symbol, decayLevel, decayedTpPercent, newTpPrice);
            } else {
                String err = result != null ? result.getErrorMessage() : "null result";
                log.error("Martingale TP decay 下單失敗（保留舊 TP）: symbol={} level={} err={}", symbol, decayLevel, err);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * SL 基準價：有成交用加權均價，無成交用 baseEntryPrice。
     */
    private double resolveSlBasePrice(MartingaleSession session) {
        if (session.getFilledLayers() > 0) {
            LayerFillTracker.AggregatedFill fill = layerFillTracker.getAggregatedFill(session.getSymbol());
            if (fill.avgPrice() > 0) {
                return fill.avgPrice();
            }
        }
        return session.getBaseEntryPrice();
    }

    private void executeStopLoss(MartingaleSession session, double markPrice, double slBasePrice) {
        String symbol = session.getSymbol();
        ReentrantLock lock = symbolLockRegistry.getLock(symbol);
        if (!lock.tryLock()) {
            return;
        }
        try {
            Optional<MartingaleSession> active = sessionManager.getActiveSession(symbol);
            if (active.isEmpty() || active.get().getStatus() != MartingaleSession.Status.ACTIVE) {
                return;
            }

            sessionManager.markExiting(symbol);
            binanceFuturesService.cancelAllOrders(symbol);

            positionService.getPosition(symbol)
                    .filter(PositionInfo::isOpen)
                    .ifPresent(position -> {
                        double qty = Math.abs(position.quantity());
                        if (qty > 0) {
                            String closeSide = position.side() == TradeSignal.Side.SHORT ? "BUY" : "SELL";
                            binanceFuturesService.placeMarketOrder(symbol, closeSide, qty);
                        }
                    });

            layerFillTracker.clearSymbol(symbol);
            sessionManager.endSession(symbol);
            stateStore.removeSession(symbol);

            log.warn("Martingale stop loss triggered: symbol={} side={} slBase={} markPrice={} stopLossPercent={}",
                    symbol, session.getSide(), slBasePrice, markPrice, config.getEffectiveStopLossPercent(symbol));
            notifier.notifyStopLossTriggered(symbol, session.getSide(), slBasePrice, markPrice);
        } finally {
            lock.unlock();
        }
    }
}
