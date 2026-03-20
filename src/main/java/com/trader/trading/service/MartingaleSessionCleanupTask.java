package com.trader.trading.service;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.model.MartingaleSession;
import com.trader.trading.model.PositionInfo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class MartingaleSessionCleanupTask {

    private final MartingaleSessionManager sessionManager;
    private final LayerFillTracker layerFillTracker;
    private final BinanceFuturesService binanceFuturesService;
    private final PositionService positionService;
    private final SymbolLockRegistry symbolLockRegistry;
    private final MartingaleStrategyConfig config;

    public MartingaleSessionCleanupTask(MartingaleSessionManager sessionManager,
                                        LayerFillTracker layerFillTracker,
                                        BinanceFuturesService binanceFuturesService,
                                        PositionService positionService,
                                        SymbolLockRegistry symbolLockRegistry,
                                        MartingaleStrategyConfig config) {
        this.sessionManager = sessionManager;
        this.layerFillTracker = layerFillTracker;
        this.binanceFuturesService = binanceFuturesService;
        this.positionService = positionService;
        this.symbolLockRegistry = symbolLockRegistry;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${trading.strategy.martingale.session-cleanup-interval-millis:60000}")
    public void cleanupIdleSessions() {
        Duration idleTimeout = Duration.ofMinutes(config.getSessionIdleTimeoutMinutes());
        Instant now = Instant.now();

        for (MartingaleSession session : sessionManager.getSessionsSnapshot()) {
            if (session.getStatus() != MartingaleSession.Status.ACTIVE) {
                continue;
            }

            String symbol = session.getSymbol();
            if (symbol == null || symbol.isBlank()) {
                continue;
            }

            Instant lastFillAt = layerFillTracker.getLastFillAt(symbol);
            Instant lastActivity = lastFillAt != null ? lastFillAt : session.getCreatedAt();

            if (Duration.between(lastActivity, now).compareTo(idleTimeout) <= 0) {
                continue;
            }

            ReentrantLock lock = symbolLockRegistry.getLock(symbol);
            if (!lock.tryLock()) {
                continue;
            }
            try {
                Optional<MartingaleSession> active = sessionManager.getActiveSession(symbol);
                if (active.isEmpty() || active.get().getStatus() != MartingaleSession.Status.ACTIVE) {
                    continue;
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

                log.warn("Martingale session timeout cleanup: symbol={} lastActivity={} timeoutMinutes={}",
                        symbol, lastActivity, config.getSessionIdleTimeoutMinutes());
            } finally {
                lock.unlock();
            }
        }
    }
}
