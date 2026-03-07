package com.trader.trading.service;


import com.trader.shared.config.RiskConfig;
import com.trader.trading.config.MultiUserConfig;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeRequest;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.exchange.ExchangeCredentials;
import com.trader.trading.exchange.binance.BinanceAdapter;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.ExchangeKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Binance Futures 交易服務 — Facade + 廣播跟單
 *
 * Phase 1 重構：交易所 API 操作已抽取至 {@link BinanceAdapter}
 * Phase 2 重構：業務邏輯（風控/DCA/熔斷）已搬至 {@link TradingOrchestrator}
 *
 * 本類保留：
 * 1. Facade 方法 — 供 TradeController 和其他呼叫者使用
 * 2. executeSignalForBroadcast — 廣播跟單（credential 管理 + action 路由）
 * 3. calculatePositionSize — 供 TradeSignalValidator/TradeRecordService 呼叫
 * 4. ThreadLocal CURRENT_USER_KEYS 管理（Phase 5 完成後移除）
 *
 * 公開 API 不變，所有呼叫者行為不變。
 */
@Slf4j
@Service
public class BinanceFuturesService {

    private final BinanceAdapter binanceAdapter;
    private final TradingOrchestrator orchestrator;
    private final RiskConfig riskConfig;
    private final TradeRecordService tradeRecordService;
    private final SignalDeduplicationService deduplicationService;
    private final MultiUserConfig multiUserConfig;
    private final SymbolLockRegistry symbolLockRegistry;
    private final UserApiKeyService userApiKeyService;
    private final TradeConfigResolver tradeConfigResolver;

    /**
     * ThreadLocal 暫存 per-user API Key（舊版，保留向後相容）
     * 在 executeSignalForBroadcast 開始時 set，結束時 remove。
     * BinanceAdapter 的 getActiveApiKey() 會檢查此 ThreadLocal 作為 fallback。
     * Phase 5 完成所有呼叫者遷移後刪除。
     */
    private static final ThreadLocal<ExchangeKeys> CURRENT_USER_KEYS = new ThreadLocal<>();

    public BinanceFuturesService(BinanceAdapter binanceAdapter,
                                  TradingOrchestrator orchestrator,
                                  RiskConfig riskConfig,
                                  TradeRecordService tradeRecordService,
                                  SignalDeduplicationService deduplicationService,
                                  MultiUserConfig multiUserConfig,
                                  SymbolLockRegistry symbolLockRegistry,
                                  UserApiKeyService userApiKeyService,
                                  TradeConfigResolver tradeConfigResolver) {
        this.binanceAdapter = binanceAdapter;
        this.orchestrator = orchestrator;
        this.riskConfig = riskConfig;
        this.tradeRecordService = tradeRecordService;
        this.deduplicationService = deduplicationService;
        this.multiUserConfig = multiUserConfig;
        this.symbolLockRegistry = symbolLockRegistry;
        this.userApiKeyService = userApiKeyService;
        this.tradeConfigResolver = tradeConfigResolver;
    }

    // ==================== Per-User API Key（舊版，向後相容）====================

    /**
     * 設定當前線程的 per-user API Key
     * 供排程任務（DailyReportService）查詢個別用戶的幣安帳戶餘額時使用。
     * 使用完畢後務必呼叫 clearCurrentUserKeys() 清除，避免線程池復用時洩漏。
     */
    public static void setCurrentUserKeys(ExchangeKeys keys) {
        CURRENT_USER_KEYS.set(keys);
    }

    /**
     * 清除當前線程的 per-user API Key
     */
    public static void clearCurrentUserKeys() {
        CURRENT_USER_KEYS.remove();
    }

    /**
     * 取得當前線程的 per-user API Key（供 BinanceAdapter 向後相容讀取）
     */
    public static ExchangeKeys getCurrentUserKeys() {
        return CURRENT_USER_KEYS.get();
    }

    // ==================== Facade：帳戶相關（委託 BinanceAdapter）====================

    public String getAccountBalance() {
        return binanceAdapter.getAccountBalanceRaw();
    }

    public double getAvailableBalance() {
        return binanceAdapter.getAvailableBalance();
    }

    public String getPositions() {
        return binanceAdapter.getPositionsRaw();
    }

    public String getExchangeInfo() {
        return binanceAdapter.getExchangeInfoRaw();
    }

    public double getCurrentPositionAmount(String symbol) {
        return binanceAdapter.getCurrentPositionAmount(symbol);
    }

    public Map<String, Double> getAllPositionAmounts() {
        return binanceAdapter.getAllPositionAmounts();
    }

    public String getForceOrders() {
        return binanceAdapter.getForceOrdersRaw();
    }

    public double getMarkPrice(String symbol) {
        return binanceAdapter.getMarkPrice(symbol);
    }

    public int getActivePositionCount() {
        return binanceAdapter.getActivePositionCount();
    }

    public boolean hasOpenEntryOrders(String symbol) {
        return binanceAdapter.hasOpenEntryOrders(symbol);
    }

    // ==================== Facade：交易相關（委託 BinanceAdapter）====================

    public String setLeverage(String symbol, int leverage) {
        binanceAdapter.setLeverage(symbol, leverage);
        return "";
    }

    public String setMarginType(String symbol, String marginType) {
        binanceAdapter.setMarginType(symbol, marginType);
        return "";
    }

    public String setPositionMode(boolean dualSidePosition) {
        binanceAdapter.setPositionMode(dualSidePosition);
        return "";
    }

    public OrderResult placeLimitOrder(String symbol, String side, double price, double quantity) {
        return binanceAdapter.placeLimitOrder(symbol, side, price, quantity);
    }

    public OrderResult placeMarketOrder(String symbol, String side, double quantity) {
        return binanceAdapter.placeMarketOrder(symbol, side, quantity);
    }

    public OrderResult placeStopLoss(String symbol, String side, double stopPrice, double quantity) {
        return binanceAdapter.setStopLoss(symbol, side, stopPrice, quantity);
    }

    public OrderResult placeTakeProfit(String symbol, String side, double stopPrice, double quantity) {
        return binanceAdapter.setTakeProfit(symbol, side, stopPrice, quantity);
    }

    public String cancelOrder(String symbol, long orderId) {
        binanceAdapter.cancelOrder(symbol, String.valueOf(orderId));
        return "";
    }

    /**
     * 取消所有訂單：標準訂單 + Algo 訂單（SL/TP）
     */
    public String cancelAllOrders(String symbol) {
        binanceAdapter.cancelAllOrders(symbol);
        return "";
    }

    public void cancelSLTPOrders(String symbol) {
        binanceAdapter.cancelSLTPOrders(symbol);
    }

    public double[] getCurrentSLTPPrices(String symbol) {
        return binanceAdapter.getCurrentSLTPPrices(symbol);
    }

    public String getOpenOrders(String symbol) {
        return binanceAdapter.getOpenOrdersRaw(symbol);
    }

    // ==================== Facade：格式化（委託 BinanceAdapter）====================

    private String formatPrice(double price) {
        return binanceAdapter.formatPrice(price);
    }

    private String formatQuantity(String symbol, double quantity) {
        return binanceAdapter.formatQuantity(symbol, quantity);
    }

    // ==================== 業務邏輯（委託 TradingOrchestrator）====================

    /**
     * ENTRY: 以損定倉開倉
     */
    public List<OrderResult> executeSignal(TradeSignal signal) {
        return orchestrator.executeSignal(signal, binanceAdapter);
    }

    /**
     * CLOSE: 分批平倉
     */
    public List<OrderResult> executeClose(TradeSignal signal) {
        return orchestrator.executeClose(signal, binanceAdapter);
    }

    /**
     * MOVE_SL: 移動止損/止盈
     */
    public List<OrderResult> executeMoveSL(TradeSignal signal) {
        return orchestrator.executeMoveSL(signal, binanceAdapter);
    }

    // ==================== 以損定倉計算 ====================

    /**
     * 以損定倉計算下單數量（含名目價值 cap）
     */
    public double calculatePositionSize(double balance, double entryPrice, double stopLoss) {
        double riskDistance = Math.abs(entryPrice - stopLoss);
        if (riskDistance == 0) {
            throw new IllegalArgumentException("入場價與止損價不可相同");
        }
        double riskAmount = balance * riskConfig.getRiskPercent();
        double quantity = riskAmount / riskDistance;

        double maxNotional = riskConfig.getMaxPositionUsdt();
        if (maxNotional > 0) {
            double cappedQty = maxNotional / entryPrice;
            quantity = Math.min(quantity, cappedQty);
        }
        return quantity;
    }

    // ==================== 廣播跟單 ====================

    /**
     * 廣播跟單用：執行單個用戶的跟單邏輯
     *
     * @deprecated Phase 4 後廣播跟單已搬至 {@link BroadcastTradeService#executeSignalForUser}，
     *             保留此方法供排程任務向後相容（Phase 5 完成後移除）
     */
    @Deprecated
    public List<OrderResult> executeSignalForBroadcast(TradeRequest request, String userId) {
        log.info("廣播跟單執行: userId={} action={} symbol={}", userId, request.getAction(), request.getSymbol());

        String action = request.getAction();
        if (action == null) {
            throw new IllegalArgumentException("action 不可為空");
        }

        String symbol = request.getSymbol();
        EffectiveTradeConfig broadcastConfig = tradeConfigResolver.resolve(userId);
        if (symbol == null || !broadcastConfig.isSymbolAllowed(symbol)) {
            throw new IllegalArgumentException("交易對不在白名單: " + symbol);
        }

        // 取得 per-user API Key — 未設定則拒絕執行
        var userKeysOpt = userApiKeyService.getUserExchangeKeys(userId, "BINANCE");
        if (userKeysOpt.isEmpty()) {
            throw new IllegalStateException(
                    "用戶 " + userId + " 未設定 Binance API Key，無法執行廣播跟單");
        }
        // 設入舊版 ThreadLocal（向後相容）+ 新版 adapter credentials
        CURRENT_USER_KEYS.set(userKeysOpt.get());
        binanceAdapter.setCredentials(new ExchangeCredentials(
                userKeysOpt.get().apiKey(), userKeysOpt.get().secretKey()));
        TradeRecordService.setCurrentUserId(userId);
        TradingOrchestrator.setBroadcastContext(true);
        log.info("廣播跟單: userId={} 使用 per-user API Key", userId);

        List<OrderResult> broadcastResults = List.of();
        try {
        switch (action.toUpperCase()) {
            case "ENTRY" -> {
                boolean isDca = request.getIsDca() != null && request.getIsDca();

                if (!isDca && request.getSide() == null) {
                    throw new IllegalArgumentException("ENTRY 需要 side");
                }
                if (request.getEntryPrice() == null) {
                    throw new IllegalArgumentException("ENTRY 需要 entry_price");
                }
                if (request.getStopLoss() == null && !isDca) {
                    throw new IllegalArgumentException("ENTRY 必須包含 stop_loss");
                }

                if (isDca && request.getNewStopLoss() == null && request.getStopLoss() != null) {
                    request.setNewStopLoss(request.getStopLoss());
                }

                TradeSignal.TradeSignalBuilder builder = TradeSignal.builder()
                        .symbol(symbol)
                        .entryPriceLow(request.getEntryPrice())
                        .entryPriceHigh(request.getEntryPrice())
                        .signalType(TradeSignal.SignalType.ENTRY)
                        .isDca(isDca)
                        .newStopLoss(request.getNewStopLoss())
                        .newTakeProfit(request.getNewTakeProfit())
                        .source(request.getSource());

                if (request.getSide() != null) {
                    builder.side(TradeSignal.Side.valueOf(request.getSide().toUpperCase()));
                }
                if (isDca) {
                    builder.stopLoss(request.getNewStopLoss() != null ? request.getNewStopLoss() : 0);
                } else {
                    builder.stopLoss(request.getStopLoss());
                }

                TradeSignal signal = builder.build();
                if (request.getTakeProfit() != null) {
                    signal.setTakeProfits(List.of(request.getTakeProfit()));
                }

                List<OrderResult> results = executeSignal(signal);
                boolean ok = results.stream().anyMatch(r -> r.isSuccess() && r.getOrderId() != null);
                if (!ok) {
                    String errors = results.stream()
                            .filter(r -> !r.isSuccess())
                            .map(OrderResult::getErrorMessage)
                            .collect(Collectors.joining("; "));
                    throw new RuntimeException("ENTRY 失敗: " + errors);
                }
                broadcastResults = results;
            }
            case "CLOSE" -> {
                TradeSignal signal = TradeSignal.builder()
                        .symbol(symbol)
                        .signalType(TradeSignal.SignalType.CLOSE)
                        .closeRatio(request.getCloseRatio())
                        .newStopLoss(request.getNewStopLoss())
                        .newTakeProfit(request.getNewTakeProfit())
                        .build();

                List<OrderResult> results = executeClose(signal);
                boolean ok = !results.isEmpty() && results.get(0).isSuccess();
                if (!ok) {
                    String msg = results.isEmpty() ? "CLOSE 失敗"
                            : results.get(0).getErrorMessage();
                    throw new RuntimeException(msg + ": " + symbol);
                }
                broadcastResults = results;
            }
            case "MOVE_SL" -> {
                TradeSignal signal = TradeSignal.builder()
                        .symbol(symbol)
                        .signalType(TradeSignal.SignalType.MOVE_SL)
                        .newStopLoss(request.getNewStopLoss())
                        .newTakeProfit(request.getNewTakeProfit())
                        .build();

                List<OrderResult> results = executeMoveSL(signal);
                boolean ok = results.stream().allMatch(OrderResult::isSuccess);
                if (!ok) {
                    throw new RuntimeException("MOVE_SL 失敗: " + symbol);
                }
                broadcastResults = results;
            }
            case "CANCEL" -> {
                if (deduplicationService.isCancelDuplicate(symbol, userId)) {
                    log.warn("廣播跟單: 重複取消跳過 userId={} symbol={}", userId, symbol);
                    return List.of();
                }
                ReentrantLock cancelLock = symbolLockRegistry.getLock(symbol);
                cancelLock.lock();
                try {
                    cancelAllOrders(symbol);
                    try {
                        tradeRecordService.recordCancel(symbol, userId);
                    } catch (Exception e) {
                        log.error("取消紀錄寫入失敗（不影響實際取消結果）: {}", e.getMessage());
                    }
                } finally {
                    cancelLock.unlock();
                }
            }
            default -> throw new IllegalArgumentException("不支援的 action: " + action);
        }

        log.info("廣播跟單完成: userId={} action={} symbol={}", userId, action, symbol);
        return broadcastResults;
        } finally {
            // 一定要清除 ThreadLocal，避免線程池復用時 key 洩漏給其他用戶
            CURRENT_USER_KEYS.remove();
            binanceAdapter.clearCredentials();
            TradeRecordService.clearCurrentUserId();
            TradeRecordService.clearCurrentUserDisplayName();
            TradingOrchestrator.clearBroadcastContext();
        }
    }
}
