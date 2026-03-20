package com.trader.trading.service;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.model.MartingaleSession;
import com.trader.trading.model.PositionInfo;
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

    public MartingaleStopLossWatcher(MartingaleSessionManager sessionManager,
                                     BinanceFuturesService binanceFuturesService,
                                     PositionService positionService,
                                     LayerFillTracker layerFillTracker,
                                     SymbolLockRegistry symbolLockRegistry,
                                     MartingaleStrategyConfig config) {
        this.sessionManager = sessionManager;
        this.binanceFuturesService = binanceFuturesService;
        this.positionService = positionService;
        this.layerFillTracker = layerFillTracker;
        this.symbolLockRegistry = symbolLockRegistry;
        this.config = config;
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

                if (!isStopLossTriggered(session.getSide(), markPrice, session.getBaseEntryPrice())) {
                    continue;
                }

                executeStopLoss(session, markPrice);
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

    private void executeStopLoss(MartingaleSession session, double markPrice) {
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

            log.warn("Martingale stop loss triggered: symbol={} side={} baseEntry={} markPrice={} stopLossPercent={}",
                    symbol, session.getSide(), session.getBaseEntryPrice(), markPrice, config.getStopLossPercent());
        } finally {
            lock.unlock();
        }
    }
}
