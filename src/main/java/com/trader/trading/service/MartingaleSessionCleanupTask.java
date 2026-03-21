package com.trader.trading.service;

import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.model.MartingaleSession;
import com.trader.trading.model.PositionInfo;
import com.trader.trading.service.martingale.MartingaleNotifier;
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
    private final MartingaleNotifier notifier;

    public MartingaleSessionCleanupTask(MartingaleSessionManager sessionManager,
                                        LayerFillTracker layerFillTracker,
                                        BinanceFuturesService binanceFuturesService,
                                        PositionService positionService,
                                        SymbolLockRegistry symbolLockRegistry,
                                        MartingaleStrategyConfig config,
                                        MartingaleNotifier notifier) {
        this.sessionManager = sessionManager;
        this.layerFillTracker = layerFillTracker;
        this.binanceFuturesService = binanceFuturesService;
        this.positionService = positionService;
        this.symbolLockRegistry = symbolLockRegistry;
        this.config = config;
        this.notifier = notifier;
    }

    @Scheduled(fixedDelayString = "${trading.strategy.martingale.session-cleanup-interval-millis:60000}")
    public void cleanupSessions() {
        Duration idleTimeout = Duration.ofMinutes(config.getSessionIdleTimeoutMinutes());
        Instant now = Instant.now();

        for (MartingaleSession session : sessionManager.getSessionsSnapshot()) {
            String symbol = session.getSymbol();
            if (symbol == null || symbol.isBlank()) {
                continue;
            }

            if (session.getStatus() == MartingaleSession.Status.EXITING) {
                retryExitingSession(session);
                continue;
            }

            if (session.getStatus() != MartingaleSession.Status.ACTIVE) {
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

                boolean closed = closePosition(symbol);
                if (closed) {
                    layerFillTracker.clearSymbol(symbol);
                    sessionManager.endSession(symbol);
                    log.warn("Martingale session timeout cleanup: symbol={} lastActivity={} timeoutMinutes={}",
                            symbol, lastActivity, config.getSessionIdleTimeoutMinutes());
                    notifier.notifySessionTimeout(symbol);
                } else {
                    log.error("Martingale session timeout cleanup 平倉失敗，保留 EXITING: symbol={}", symbol);
                }
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * 重試 EXITING 狀態的 session（上次平倉失敗留下的）。
     */
    private void retryExitingSession(MartingaleSession session) {
        String symbol = session.getSymbol();
        ReentrantLock lock = symbolLockRegistry.getLock(symbol);
        if (!lock.tryLock()) {
            return;
        }
        try {
            boolean closed = closePosition(symbol);
            if (closed) {
                layerFillTracker.clearSymbol(symbol);
                sessionManager.endSession(symbol);
                log.info("Martingale EXITING session 重試平倉成功: symbol={}", symbol);
            } else {
                log.warn("Martingale EXITING session 重試平倉仍失敗: symbol={}", symbol);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 嘗試市價平倉，回傳是否成功（無持倉也算成功）。
     */
    private boolean closePosition(String symbol) {
        Optional<PositionInfo> posOpt = positionService.getPosition(symbol);
        if (posOpt.isEmpty() || !posOpt.get().isOpen()) {
            return true; // 無持倉，視為成功
        }
        PositionInfo position = posOpt.get();
        double qty = Math.abs(position.quantity());
        if (qty <= 0) {
            return true;
        }
        try {
            String closeSide = position.side() == TradeSignal.Side.SHORT ? "BUY" : "SELL";
            binanceFuturesService.placeMarketOrder(symbol, closeSide, qty);
            return true;
        } catch (Exception e) {
            log.error("市價平倉失敗: symbol={} err={}", symbol, e.getMessage());
            return false;
        }
    }
}
