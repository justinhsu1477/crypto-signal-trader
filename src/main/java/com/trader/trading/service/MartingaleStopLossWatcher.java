package com.trader.trading.service;

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

                if (!isStopLossTriggered(session.getSide(), markPrice, slBasePrice)) {
                    continue;
                }

                executeStopLoss(session, markPrice, slBasePrice);
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
     * SL 基準價：有成交用加權均價，無成交用 baseEntryPrice。
     * 確保無論幾層成交，SL 距離均價的比例一致。
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
