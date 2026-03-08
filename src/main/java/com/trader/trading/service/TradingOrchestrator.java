package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.notification.service.NotificationService;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeSignal;
import com.trader.shared.service.MetricsService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.exchange.ExchangeAdapter;
import com.trader.trading.model.TradeContext;
import com.trader.trading.validation.TradeSignalValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 交易所無關的業務邏輯協調器
 *
 * 從 BinanceFuturesService 抽取的核心交易邏輯：
 * - executeSignal()    風控/以損定倉/DCA/fail-safe
 * - executeClose()     分批平倉/SL重掛
 * - executeMoveSL()    移動止損/成本保護
 *
 * 所有交易所 API 操作通過 ExchangeAdapter 介面呼叫，
 * 不依賴任何特定交易所（Binance/Bybit）的概念。
 *
 * Phase 2 重構：從 BinanceFuturesService 搬移業務邏輯。
 */
@Slf4j
@Service
public class TradingOrchestrator {

    private final TradeRecordService tradeRecordService;
    private final SignalDeduplicationService deduplicationService;
    private final NotificationService notificationService;
    private final MultiUserConfig multiUserConfig;
    private final ObjectMapper objectMapper;
    private final SymbolLockRegistry symbolLockRegistry;
    private final TradeConfigResolver tradeConfigResolver;
    private final StartOfDayBalanceCache startOfDayBalanceCache;
    private final TradeSignalValidator tradeSignalValidator;
    private final MetricsService metricsService;  // nullable in tests

    public TradingOrchestrator(TradeRecordService tradeRecordService,
                                SignalDeduplicationService deduplicationService,
                                NotificationService notificationService,
                                MultiUserConfig multiUserConfig,
                                ObjectMapper objectMapper,
                                SymbolLockRegistry symbolLockRegistry,
                                TradeConfigResolver tradeConfigResolver,
                                StartOfDayBalanceCache startOfDayBalanceCache,
                                TradeSignalValidator tradeSignalValidator,
                                @Autowired(required = false) MetricsService metricsService) {
        this.tradeRecordService = tradeRecordService;
        this.deduplicationService = deduplicationService;
        this.notificationService = notificationService;
        this.multiUserConfig = multiUserConfig;
        this.objectMapper = objectMapper;
        this.symbolLockRegistry = symbolLockRegistry;
        this.tradeConfigResolver = tradeConfigResolver;
        this.startOfDayBalanceCache = startOfDayBalanceCache;
        this.tradeSignalValidator = tradeSignalValidator;
        this.metricsService = metricsService;
    }

    // ==================== Public API ====================

    /**
     * ENTRY: 以損定倉開倉
     */
    public List<OrderResult> executeSignal(TradeSignal signal, ExchangeAdapter adapter, TradeContext ctx) {
        Optional<String> validationError = tradeSignalValidator.validate(signal);
        if (validationError.isPresent()) {
            log.warn("訊號驗證失敗: {}", validationError.get());
            return List.of(OrderResult.fail("訊號驗證失敗: " + validationError.get()));
        }

        if (metricsService != null) {
            metricsService.recordSignal(signal.getSignalType().name());
        }

        ReentrantLock lock = symbolLockRegistry.getLock(signal.getSymbol());
        lock.lock();
        try {
            return executeSignalInternal(signal, adapter, ctx);
        } catch (RuntimeException e) {
            log.error("交易前置檢查失敗，拒絕執行: {}", e.getMessage());
            return List.of(OrderResult.fail("前置檢查失敗: " + e.getMessage()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * CLOSE: 分批平倉
     */
    public List<OrderResult> executeClose(TradeSignal signal, ExchangeAdapter adapter, TradeContext ctx) {
        String symbol = signal.getSymbol();
        ReentrantLock lock = symbolLockRegistry.getLock(symbol);
        lock.lock();
        try {
            return executeCloseInternal(signal, adapter, ctx);
        } catch (RuntimeException e) {
            log.error("平倉前置檢查失敗: {}", e.getMessage());
            return List.of(OrderResult.fail("平倉失敗: " + e.getMessage()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * MOVE_SL: 移動止損/止盈
     */
    public List<OrderResult> executeMoveSL(TradeSignal signal, ExchangeAdapter adapter, TradeContext ctx) {
        String symbol = signal.getSymbol();
        ReentrantLock lock = symbolLockRegistry.getLock(symbol);
        lock.lock();
        try {
            return executeMoveSLInternal(signal, adapter, ctx);
        } catch (RuntimeException e) {
            log.error("修改 TP/SL 失敗: {}", e.getMessage());
            return List.of(OrderResult.fail("修改 TP/SL 失敗: " + e.getMessage()));
        } finally {
            lock.unlock();
        }
    }

    // ==================== executeSignal 內部邏輯 ====================

    private List<OrderResult> executeSignalInternal(TradeSignal signal, ExchangeAdapter adapter, TradeContext ctx) {
        String symbol = signal.getSymbol();

        EffectiveTradeConfig config = tradeConfigResolver.resolve(ctx.userId());

        // 1. 交易對白名單檢查
        if (!config.isSymbolAllowed(symbol)) {
            log.warn("交易對不在白名單: {}, 允許清單: {}", symbol, config.allowedSymbols());
            return List.of(OrderResult.fail("交易對不在白名單: " + symbol + ", 允許: " + config.allowedSymbols()));
        }

        // 1b. 查帳戶餘額
        double balance = adapter.getAvailableBalance();
        double riskAmount = balance * config.riskPercent();
        log.info("帳戶餘額: {} USDT, 1R = {} USDT ({}%)", balance, riskAmount, config.riskPercent() * 100);

        // 1c. 每日虧損熔斷
        double sodBalance = startOfDayBalanceCache.getOrCompute(ctx.userId(), () -> balance);
        double todayLoss = tradeRecordService.getTodayRealizedLoss(ctx.userId());
        double maxDailyLoss = config.effectiveDailyLossLimit(sodBalance);
        if (maxDailyLoss > 0 && Math.abs(todayLoss) >= maxDailyLoss) {
            String msg = String.format("每日虧損熔斷! 今日已虧損 %.2f USDT，上限 %.2f USDT (SOD=%.0f × %.0f%% cap %.0f)",
                    todayLoss, maxDailyLoss, sodBalance, config.dailyLossPercent() * 100, config.maxDailyLossUsdt());
            log.error(msg);
            notifyGlobal("🚨 每日虧損熔斷", msg, NotificationService.COLOR_RED, ctx);
            return List.of(OrderResult.fail("每日虧損已達上限，暫停交易"));
        }

        // 2. 持倉限制檢查 + DCA 補倉邏輯
        double currentPosition = adapter.getCurrentPositionAmount(symbol);
        if (currentPosition != 0) {
            if (signal.isDca()) {
                int dcaCount = tradeRecordService.getDcaCount(symbol, ctx.userId());
                int maxDca = config.maxDcaPerSymbol();
                if (dcaCount >= maxDca - 1) {
                    log.warn("DCA 已達上限: {} 已補倉 {} 次，上限 {} 層", symbol, dcaCount, maxDca);
                    return List.of(OrderResult.fail("DCA 已達上限: " + symbol + " 已 " + (dcaCount + 1) + "/" + maxDca + " 層"));
                }

                boolean isCurrentLong = currentPosition > 0;
                if (signal.getSide() == null) {
                    signal.setSide(isCurrentLong ? TradeSignal.Side.LONG : TradeSignal.Side.SHORT);
                    log.info("DCA 自動推斷方向: {} (持倉 {} BTC)", signal.getSide(), currentPosition);
                }
                boolean isSignalLong = signal.getSide() == TradeSignal.Side.LONG;
                if (isCurrentLong != isSignalLong) {
                    log.warn("DCA 方向不一致: 持倉={}, 訊號={}", isCurrentLong ? "LONG" : "SHORT", signal.getSide());
                    return List.of(OrderResult.fail("DCA 補倉方向與現有持倉不一致"));
                }

                log.info("DCA 補倉允許: {} 目前第 {} 層，上限 {} 層", symbol, dcaCount + 1, maxDca);
            } else {
                log.warn("已有持倉 {} BTC，拒絕新開倉（如需補倉請使用 DCA）", currentPosition);
                return List.of(OrderResult.fail("已有持倉，拒絕新開倉（如需補倉請使用 is_dca=true）"));
            }
        } else if (signal.isDca()) {
            log.warn("DCA 補倉但 {} 沒有持倉，拒絕執行", symbol);
            return List.of(OrderResult.fail("DCA 補倉失敗: " + symbol + " 目前沒有持倉，無法補倉"));
        }

        // 2b. 檢查未成交入場掛單
        if (!signal.isDca() && adapter.hasOpenEntryOrders(symbol)) {
            log.warn("已有未成交的入場掛單，拒絕重複下單");
            return List.of(OrderResult.fail("已有未成交的入場掛單，拒絕重複下單"));
        }

        // 2c. 重複訊號防護
        if (deduplicationService.isUserDuplicate(signal, ctx.userId())) {
            log.warn("重複訊號，拒絕執行: userId={} {} {} entry={} SL={}",
                    ctx.userId(), symbol, signal.getSide(), signal.getEntryPriceLow(), signal.getStopLoss());
            return List.of(OrderResult.fail("重複訊號，5分鐘內已收到相同訊號"));
        }

        // 3. 驗證止損
        if (signal.getStopLoss() == 0 && !signal.isDca()) {
            log.warn("ENTRY 訊號缺少止損");
            return List.of(OrderResult.fail("ENTRY 訊號必須包含 stop_loss"));
        }

        // 4. 方向邏輯驗證
        double entry = signal.getEntryPriceLow();
        double sl = signal.getStopLoss();
        if (!signal.isDca() && signal.getSide() == TradeSignal.Side.LONG && sl >= entry) {
            return List.of(OrderResult.fail("做多止損不應高於入場價"));
        }
        if (!signal.isDca() && signal.getSide() == TradeSignal.Side.SHORT && sl <= entry) {
            return List.of(OrderResult.fail("做空止損不應低於入場價"));
        }

        // 5. 價格偏離檢查
        double markPrice = adapter.getMarkPrice(symbol);
        if (markPrice <= 0) {
            return List.of(OrderResult.fail("無法取得市價，拒絕交易"));
        }
        double deviation = Math.abs(entry - markPrice) / markPrice;
        if (deviation > 0.10) {
            log.warn("入場價 {} 偏離市價 {} 超過 10% ({}%)", entry, markPrice, String.format("%.1f", deviation * 100));
            return List.of(OrderResult.fail("入場價偏離市價超過 10%"));
        }

        int leverage = config.fixedLeverage();

        // 6. 設定逐倉 + 槓桿
        try {
            adapter.setMarginType(symbol, "ISOLATED");
        } catch (Exception e) {
            log.info("設定保證金模式: {}", e.getMessage());
        }
        adapter.setLeverage(symbol, leverage);

        // 7. 動態以損定倉
        double riskMultiplier = signal.isDca() ? config.dcaRiskMultiplier() : 1.0;
        double effectiveRiskAmount = riskAmount * riskMultiplier;

        double effectiveSl = sl;
        if (signal.isDca() && sl == 0) {
            Double existingSl = tradeRecordService.findOpenTrade(symbol, ctx.userId())
                    .map(Trade::getStopLoss).orElse(null);
            if (existingSl != null && existingSl > 0) {
                effectiveSl = existingSl;
                log.info("DCA 倉位計算: 使用現有 SL {} 計算風險距離", effectiveSl);
            } else {
                log.warn("DCA 無法取得現有 SL，使用入場價 5% 作為風險距離估算");
                effectiveSl = signal.getSide() == TradeSignal.Side.LONG
                        ? entry * 0.95 : entry * 1.05;
            }
        }

        double riskDistance = Math.abs(entry - effectiveSl);
        double quantity = effectiveRiskAmount / riskDistance;
        if (signal.isDca()) {
            log.info("DCA 倉位計算: {}R = {} × {} = {} USDT", riskMultiplier, riskAmount, riskMultiplier, effectiveRiskAmount);
        }

        // 7b. 名目價值上限 cap
        double notional = entry * quantity;
        double maxNotional = config.effectiveMaxPosition(balance);
        if (maxNotional > 0 && notional > maxNotional) {
            double cappedQty = maxNotional / entry;
            log.warn("倉位 cap 觸發: 原始數量={} (名目 {} USDT), 上限數量={} (名目 {} USDT)",
                    quantity, notional, cappedQty, maxNotional);
            quantity = cappedQty;
        }

        // 7c. 保證金充足性檢查
        double requiredMargin = entry * quantity / leverage;
        double maxMargin = balance * 0.90;
        if (requiredMargin > maxMargin) {
            double marginCappedQty = maxMargin * leverage / entry;
            log.warn("保證金不足 cap: 需要 {} USDT，可用 {} USDT (90%), 數量 {} → {}",
                    requiredMargin, maxMargin, quantity, marginCappedQty);
            quantity = marginCappedQty;
        }

        // 7d. 最低下單量檢查
        double minNotional = 5.0;
        if (entry * quantity < minNotional) {
            String msg = String.format("倉位太小: 名目 %.2f USDT < 最低 %.0f USDT (餘額 %.2f, 1R=%.2f)",
                    entry * quantity, minNotional, balance, riskAmount);
            log.warn(msg);
            return List.of(OrderResult.fail("餘額不足，計算出的倉位低於最低下單量"));
        }

        log.info("以損定倉: 餘額={}, 1R={}, 實際風險={}(×{}), 風險距離={}, 數量={}, 名目={} USDT, 保證金={} USDT",
                balance, riskAmount, effectiveRiskAmount, riskMultiplier,
                riskDistance, quantity, entry * quantity, entry * quantity / leverage);

        String entrySide = signal.getSide() == TradeSignal.Side.SHORT ? "SELL" : "BUY";
        String closeSide = signal.getSide() == TradeSignal.Side.SHORT ? "BUY" : "SELL";

        // 8. 掛 LIMIT 入場單
        OrderResult entryOrder = adapter.placeLimitOrder(symbol, entrySide, entry, quantity);
        if (!entryOrder.isSuccess()) {
            log.error("入場單失敗: {}", entryOrder.getErrorMessage());
            tradeRecordService.recordOrderEvent(symbol, "ENTRY_FAILED", entryOrder, null, ctx.userId());
            return List.of(entryOrder);
        }
        entryOrder.setRiskSummary(String.format("餘額: %.2f | %s: %.2f (%.0f%%×%.0f) | 保證金: %.2f",
                balance, signal.isDca() ? "DCA風險" : "1R",
                effectiveRiskAmount, config.riskPercent() * 100, riskMultiplier,
                entry * quantity / leverage));

        // === DCA 補倉 SL/TP 處理 ===
        OrderResult slOrder;
        OrderResult tpOrder = null;

        if (signal.isDca()) {
            adapter.cancelSLTPOrders(symbol);

            double totalQty = Math.abs(currentPosition) + quantity;
            log.info("DCA SL/TP 重掛: 舊持倉={}, 新掛單={}, 總數量={}", Math.abs(currentPosition), quantity, totalQty);

            try {
                if (signal.getNewStopLoss() != null) {
                    slOrder = adapter.setStopLoss(symbol, closeSide, signal.getNewStopLoss(), totalQty);
                } else {
                    Double existingSl2 = tradeRecordService.findOpenTrade(symbol, ctx.userId())
                            .map(Trade::getStopLoss).orElse(null);
                    if (existingSl2 != null) {
                        slOrder = adapter.setStopLoss(symbol, closeSide, existingSl2, totalQty);
                        log.info("DCA 止損不變，用現有 SL {} 重掛（數量更新為 {}）", existingSl2, totalQty);
                    } else {
                        log.warn("DCA 無法找到現有 SL，跳過 SL 掛單");
                        slOrder = OrderResult.fail("DCA 無現有 SL 可用");
                    }
                }
            } catch (RuntimeException e) {
                log.error("DCA SL 下單異常（網路重試全部失敗）: {}", e.getMessage());
                slOrder = OrderResult.fail("DCA SL 網路異常: " + e.getMessage());
            }

            if (signal.getNewTakeProfit() != null && signal.getNewTakeProfit() > 0) {
                tpOrder = adapter.setTakeProfit(symbol, closeSide, signal.getNewTakeProfit(), totalQty);
                if (!tpOrder.isSuccess()) {
                    log.warn("DCA 止盈單失敗（不影響入場和止損）: {}", tpOrder.getErrorMessage());
                    tradeRecordService.recordOrderEvent(symbol, "TP_FAILED", tpOrder,
                            toJson(Map.of("context", "DCA")), ctx.userId());
                }
            }
        } else {
            // 正常入場: SL/TP 按入場數量掛
            try {
                slOrder = adapter.setStopLoss(symbol, closeSide, sl, quantity);
            } catch (RuntimeException e) {
                log.error("SL 下單異常（網路重試全部失敗）: {}", e.getMessage());
                slOrder = OrderResult.fail("SL 網路異常: " + e.getMessage());
            }

            if (signal.getTakeProfits() != null && !signal.getTakeProfits().isEmpty()) {
                double tp = signal.getTakeProfits().get(0);
                tpOrder = adapter.setTakeProfit(symbol, closeSide, tp, quantity);
                if (!tpOrder.isSuccess()) {
                    log.warn("止盈單失敗（不影響入場和止損）: {}", tpOrder.getErrorMessage());
                    tradeRecordService.recordOrderEvent(symbol, "TP_FAILED", tpOrder, null, ctx.userId());
                    notifyGlobal(
                            "⚠️ 止盈單失敗（需手動設定）",
                            String.format("%s %s\n入場和止損已正常設定\n止盈錯誤: %s\n請手動設定 TP",
                                    symbol, signal.getSide(), tpOrder.getErrorMessage()),
                            NotificationService.COLOR_YELLOW, ctx);
                } else {
                    tradeRecordService.recordOrderEvent(symbol, "TP_PLACED", tpOrder, null, ctx.userId());
                }
            }
        }

        // 11. Fail-Safe: SL 掛失敗 → 取消入場單
        if (!slOrder.isSuccess()) {
            log.error("止損單失敗! 觸發 Fail-Safe，取消入場單");
            tradeRecordService.recordFailSafe(symbol,
                    toJson(Map.of("reason", "SL下單失敗", "sl_error", slOrder.getErrorMessage() != null ? slOrder.getErrorMessage() : "")), ctx.userId());
            try {
                adapter.cancelOrder(symbol, entryOrder.getOrderId());
                log.info("Fail-Safe: 已取消入場單 {}", entryOrder.getOrderId());
                notifyGlobal("🛑 Fail-Safe: 止損失敗，入場單已取消",
                        String.format("%s %s\n止損掛單失敗: %s\n入場單 %s 已自動取消\n⚠️ 此筆交易未成立",
                                symbol, signal.getSide(),
                                slOrder.getErrorMessage() != null ? slOrder.getErrorMessage() : "unknown",
                                entryOrder.getOrderId()),
                        NotificationService.COLOR_RED, ctx);
            } catch (Exception e) {
                log.error("Fail-Safe: 取消入場單失敗，嘗試市價平倉", e);
                OrderResult marketClose = adapter.placeMarketOrder(symbol, closeSide, quantity);
                if (!marketClose.isSuccess()) {
                    String alert = String.format("CRITICAL: %s 止損單+取消單+市價平倉全部失敗! 請立即手動處理! 數量=%s",
                            symbol, adapter.formatQuantity(symbol, quantity));
                    log.error(alert);
                    notifyGlobal("🚨 Fail-Safe 全部失敗",
                            alert, NotificationService.COLOR_RED, ctx);
                    tradeRecordService.recordFailSafe(symbol,
                            toJson(Map.of("reason", "所有自動保護措施失敗", "market_close_error", marketClose.getErrorMessage() != null ? marketClose.getErrorMessage() : "")), ctx.userId());
                } else {
                    tradeRecordService.recordOrderEvent(symbol, "FAIL_SAFE_CLOSE", marketClose,
                            toJson(Map.of("reason", "SL失敗+取消失敗，已市價平倉")), ctx.userId());
                    notifyGlobal("🛑 Fail-Safe: 止損失敗，已市價平倉",
                            String.format("%s %s\n止損掛單失敗: %s\n取消入場單也失敗，已市價平倉 %s\n⚠️ 請確認帳戶狀態",
                                    symbol, signal.getSide(),
                                    slOrder.getErrorMessage() != null ? slOrder.getErrorMessage() : "unknown",
                                    adapter.formatQuantity(symbol, quantity)),
                            NotificationService.COLOR_RED, ctx);
                }
            }
            entryOrder.setSuccess(false);
            entryOrder.setErrorMessage("Fail-Safe 觸發: 止損掛單失敗，入場單已取消");
            return List.of(entryOrder, slOrder);
        }

        // 12. 記錄交易到資料庫
        try {
            if (signal.isDca()) {
                tradeRecordService.recordDcaEntry(symbol, signal, entryOrder, effectiveRiskAmount, ctx.userId());
            } else {
                String signalHash = deduplicationService.generateHash(signal);
                tradeRecordService.recordEntry(signal, entryOrder, slOrder, leverage,
                        effectiveRiskAmount, signalHash, ctx.userId());
            }
        } catch (Exception e) {
            log.error("🚨 交易紀錄寫入失敗（交易所已有倉位但 DB 無紀錄）: {} {} - {}",
                    symbol, signal.getSide(), e.getMessage(), e);
            notifyGlobal("🚨 DB 寫入失敗 — 隱形倉位風險",
                    String.format("%s %s\n入場價: %s | SL: %s\n數量: %s | 槓桿: %dx\n" +
                                    "原因: %s\n⚠️ 交易所有倉位但 DB 無紀錄，請立即手動確認！",
                            symbol, signal.getSide(),
                            entryOrder.getPrice(), sl,
                            adapter.formatQuantity(symbol, quantity), leverage,
                            e.getMessage()),
                    NotificationService.COLOR_RED, ctx);
        }

        List<OrderResult> results = new ArrayList<>();
        results.add(entryOrder);
        results.add(slOrder);
        if (tpOrder != null) {
            results.add(tpOrder);
        }

        String tpInfo = (signal.getTakeProfits() != null && !signal.getTakeProfits().isEmpty())
                ? " TP=" + signal.getTakeProfits().get(0) : "";
        log.info("ENTRY 完成: {} {} qty={} entry={} SL={}{} 槓桿={}x ISOLATED",
                symbol, signal.getSide(), String.format("%.3f", quantity), entry, sl, tpInfo, leverage);

        return results;
    }

    // ==================== executeClose 內部邏輯 ====================

    private List<OrderResult> executeCloseInternal(TradeSignal signal, ExchangeAdapter adapter, TradeContext ctx) {
        String symbol = signal.getSymbol();

        double positionAmt;
        try {
            positionAmt = adapter.getCurrentPositionAmount(symbol);
        } catch (RuntimeException e) {
            log.error("平倉前查詢持倉失敗: {}", e.getMessage());
            return List.of(OrderResult.fail("查詢持倉失敗: " + e.getMessage()));
        }

        // 1b. Symbol fallback
        if (positionAmt == 0) {
            String resolved = resolveSymbolFallback(symbol, ctx);
            if (resolved != null) {
                log.info("平倉 symbol fallback: {} 無持倉，改用 DB OPEN trade: {}", symbol, resolved);
                signal = TradeSignal.builder()
                        .symbol(resolved)
                        .signalType(signal.getSignalType())
                        .closeRatio(signal.getCloseRatio())
                        .newStopLoss(signal.getNewStopLoss())
                        .newTakeProfit(signal.getNewTakeProfit())
                        .build();
                symbol = resolved;
                try {
                    positionAmt = adapter.getCurrentPositionAmount(symbol);
                } catch (RuntimeException e) {
                    return List.of(OrderResult.fail("查詢持倉失敗: " + e.getMessage()));
                }
            }
            if (positionAmt == 0) {
                boolean hadPendingOrders = adapter.hasOpenEntryOrders(symbol);
                adapter.cancelAllOrders(symbol);
                try {
                    tradeRecordService.recordCancel(symbol, ctx.userId());
                } catch (Exception e) {
                    log.warn("取消紀錄寫入失敗: {}", e.getMessage());
                }

                if (hadPendingOrders) {
                    log.info("CLOSE 訊號: {} 未成交委託已撤銷，無需平倉", symbol);
                    if (!ctx.broadcastMode()) {
                        notifyGlobal(
                                "📋 CLOSE — 未成交委託已撤銷",
                                String.format("%s\n入場委託未成交，已撤銷所有掛單（入場/SL/TP）", symbol),
                                NotificationService.COLOR_YELLOW, ctx);
                    }
                    return List.of(OrderResult.builder()
                            .success(true)
                            .symbol(symbol)
                            .errorMessage("未成交委託已撤銷，無需平倉")
                            .build());
                } else {
                    log.warn("CLOSE 訊號: {} 無持倉也無掛單，忽略", symbol);
                    if (!ctx.broadcastMode()) {
                        notifyGlobal(
                                "ℹ️ CLOSE — 無持倉也無掛單",
                                String.format("%s\n訊號要求平倉，但無持倉也無未成交掛單", symbol),
                                NotificationService.COLOR_YELLOW, ctx);
                    }
                    return List.of(OrderResult.fail("無持倉也無掛單，CLOSE 訊號忽略"));
                }
            }
        }

        boolean isLong = positionAmt > 0;
        double absPosition = Math.abs(positionAmt);

        double closeRatio = signal.getCloseRatio() != null ? signal.getCloseRatio() : 1.0;
        double closeQty = absPosition * closeRatio;
        boolean isPartialClose = closeRatio < 1.0;

        log.info("平倉: {} 持倉={} ratio={} 平倉數量={} partial={}",
                symbol, positionAmt, closeRatio, closeQty, isPartialClose);

        // 3. 部分平倉前：先查詢現有 SL/TP 價格
        double oldSlPrice = 0;
        double oldTpPrice = 0;
        if (isPartialClose) {
            double[] prices = adapter.getCurrentSLTPPrices(symbol);
            oldSlPrice = prices[0];
            oldTpPrice = prices[1];
            log.info("部分平倉: 保存舊 SL={} TP={} 用於重掛", oldSlPrice, oldTpPrice);
        }

        adapter.cancelAllOrders(symbol);

        double markPrice;
        try {
            markPrice = adapter.getMarkPrice(symbol);
        } catch (RuntimeException e) {
            log.error("平倉前取得市價失敗: {}", e.getMessage());
            return List.of(OrderResult.fail("取得市價失敗: " + e.getMessage()));
        }
        if (markPrice <= 0) {
            return List.of(OrderResult.fail("無法取得市價"));
        }

        String closeSide = isLong ? "SELL" : "BUY";

        OrderResult closeOrder;
        if (isPartialClose) {
            double closePrice = isLong ? markPrice * 0.999 : markPrice * 1.001;
            closeOrder = adapter.placeLimitOrder(symbol, closeSide, closePrice, closeQty);
        } else {
            closeOrder = adapter.placeMarketOrder(symbol, closeSide, closeQty);
        }

        if (closeOrder.isSuccess()) {
            try {
                if (isPartialClose) {
                    tradeRecordService.recordPartialClose(symbol, closeOrder, closeRatio, "SIGNAL_CLOSE", ctx.userId());
                    Double entryPrice = tradeRecordService.getEntryPrice(symbol, ctx.userId());
                    if (entryPrice != null && closeOrder.getPrice() > 0) {
                        double pnl = isLong
                                ? (closeOrder.getPrice() - entryPrice) * closeOrder.getQuantity()
                                : (entryPrice - closeOrder.getPrice()) * closeOrder.getQuantity();
                        closeOrder.setNetProfit(pnl);
                    }
                } else {
                    Trade closedTrade = tradeRecordService.recordClose(symbol, closeOrder, "SIGNAL_CLOSE", ctx.userId());
                    if (closedTrade != null) {
                        closeOrder.setNetProfit(closedTrade.getNetProfit());
                        closeOrder.setTotalCommission(closedTrade.getCommission());
                    }
                }
            } catch (Exception e) {
                log.error("平倉紀錄寫入失敗（不影響交易）: {}", e.getMessage());
            }
        } else {
            tradeRecordService.recordOrderEvent(symbol, "CLOSE_FAILED", closeOrder, null, ctx.userId());
        }

        List<OrderResult> results = new ArrayList<>();
        results.add(closeOrder);

        // 6. 部分平倉：一定要重掛 SL
        if (isPartialClose) {
            double remainingQty = absPosition - closeQty;
            String slSide = isLong ? "SELL" : "BUY";

            double slToUse;
            if (signal.getNewStopLoss() != null && signal.getNewStopLoss() > 0) {
                slToUse = signal.getNewStopLoss();
                log.info("部分平倉: 使用訊號指定 SL={}", slToUse);
            } else if (signal.getNewStopLoss() == null && signal.getNewTakeProfit() == null
                    && oldSlPrice == 0) {
                Double entryPrice = tradeRecordService.getEntryPrice(symbol, ctx.userId());
                if (entryPrice != null && entryPrice > 0) {
                    slToUse = entryPrice;
                    log.info("部分平倉: 無 SL 資訊，使用開倉價做成本保護 SL={}", slToUse);
                } else {
                    slToUse = 0;
                    log.warn("部分平倉: ⚠️ 無法取得 SL 價格，剩餘倉位無止損保護！");
                }
            } else if (oldSlPrice > 0) {
                slToUse = oldSlPrice;
                log.info("部分平倉: 使用原有 SL={}", slToUse);
            } else {
                Double entryPrice = tradeRecordService.getEntryPrice(symbol, ctx.userId());
                if (entryPrice != null && entryPrice > 0) {
                    slToUse = entryPrice;
                    log.info("部分平倉: 成本保護，SL 移至開倉價={}", slToUse);
                } else {
                    slToUse = oldSlPrice;
                    log.warn("部分平倉: 成本保護但無開倉價，用舊 SL={}", slToUse);
                }
            }

            if (slToUse > 0) {
                OrderResult newSl = adapter.setStopLoss(symbol, slSide, slToUse, remainingQty);
                results.add(newSl);
                tradeRecordService.recordOrderEvent(symbol,
                        newSl.isSuccess() ? "SL_REHUNG" : "SL_REHUNG_FAILED", newSl,
                        toJson(Map.of("sl_price", slToUse, "remaining_qty", remainingQty)), ctx.userId());
                if (!newSl.isSuccess()) {
                    notifyGlobal(
                            "🚨 部分平倉後 SL 重掛 API 失敗",
                            String.format("%s SL=%.2f 重掛失敗！剩餘倉位 %.4f 無保護\n原因: %s\n請立即手動設定 SL",
                                    symbol, slToUse, remainingQty,
                                    newSl.getErrorMessage() != null ? newSl.getErrorMessage() : "unknown"),
                            NotificationService.COLOR_RED, ctx);
                }
            } else {
                log.error("⚠️ 部分平倉後未能重掛 SL！{} 剩餘 {} 裸奔中", symbol, remainingQty);
                tradeRecordService.recordOrderEvent(symbol, "SL_REHUNG_FAILED", null,
                        toJson(Map.of("reason", "no_sl_price", "remaining_qty", remainingQty)), ctx.userId());
                results.add(OrderResult.fail("部分平倉後無法重掛 SL — 剩餘倉位無保護"));
                notifyGlobal(
                        "🚨 部分平倉後 SL 重掛失敗",
                        String.format("%s 剩餘倉位 %.4f 無止損保護！\n請立即手動設定 SL",
                                symbol, remainingQty),
                        NotificationService.COLOR_RED, ctx);
            }

            // TP 重掛
            double tpToUse = 0;
            if (signal.getNewTakeProfit() != null && signal.getNewTakeProfit() > 0) {
                tpToUse = signal.getNewTakeProfit();
            } else if (oldTpPrice > 0) {
                tpToUse = oldTpPrice;
                log.info("部分平倉: 使用原有 TP={}", tpToUse);
            }

            if (tpToUse > 0) {
                OrderResult newTp = adapter.setTakeProfit(symbol, slSide, tpToUse, remainingQty);
                results.add(newTp);
                tradeRecordService.recordOrderEvent(symbol,
                        newTp.isSuccess() ? "TP_REHUNG" : "TP_REHUNG_FAILED", newTp,
                        toJson(Map.of("tp_price", tpToUse, "remaining_qty", remainingQty)), ctx.userId());
            }
        }

        return results;
    }

    // ==================== executeMoveSL 內部邏輯 ====================

    private List<OrderResult> executeMoveSLInternal(TradeSignal signal, ExchangeAdapter adapter, TradeContext ctx) {
        String symbol = signal.getSymbol();

        double positionAmt;
        try {
            positionAmt = adapter.getCurrentPositionAmount(symbol);
        } catch (RuntimeException e) {
            log.error("修改 TP/SL 前查詢持倉失敗: {}", e.getMessage());
            return List.of(OrderResult.fail("查詢持倉失敗: " + e.getMessage()));
        }

        if (positionAmt == 0) {
            String resolved = resolveSymbolFallback(symbol, ctx);
            if (resolved != null) {
                log.info("MOVE_SL symbol fallback: {} 無持倉，改用 DB OPEN trade: {}", symbol, resolved);
                signal = TradeSignal.builder()
                        .symbol(resolved)
                        .signalType(signal.getSignalType())
                        .newStopLoss(signal.getNewStopLoss())
                        .newTakeProfit(signal.getNewTakeProfit())
                        .build();
                symbol = resolved;
                try {
                    positionAmt = adapter.getCurrentPositionAmount(symbol);
                } catch (RuntimeException e) {
                    return List.of(OrderResult.fail("查詢持倉失敗: " + e.getMessage()));
                }
            }
            if (positionAmt == 0) {
                return List.of(OrderResult.fail("無持倉，無法修改 TP/SL"));
            }
        }

        boolean isLong = positionAmt > 0;
        double absPosition = Math.abs(positionAmt);
        String closeSide = isLong ? "SELL" : "BUY";

        adapter.cancelAllOrders(symbol);

        double oldSl = tradeRecordService.findOpenTrade(symbol, ctx.userId())
                .map(t -> t.getStopLoss() != null ? t.getStopLoss() : 0.0)
                .orElse(0.0);

        List<OrderResult> results = new ArrayList<>();

        Double slValue = signal.getNewStopLoss();
        if (slValue != null && slValue > 0) {
            log.info("移動止損: {} 舊SL={} 新SL={} 持倉={}", symbol, oldSl, slValue, positionAmt);
        } else {
            Double entryPrice = tradeRecordService.getEntryPrice(symbol, ctx.userId());
            if (entryPrice != null && entryPrice > 0) {
                slValue = entryPrice;
                log.info("成本保護: {} 舊SL={} 用開倉價做SL={} 持倉={}", symbol, oldSl, slValue, positionAmt);
            } else {
                log.warn("成本保護但無法取得開倉價: {} 舊SL={}", symbol, oldSl);
                if (oldSl > 0) {
                    slValue = oldSl;
                    log.info("成本保護 fallback: 用舊 SL={} 重掛", slValue);
                }
            }
        }

        if (slValue != null && slValue > 0) {
            double newSl = slValue;
            OrderResult slOrder = adapter.setStopLoss(symbol, closeSide, newSl, absPosition);
            results.add(slOrder);

            if (slOrder.isSuccess()) {
                try {
                    tradeRecordService.recordMoveSL(symbol, slOrder, oldSl, newSl, ctx.userId());
                } catch (Exception e) {
                    log.error("移動止損紀錄寫入失敗（不影響交易）: {}", e.getMessage());
                }
            } else {
                tradeRecordService.recordOrderEvent(symbol, "MOVE_SL_FAILED", slOrder,
                        toJson(Map.of("old_sl", oldSl, "new_sl", newSl)), ctx.userId());
                notifyGlobal(
                        "🚨 移動止損失敗 — 倉位無 SL 保護",
                        String.format("%s 舊SL=%.2f 已被取消，新SL=%.2f 掛單失敗！\n持倉 %.4f 無止損保護\n原因: %s\n請立即手動設定 SL",
                                symbol, oldSl, newSl, absPosition,
                                slOrder.getErrorMessage() != null ? slOrder.getErrorMessage() : "unknown"),
                        NotificationService.COLOR_RED, ctx);
            }
        }

        Double tpValue = signal.getNewTakeProfit();
        if (tpValue == null && signal.getTakeProfits() != null && !signal.getTakeProfits().isEmpty()) {
            tpValue = signal.getTakeProfits().get(0);
        }
        if (tpValue != null && tpValue > 0) {
            double newTp = tpValue;
            log.info("更新止盈: {} 新TP={} 持倉={}", symbol, newTp, positionAmt);

            OrderResult tpOrder = adapter.setTakeProfit(symbol, closeSide, newTp, absPosition);
            results.add(tpOrder);

            if (!tpOrder.isSuccess()) {
                log.warn("新止盈單失敗: {}", tpOrder.getErrorMessage());
                tradeRecordService.recordOrderEvent(symbol, "TP_FAILED", tpOrder,
                        toJson(Map.of("context", "MOVE_SL")), ctx.userId());
                notifyGlobal(
                        "⚠️ 新止盈單失敗（需手動設定）",
                        String.format("%s\n新TP設定失敗: %s\n請手動設定 TP",
                                symbol, tpOrder.getErrorMessage()),
                        NotificationService.COLOR_YELLOW, ctx);
            }
        }

        if (results.isEmpty()) {
            return List.of(OrderResult.fail("TP-SL 修改訊號缺少新的 TP 或 SL"));
        }

        return results;
    }

    // ==================== 內部工具方法 ====================

    /**
     * Symbol fallback：當訊號指定的 symbol 無持倉時，查 DB 找其他 OPEN trade
     */
    private String resolveSymbolFallback(String originalSymbol, TradeContext ctx) {
        try {
            var openTrades = tradeRecordService.findAllOpenTrades(ctx.userId());
            if (openTrades.size() == 1) {
                String dbSymbol = openTrades.get(0).getSymbol();
                if (!dbSymbol.equals(originalSymbol)) {
                    log.info("Symbol fallback: 訊號={} 但 DB 唯一 OPEN trade={}", originalSymbol, dbSymbol);
                    notifyGlobal(
                            "🔄 Symbol 自動修正",
                            String.format("訊號幣種: %s（無持倉）\n自動修正為: %s（DB 中唯一 OPEN trade）",
                                    originalSymbol, dbSymbol),
                            NotificationService.COLOR_BLUE, ctx);
                    return dbSymbol;
                }
            } else if (openTrades.size() > 1) {
                log.warn("Symbol fallback: {} 無持倉，但 DB 有 {} 筆 OPEN trade，無法自動判斷",
                        originalSymbol, openTrades.size());
            }
        } catch (Exception e) {
            log.warn("Symbol fallback 查詢失敗: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 三路通知（風控告警 / 系統級事件）
     */
    private void notifyGlobal(String title, String body, int color, TradeContext ctx) {
        String userId = ctx.userId();
        String displayName = ctx.effectiveDisplayName();

        if (multiUserConfig.isEnabled() && userId != null && !userId.isBlank()) {
            notificationService.sendNotificationToUser(userId, title, body, color);
            notificationService.sendNotificationToAdmins(displayName, title, body, color);
        } else {
            String enriched = (displayName != null && !displayName.isBlank())
                    ? "用戶: " + displayName + "\n" + body
                    : body;
            notificationService.sendNotification(title, enriched, color);
        }
    }

    /**
     * 安全地將 Map 轉為 JSON 字串
     */
    private String toJson(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            log.warn("JSON 序列化失敗: {}", e.getMessage());
            return "{}";
        }
    }
}
