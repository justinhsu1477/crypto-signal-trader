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

            boolean hasFills = session.getFilledLayers() > 0;

            if (hasFills) {
                // 有成交的 session：用 sessionMaxDurationMinutes（從 session 建立時間算起）
                Duration elapsed = Duration.between(session.getCreatedAt(), now);
                Duration maxDuration = Duration.ofMinutes(config.getSessionMaxDurationMinutes());
                if (elapsed.compareTo(maxDuration) <= 0) {
                    continue;
                }
                handleTimeout(session, "session-max-duration", elapsed.toMinutes());
            } else {
                // 無成交的純掛單 session：用 entryIdleTimeoutMinutes（從建立時間算起）
                Duration elapsed = Duration.between(session.getCreatedAt(), now);
                Duration idleTimeout = Duration.ofMinutes(config.getEntryIdleTimeoutMinutes());
                if (elapsed.compareTo(idleTimeout) <= 0) {
                    continue;
                }
                handleIdleEntryTimeout(session, elapsed.toMinutes());
            }
        }
    }

    /**
     * 有成交的 session 超過最大持續時間：取消掛單 + 市價平倉 + 清理。
     */
    private void handleTimeout(MartingaleSession session, String reason, long elapsedMinutes) {
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

            boolean closed = closePosition(symbol);
            if (closed) {
                layerFillTracker.clearSymbol(symbol);
                sessionManager.endSession(symbol);
                log.warn("Martingale session 超時平倉: symbol={} reason={} elapsed={}min maxDuration={}min",
                        symbol, reason, elapsedMinutes, config.getSessionMaxDurationMinutes());
                notifier.notifySessionTimeout(symbol);
            } else {
                log.error("Martingale session 超時平倉失敗，保留 EXITING: symbol={}", symbol);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 無成交的純掛單 session 超時：只取消掛單 + 清理 session，不需平倉。
     */
    private void handleIdleEntryTimeout(MartingaleSession session, long elapsedMinutes) {
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

            // 再次確認無成交（避免 race condition：在取得 lock 期間有新的 fill）
            if (active.get().getFilledLayers() > 0) {
                return;
            }

            binanceFuturesService.cancelAllOrders(symbol);
            layerFillTracker.clearSymbol(symbol);
            sessionManager.endSession(symbol);

            log.info("Martingale 純掛單 session 閒置超時，取消掛單: symbol={} elapsed={}min idleTimeout={}min",
                    symbol, elapsedMinutes, config.getEntryIdleTimeoutMinutes());
            notifier.notifySessionTimeout(symbol);
        } finally {
            lock.unlock();
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
     * 下單後驗證實際倉位，部分成交時重試一次，仍有殘留則告警。
     */
    private boolean closePosition(String symbol) {
        Optional<PositionInfo> posOpt = positionService.getPosition(symbol);
        if (posOpt.isEmpty() || !posOpt.get().isOpen()) {
            return true;
        }
        PositionInfo position = posOpt.get();
        double qty = Math.abs(position.quantity());
        if (qty <= 0) {
            return true;
        }
        try {
            String closeSide = position.side() == TradeSignal.Side.SHORT ? "BUY" : "SELL";
            binanceFuturesService.placeMarketOrder(symbol, closeSide, qty);

            // 驗證倉位是否完全平倉
            double remaining = Math.abs(binanceFuturesService.getCurrentPositionAmount(symbol));
            if (remaining > 0) {
                log.warn("平倉部分成交，剩餘倉位重試: symbol={} remaining={}", symbol, remaining);
                binanceFuturesService.placeMarketOrder(symbol, closeSide, remaining);
                remaining = Math.abs(binanceFuturesService.getCurrentPositionAmount(symbol));
            }
            if (remaining > 0) {
                log.error("平倉重試後仍有剩餘倉位（幽靈倉位）: symbol={} remaining={}", symbol, remaining);
                notifier.notifyGhostPosition(symbol, remaining);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("市價平倉失敗: symbol={} err={}", symbol, e.getMessage());
            return false;
        }
    }
}
