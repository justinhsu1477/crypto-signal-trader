package com.trader.trading.service.martingale;

import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.config.MartingaleStrategyConfig;
import com.trader.trading.model.MartingaleSession;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.LayerFillTracker;
import com.trader.trading.service.MartingaleSessionManager;
import com.trader.trading.service.SymbolLockRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
public class MartingaleTpManager {

    private final MartingaleSessionManager sessionManager;
    private final LayerFillTracker layerFillTracker;
    private final BinanceFuturesService binanceFuturesService;
    private final SymbolLockRegistry symbolLockRegistry;
    private final MartingaleStrategyConfig config;
    private final MartingaleNotifier notifier;

    public MartingaleTpManager(MartingaleSessionManager sessionManager,
                               LayerFillTracker layerFillTracker,
                               BinanceFuturesService binanceFuturesService,
                               SymbolLockRegistry symbolLockRegistry,
                               MartingaleStrategyConfig config,
                               MartingaleNotifier notifier) {
        this.sessionManager = sessionManager;
        this.layerFillTracker = layerFillTracker;
        this.binanceFuturesService = binanceFuturesService;
        this.symbolLockRegistry = symbolLockRegistry;
        this.config = config;
        this.notifier = notifier;
    }

    /**
     * 根據實際成交資料重新掛出 TP 單。
     * 每次 ENTRY 層成交後由 MartingaleFillListener 呼叫。
     */
    public void updateTakeProfit(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }

        ReentrantLock lock = symbolLockRegistry.getLock(symbol);
        lock.lock();
        try {
            Optional<MartingaleSession> sessionOpt = sessionManager.getActiveSession(symbol);
            if (sessionOpt.isEmpty()) {
                return;
            }

            MartingaleSession session = sessionOpt.get();
            LayerFillTracker.AggregatedFill fill = layerFillTracker.getAggregatedFill(symbol);
            if (fill.totalQty() <= 0 || fill.avgPrice() <= 0) {
                return;
            }

            // 取消舊 TP
            cancelCurrentTp(session);

            // 按實際成交均價計算新 TP 價格
            double tpPrice = session.getSide() == TradeSignal.Side.LONG
                    ? fill.avgPrice() * (1.0 + config.getTakeProfitPercent())
                    : fill.avgPrice() * (1.0 - config.getTakeProfitPercent());

            String closeSide = session.getSide() == TradeSignal.Side.SHORT ? "BUY" : "SELL";
            OrderResult result = binanceFuturesService.placeTakeProfit(symbol, closeSide, tpPrice, fill.totalQty());

            if (result != null && result.isSuccess() && result.getOrderId() != null) {
                session.setCurrentTpOrderId(result.getOrderId());
                log.info("Martingale TP updated: symbol={} side={} avgPrice={} tpPrice={} qty={}",
                        symbol, session.getSide(), fill.avgPrice(), tpPrice, fill.totalQty());
                notifier.notifyTpUpdated(symbol, tpPrice, fill.totalQty());
                notifier.notifyLayerFilled(symbol, fill.avgPrice(), fill.totalQty(), fill.avgPrice(), fill.totalQty());
            } else {
                String err = result != null ? result.getErrorMessage() : "null result";
                log.error("Martingale TP placement failed: symbol={} err={}", symbol, err);
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * TP 成交後的清理流程：取消殘留 ENTRY 掛單、結束 session、清理 tracker。
     * 由 MartingaleFillListener 偵測到 ALGO_UPDATE TRIGGERED 或 ORDER_TRADE_UPDATE 平倉成交時呼叫。
     */
    public void handleTpFilled(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return;
        }

        ReentrantLock lock = symbolLockRegistry.getLock(symbol);
        lock.lock();
        try {
            Optional<MartingaleSession> sessionOpt = sessionManager.getActiveSession(symbol);
            if (sessionOpt.isEmpty()) {
                return;
            }

            MartingaleSession session = sessionOpt.get();

            // 取消所有殘留的 ENTRY LIMIT 掛單
            try {
                binanceFuturesService.cancelAllOrders(symbol);
            } catch (Exception e) {
                log.warn("Martingale TP filled — 取消殘留掛單失敗: symbol={} err={}", symbol, e.getMessage());
            }

            // 清理 session 和 tracker
            layerFillTracker.clearSymbol(symbol);
            sessionManager.endSession(symbol);

            log.info("Martingale TP 成交，Session 已清理: symbol={} side={}", symbol, session.getSide());
            notifier.notifyTpHit(symbol, session.getSide());
        } finally {
            lock.unlock();
        }
    }

    private void cancelCurrentTp(MartingaleSession session) {
        String tpOrderId = session.getCurrentTpOrderId();
        if (tpOrderId == null || tpOrderId.isBlank()) {
            return;
        }
        try {
            binanceFuturesService.cancelAlgoOrder(session.getSymbol(), Long.parseLong(tpOrderId));
            session.setCurrentTpOrderId(null);
        } catch (Exception e) {
            log.warn("Failed to cancel old TP: symbol={} algoId={} err={}",
                    session.getSymbol(), tpOrderId, e.getMessage());
        }
    }
}
