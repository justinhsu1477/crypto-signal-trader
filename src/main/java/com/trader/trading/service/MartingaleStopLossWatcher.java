package com.trader.trading.service;

import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.model.MartingaleSession;
import com.trader.trading.model.PositionInfo;
import com.trader.trading.service.martingale.MartingaleNotifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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

    public MartingaleStopLossWatcher(MartingaleSessionManager sessionManager,
                                     BinanceFuturesService binanceFuturesService,
                                     PositionService positionService,
                                     LayerFillTracker layerFillTracker,
                                     SymbolLockRegistry symbolLockRegistry,
                                     MartingaleStrategyConfig config,
                                     MartingaleNotifier notifier) {
        this.sessionManager = sessionManager;
        this.binanceFuturesService = binanceFuturesService;
        this.positionService = positionService;
        this.layerFillTracker = layerFillTracker;
        this.symbolLockRegistry = symbolLockRegistry;
        this.config = config;
        this.notifier = notifier;
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
                if (isStopLossTriggered(session.getSide(), markPrice, slBasePrice)) {
                    executeStopLoss(session, markPrice, slBasePrice);
                    continue;
                }

                // 2) Breakeven 保本檢查（只在有成交且 breakevenTriggerPercent > 0 時啟用）
                if (session.getFilledLayers() > 0 && config.getBreakevenTriggerPercent() > 0) {
                    checkBreakeven(session, markPrice, slBasePrice);
                }
            } catch (Exception e) {
                log.error("Stop loss check failed: symbol={} err={}", symbol, e.getMessage(), e);
            }
        }
    }

    private boolean isStopLossTriggered(TradeSignal.Side side, double markPrice, double baseEntryPrice) {
        if (baseEntryPrice <= 0) {
            return false;
        }
        if (side == TradeSignal.Side.LONG) {
            return markPrice <= baseEntryPrice * (1.0 - config.getStopLossPercent());
        }
        return markPrice >= baseEntryPrice * (1.0 + config.getStopLossPercent());
    }

    /**
     * 保本移動 TP：當 markPrice 超過 avgPrice × (1 + trigger) 時，
     * 將 TP 移到 avgPrice × (1 + offset)（微利出場），保護已回收利潤。
     */
    private void checkBreakeven(MartingaleSession session, double markPrice, double avgPrice) {
        if (session.isBreakevenActivated()) {
            return; // 已觸發過，不重複操作
        }

        double trigger = config.getBreakevenTriggerPercent();
        double offset = config.getBreakevenOffsetPercent();

        boolean triggered;
        if (session.getSide() == TradeSignal.Side.LONG) {
            triggered = markPrice >= avgPrice * (1.0 + trigger);
        } else {
            triggered = markPrice <= avgPrice * (1.0 - trigger);
        }

        if (!triggered) {
            return;
        }

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
            if (active.get().isBreakevenActivated()) {
                return;
            }

            LayerFillTracker.AggregatedFill fill = layerFillTracker.getAggregatedFill(symbol);
            if (fill.totalQty() <= 0 || fill.avgPrice() <= 0) {
                return;
            }

            // 取消舊 TP
            String oldTpId = active.get().getCurrentTpOrderId();
            if (oldTpId != null && !oldTpId.isBlank()) {
                try {
                    binanceFuturesService.cancelAlgoOrder(symbol, Long.parseLong(oldTpId));
                } catch (Exception e) {
                    log.warn("Breakeven: 取消舊 TP 失敗 symbol={} err={}", symbol, e.getMessage());
                }
            }

            // 掛新的保本 TP
            double breakevenTpPrice = session.getSide() == TradeSignal.Side.LONG
                    ? fill.avgPrice() * (1.0 + offset)
                    : fill.avgPrice() * (1.0 - offset);

            String closeSide = session.getSide() == TradeSignal.Side.SHORT ? "BUY" : "SELL";
            OrderResult result = binanceFuturesService.placeTakeProfit(symbol, closeSide, breakevenTpPrice, fill.totalQty());

            if (result != null && result.isSuccess() && result.getOrderId() != null) {
                active.get().setCurrentTpOrderId(result.getOrderId());
                active.get().setBreakevenActivated(true);
                log.info("Martingale breakeven TP activated: symbol={} avgPrice={} breakevenTp={} markPrice={}",
                        symbol, fill.avgPrice(), breakevenTpPrice, markPrice);
                notifier.notifyBreakevenActivated(symbol, breakevenTpPrice, fill.totalQty());
            } else {
                String err = result != null ? result.getErrorMessage() : "null result";
                log.error("Martingale breakeven TP 下單失敗: symbol={} err={}", symbol, err);
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

            log.warn("Martingale stop loss triggered: symbol={} side={} slBase={} markPrice={} stopLossPercent={}",
                    symbol, session.getSide(), slBasePrice, markPrice, config.getStopLossPercent());
            notifier.notifyStopLossTriggered(symbol, session.getSide(), slBasePrice, markPrice);
        } finally {
            lock.unlock();
        }
    }
}
