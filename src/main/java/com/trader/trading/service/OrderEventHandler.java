package com.trader.trading.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.trading.entity.Trade;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 共用的 ORDER_TRADE_UPDATE / ALGO_UPDATE 事件處理邏輯
 *
 * 單用戶 (BinanceUserDataStreamService) 和多用戶 (MultiUserDataStreamManager)
 * 的 WebSocket Listener 都呼叫這裡，避免重複邏輯。
 *
 * 通知策略透過 NotificationSender 函式介面抽象：
 * - 單用戶版傳 sendNotification(title, msg, color)
 * - 多用戶版傳 sendNotificationToUser(userId, title, msg, color)
 *
 * Algo Order 遷移：
 * Binance 已將 STOP_MARKET/TAKE_PROFIT_MARKET 遷至 Algo Order API，
 * 觸發時先收到 ALGO_UPDATE (algoStatus=TRIGGERED)，再收到 ORDER_TRADE_UPDATE (type=MARKET)。
 * 用 pendingAlgoTriggers 暫存 ALGO_UPDATE 的 exitReason，供後續 MARKET fill 使用。
 */
@Slf4j
public class OrderEventHandler {

    private final TradeRecordService tradeRecordService;
    private final SymbolLockRegistry symbolLockRegistry;
    private final NotificationSender notificationSender;
    private final NotificationSender adminNotifier;  // nullable — 單用戶模式為 null
    private final Gson gson;
    private final String logPrefix;  // 日誌前綴：空字串 or "用戶 {userId} "
    private final String exchangeName;  // 交易所名稱（通知顯示用）：BINANCE / BYBIT

    /**
     * handleProtectionLost tryLock 等待上限（毫秒）
     * CANCEL 持鎖期間 WebSocket 收到 ALGO_UPDATE CANCELED 會嘗試 tryLock；
     * 超時代表 CANCEL 正在執行（會處理 trade 狀態），安全跳過。
     * package-private 供測試覆寫。
     */
    long protectionLostLockTimeoutMs = 3000;

    /**
     * Algo 觸發暫存資料：包含 exitReason、觸發後 MARKET 單 orderId、以及 symbol
     * 用 algoId 為 key 存放，確保同 symbol 多張 algo（SL + TP）互不干擾
     */
    record AlgoTriggerHint(String symbol, String exitReason, String triggeredOrderId) {}

    /**
     * Algo 觸發暫存：ALGO_UPDATE TRIGGERED → 存入 {algoId → AlgoTriggerHint}
     * ORDER_TRADE_UPDATE MARKET FILLED 時遍歷找 triggeredOrderId 精確匹配後 remove
     * 用 algoId 而非 symbol 作為 key，避免 TP TRIGGERED + SL CANCELED 的 race condition
     */
    private final ConcurrentHashMap<String, AlgoTriggerHint> pendingAlgoTriggers = new ConcurrentHashMap<>();

    /**
     * 通知發送介面 — 解耦全局 vs per-user webhook
     */
    @FunctionalInterface
    public interface NotificationSender {
        void send(String title, String message, int color);
    }

    public OrderEventHandler(TradeRecordService tradeRecordService,
                              SymbolLockRegistry symbolLockRegistry,
                              NotificationSender notificationSender,
                              NotificationSender adminNotifier,
                              Gson gson,
                              String logPrefix,
                              String exchangeName) {
        this.tradeRecordService = tradeRecordService;
        this.symbolLockRegistry = symbolLockRegistry;
        this.notificationSender = notificationSender;
        this.adminNotifier = adminNotifier;
        this.gson = gson;
        this.logPrefix = logPrefix != null ? logPrefix : "";
        this.exchangeName = exchangeName != null ? exchangeName : "";
    }

    /**
     * 處理 ORDER_TRADE_UPDATE 事件
     * - FILLED MARKET（由 Algo SL/TP 觸發）→ 透過 pendingAlgoTriggers 或 clientOrderId 前綴偵測 → 記錄平倉
     * - FILLED STOP_MARKET / TAKE_PROFIT_MARKET → 記錄平倉（legacy，遷移前舊單相容）
     * - CANCELED / EXPIRED 的 STOP_MARKET / TAKE_PROFIT_MARKET → 告警保護消失（legacy）
     * - PARTIALLY_FILLED 的 SL/TP → 告警 + 記錄事件（legacy）
     */
    public void handleOrderTradeUpdate(JsonObject event) {
        JsonObject order = event.getAsJsonObject("o");
        if (order == null) {
            log.warn("{}ORDER_TRADE_UPDATE missing 'o' field", logPrefix);
            return;
        }

        String symbol = order.get("s").getAsString();
        String orderType = order.get("o").getAsString();
        String orderStatus = order.get("X").getAsString();
        String orderId = String.valueOf(order.get("i").getAsLong());
        String side = order.get("S").getAsString();

        log.info("{}ORDER_TRADE_UPDATE: {} {} {} status={} orderId={}",
                logPrefix, symbol, side, orderType, orderStatus, orderId);

        // SL/TP 被取消或過期 → 持倉失去保護，緊急告警
        if (("CANCELED".equals(orderStatus) || "EXPIRED".equals(orderStatus))
                && ("STOP_MARKET".equals(orderType) || "TAKE_PROFIT_MARKET".equals(orderType))) {
            handleProtectionLost(symbol, orderType, orderId, orderStatus);
            return;
        }

        // SL/TP 部分成交
        if ("PARTIALLY_FILLED".equals(orderStatus)
                && ("STOP_MARKET".equals(orderType) || "TAKE_PROFIT_MARKET".equals(orderType))) {
            handlePartialFill(order, symbol, orderType, orderId);
            return;
        }

        // 非 FILLED 的其他狀態（NEW 等）→ 忽略
        if (!"FILLED".equals(orderStatus)) {
            log.debug("{}訂單未完全成交 ({}), 忽略", logPrefix, orderStatus);
            return;
        }

        double avgPrice = order.get("ap").getAsDouble();
        double filledQty = order.get("z").getAsDouble();
        double commission = order.get("n").getAsDouble();
        String commissionAsset = order.get("N").getAsString();
        double realizedProfit = order.get("rp").getAsDouble();
        long transactionTime = order.get("T").getAsLong();

        // 手續費幣種非 USDT 時用估算
        if (!"USDT".equals(commissionAsset)) {
            log.warn("{}手續費幣種非 USDT ({}), 使用估算: exitPrice × qty × 0.04%",
                    logPrefix, commissionAsset);
            commission = avgPrice * filledQty * 0.0004;
        }

        switch (orderType) {
            case "STOP_MARKET":
                log.info("{}止損觸發: {} @ {} qty={} commission={} rp={}",
                        logPrefix, symbol, avgPrice, filledQty, commission, realizedProfit);
                processStreamClose(symbol, avgPrice, filledQty, commission,
                        realizedProfit, orderId, "SL_TRIGGERED", transactionTime);
                break;

            case "TAKE_PROFIT_MARKET":
                log.info("{}止盈觸發: {} @ {} qty={} commission={} rp={}",
                        logPrefix, symbol, avgPrice, filledQty, commission, realizedProfit);
                processStreamClose(symbol, avgPrice, filledQty, commission,
                        realizedProfit, orderId, "TP_TRIGGERED", transactionTime);
                break;

            case "LIMIT": {
                log.info("{}{} 訂單成交: {} {} @ {} qty={}",
                        logPrefix, orderType, symbol, side, avgPrice, filledQty);

                // 嘗試匹配入場單（entryOrderId = orderId）
                Trade updatedTrade = tradeRecordService.recordLimitEntryFilled(
                        symbol, orderId, avgPrice, filledQty, commission, transactionTime);

                if (updatedTrade != null) {
                    // === LIMIT 入場成交 → 通知用戶 ===
                    String entryTitle = "✅ 限價入場成交";
                    String entryBody = String.format("%s %s\n成交價: %.2f\n數量: %.4f\n手續費: %.4f USDT",
                            symbol, updatedTrade.getSide(), avgPrice, filledQty, commission);
                    if (!exchangeName.isEmpty()) {
                        entryBody += "\n交易所: " + exchangeName;
                    }
                    notificationSender.send(entryTitle, entryBody, DiscordWebhookService.COLOR_GREEN);
                    if (adminNotifier != null) {
                        adminNotifier.send(entryTitle,
                                String.format("%s %s 成交 @ %.2f", symbol, updatedTrade.getSide(), avgPrice),
                                DiscordWebhookService.COLOR_GREEN);
                    }
                } else {
                    // === 非入場單 = 平倉 LIMIT → 走平倉流程 ===
                    processStreamClose(symbol, avgPrice, filledQty, commission,
                            realizedProfit, orderId, "SIGNAL_CLOSE", transactionTime);
                }
                break;
            }

            case "MARKET": {
                // Algo SL/TP 觸發後，Binance 以 MARKET 單成交
                // 遍歷 pendingAlgoTriggers（key=algoId）找 triggeredOrderId 或 symbol 匹配
                String algoExitReason = null;
                String matchedAlgoId = null;
                for (var entry : pendingAlgoTriggers.entrySet()) {
                    AlgoTriggerHint hint = entry.getValue();
                    if (!hint.symbol().equals(symbol)) continue;
                    // 精確比對：ai 欄位 = MARKET 單的 orderId（i 欄位）
                    // ai 為空（罕見）時 fallback 到 symbol 匹配
                    if (hint.triggeredOrderId().isEmpty() || hint.triggeredOrderId().equals(orderId)) {
                        matchedAlgoId = entry.getKey();
                        algoExitReason = hint.exitReason();
                        break;
                    }
                }
                if (matchedAlgoId != null) {
                    pendingAlgoTriggers.remove(matchedAlgoId);
                }
                if (algoExitReason == null) {
                    // fallback: 用 clientOrderId 前綴判斷（SL-xxx / TP-xxx）
                    String clientId = order.has("c") ? order.get("c").getAsString() : "";
                    if (clientId.startsWith("SL-")) {
                        algoExitReason = "SL_TRIGGERED";
                    } else if (clientId.startsWith("TP-")) {
                        algoExitReason = "TP_TRIGGERED";
                    }
                }
                if (algoExitReason != null) {
                    log.info("{}Algo {} 成交: {} @ {} qty={} commission={} rp={}",
                            logPrefix, algoExitReason, symbol, avgPrice, filledQty, commission, realizedProfit);
                    processStreamClose(symbol, avgPrice, filledQty, commission,
                            realizedProfit, orderId, algoExitReason, transactionTime);
                } else {
                    log.info("{}{} 訂單成交: {} {} @ {} qty={}",
                            logPrefix, orderType, symbol, side, avgPrice, filledQty);
                }
                break;
            }

            default:
                log.debug("{}非關注訂單類型: {} {}", logPrefix, orderType, symbol);
        }
    }

    /**
     * 處理 ALGO_UPDATE 事件
     * Binance ALGO_UPDATE 欄位（在 "o" 物件內）：
     *   s  = symbol
     *   X  = algoStatus (NEW/CANCELED/TRIGGERING/TRIGGERED/FINISHED/EXPIRED/REJECTED)
     *   o  = orderType (STOP_MARKET/TAKE_PROFIT_MARKET)
     *   aid = algoId (數值)
     *   caid = clientAlgoId (字串)
     *   ai = 觸發後的實際 orderId（非 algoId）
     *   tp = triggerPrice
     *   rm = cancel/fail reason
     *
     * TRIGGERED → 暫存到 pendingAlgoTriggers，等後續 ORDER_TRADE_UPDATE MARKET FILLED 使用
     * CANCELED/EXPIRED → 告警保護消失（OCO 連帶取消時跳過告警）
     * REJECTED → 無條件告警保護消失（訂單從未生效）
     */
    public void handleAlgoUpdate(JsonObject event) {
        JsonObject order = event.getAsJsonObject("o");
        if (order == null) {
            log.warn("{}ALGO_UPDATE missing 'o' field", logPrefix);
            return;
        }

        String symbol = order.has("s") ? order.get("s").getAsString() : "";
        String algoStatus = order.has("X") ? order.get("X").getAsString() : "";
        String orderType = order.has("o") ? order.get("o").getAsString() : "";
        String algoId = order.has("aid") ? String.valueOf(order.get("aid").getAsLong()) : "";
        String clientAlgoId = order.has("caid") ? order.get("caid").getAsString() : "";

        log.info("{}ALGO_UPDATE: {} {} algoStatus={} algoId={} clientAlgoId={}",
                logPrefix, symbol, orderType, algoStatus, algoId, clientAlgoId);

        switch (algoStatus) {
            case "TRIGGERED": {
                // SL/TP 已觸發 → 用 algoId 為 key 暫存 exitReason + 觸發後 MARKET 單的 orderId
                String exitReason = "STOP_MARKET".equals(orderType) ? "SL_TRIGGERED" : "TP_TRIGGERED";
                String triggeredOrderId = order.has("ai") ? order.get("ai").getAsString() : "";
                pendingAlgoTriggers.put(algoId, new AlgoTriggerHint(symbol, exitReason, triggeredOrderId));
                log.info("{}{} Algo 觸發: {} algoId={} triggeredOrderId={}",
                        logPrefix, "SL_TRIGGERED".equals(exitReason) ? "止損" : "止盈",
                        symbol, algoId, triggeredOrderId);
                break;
            }

            case "TRIGGERING": {
                // 觸發中（ai 尚未填入），僅 log，不寫入 hint
                String label = "STOP_MARKET".equals(orderType) ? "止損" : "止盈";
                log.info("{}{} Algo 觸發中: {} algoId={}", logPrefix, label, symbol, algoId);
                break;
            }

            case "CANCELED":
            case "EXPIRED":
                // 只清除「這張 algo 自己」的 hint，不影響同 symbol 其他 algo
                // 場景：TP TRIGGERED → SL CANCELED，不能把 TP 的 hint 清掉
                pendingAlgoTriggers.remove(algoId);

                // 檢查同 symbol 是否有已觸發的 hint（= OCO 連帶取消場景）
                // 若有，表示另一邊正在成交，此取消是正常的連帶行為，不需要告警
                boolean hasTriggeredSibling = pendingAlgoTriggers.values().stream()
                        .anyMatch(h -> h.symbol().equals(symbol));
                if (hasTriggeredSibling) {
                    log.info("{}ALGO_UPDATE {} {} algoId={} — 同 symbol 有已觸發 hint，視為 OCO 連帶取消，跳過告警",
                            logPrefix, algoStatus, symbol, algoId);
                } else {
                    handleProtectionLost(symbol, orderType, algoId, algoStatus);
                }
                break;

            case "REJECTED":
                // REJECTED = Binance 拒絕此 Algo 訂單（保證金不足、觸發價不合理等）
                // 保護單從未生效，無條件告警（不走 OCO sibling 檢查）
                pendingAlgoTriggers.remove(algoId);
                log.error("{}ALGO_UPDATE REJECTED: {} {} algoId={} — Algo 訂單被拒絕",
                        logPrefix, symbol, orderType, algoId);
                handleProtectionLost(symbol, orderType, algoId, algoStatus);
                break;

            default:
                log.debug("{}ALGO_UPDATE 非關注狀態: {} {} {}", logPrefix, symbol, orderType, algoStatus);
        }
    }

    private void handlePartialFill(JsonObject order, String symbol, String orderType, String orderId) {
        double filledQty = order.get("z").getAsDouble();
        double origQty = order.get("q").getAsDouble();
        double remainingQty = origQty - filledQty;
        boolean isSL = "STOP_MARKET".equals(orderType);

        log.warn("{}SL/TP 部分成交: {} {} filled={}/{}",
                logPrefix, symbol, orderType, filledQty, origQty);

        try {
            tradeRecordService.recordOrderEvent(symbol,
                    isSL ? "SL_PARTIAL_FILL" : "TP_PARTIAL_FILL",
                    null, gson.toJson(Map.of(
                            "orderId", orderId, "filledQty", filledQty,
                            "origQty", origQty, "remainingQty", remainingQty)));
        } catch (Exception e) {
            log.error("{}記錄部分成交事件失敗: {}", logPrefix, e.getMessage());
        }

        String partialTitle = "⚠️ " + (isSL ? "止損" : "止盈") + "單部分成交";
        String partialBody = String.format("%s %s\n成交: %.4f / %.4f\n剩餘 %.4f 等待完全成交",
                symbol, orderType, filledQty, origQty, remainingQty);
        if (!exchangeName.isEmpty()) {
            partialBody += "\n交易所: " + exchangeName;
        }
        notificationSender.send(partialTitle, partialBody, DiscordWebhookService.COLOR_YELLOW);
        if (adminNotifier != null) {
            adminNotifier.send(partialTitle, partialBody, DiscordWebhookService.COLOR_YELLOW);
        }
    }

    private void handleProtectionLost(String symbol, String orderType, String orderId, String reason) {
        boolean isSL = "STOP_MARKET".equals(orderType);
        String label = isSL ? "止損" : "止盈";

        log.warn("{}{} 被{}: {} orderId={}", logPrefix, label, reason, symbol, orderId);

        // 取得 symbol 鎖，與 CANCEL 流程同步：
        // CANCEL 持鎖期間 cancelAllOrders() + recordCancel() 一起完成，
        // 本方法等鎖取得後再查 DB，就能看到 CANCELLED 狀態而正確跳過。
        ReentrantLock lock = symbolLockRegistry.getLock(symbol);
        boolean lockAcquired;
        try {
            lockAcquired = lock.tryLock(protectionLostLockTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{}handleProtectionLost 等鎖被中斷，安全跳過: {} orderId={}", logPrefix, symbol, orderId);
            return;
        }

        if (!lockAcquired) {
            // 超時 = CANCEL 正在持鎖處理中，安全跳過（CANCEL 會負責更新 trade 狀態）
            log.info("{}handleProtectionLost 等鎖超時（CANCEL 進行中），跳過: {} orderId={}",
                    logPrefix, symbol, orderId);
            return;
        }

        try {
            boolean hasOpenTrade;
            try {
                hasOpenTrade = tradeRecordService.recordProtectionLost(symbol, orderType, orderId, reason);
            } catch (Exception e) {
                log.error("{}記錄保護消失事件失敗: {}", logPrefix, e.getMessage());
                // 查詢失敗時保守處理：假設仍有持倉，發送告警
                hasOpenTrade = true;
            }

            if (!hasOpenTrade) {
                // 倉位已平倉，SL/TP 過期屬正常連帶行為，不需發送緊急告警
                log.info("{}{}單{}但倉位已平，跳過告警: {} orderId={}",
                        logPrefix, label, reason, symbol, orderId);
                return;
            }

            // 降級為 log-only：多數保護消失事件是廣播平倉的連帶取消（race condition 導致 hasOpenTrade 誤判）
            // 真正的保護消失可透過日誌監控發現
            log.warn("{}{}單被取消（仍有持倉）: {} orderId={} 原因={} — {}",
                    logPrefix, label, symbol, orderId, reason,
                    isSL ? "持倉已失去止損保護" : "止盈保護已消失，止損仍有效");
        } finally {
            lock.unlock();
        }
    }

    private void processStreamClose(String symbol, double exitPrice, double exitQty,
                                     double commission, double realizedProfit,
                                     String orderId, String exitReason, long transactionTime) {
        ReentrantLock lock = symbolLockRegistry.getLock(symbol);
        lock.lock();
        try {
            tradeRecordService.recordCloseFromStream(
                    symbol, exitPrice, exitQty, commission,
                    realizedProfit, orderId, exitReason, transactionTime);

            String emoji = "SL_TRIGGERED".equals(exitReason) ? "🛑" : "🎯";
            String label = "SL_TRIGGERED".equals(exitReason) ? "止損觸發" : "止盈觸發";
            String closeTitle = emoji + " " + label + " (自動)";
            String closeBody = String.format("%s\n出場價: %.2f\n數量: %.4f\n手續費: %.4f USDT\n已實現損益: %.2f USDT",
                    symbol, exitPrice, exitQty, commission, realizedProfit);
            if (!exchangeName.isEmpty()) {
                closeBody += "\n交易所: " + exchangeName;
            }
            int closeColor = "SL_TRIGGERED".equals(exitReason)
                    ? DiscordWebhookService.COLOR_RED
                    : DiscordWebhookService.COLOR_GREEN;
            notificationSender.send(closeTitle, closeBody, closeColor);
            if (adminNotifier != null) {
                adminNotifier.send(closeTitle,
                        String.format("%s\n出場價: %.2f\n已實現損益: %+.2f USDT",
                                symbol, exitPrice, realizedProfit),
                        closeColor);
            }

        } catch (Exception e) {
            log.error("{}WebSocket 平倉記錄失敗: {} {} - {}",
                    logPrefix, symbol, exitReason, e.getMessage(), e);
            notificationSender.send(
                    "⚠️ WebSocket 平倉記錄失敗",
                    String.format("%s %s\norderId=%s\n錯誤: %s\n請手動檢查 DB",
                            symbol, exitReason, orderId, e.getMessage()),
                    DiscordWebhookService.COLOR_YELLOW);
        } finally {
            lock.unlock();
        }
    }

    // ==================== ACCOUNT_UPDATE 事件 — 強制平倉偵測 ====================

    /**
     * 處理 ACCOUNT_UPDATE 事件
     *
     * Binance Futures ACCOUNT_UPDATE 包含：
     * - a.B[]: 餘額變動（balance changes）
     * - a.P[]: 倉位變動（position changes）
     * - a.m: 事件原因 (DEPOSIT, WITHDRAW, ORDER, FUNDING_FEE, ADMIN_DEPOSIT, LIQUIDATION...)
     *
     * 重點偵測 m == "LIQUIDATION"：用戶被強制平倉
     */
    public void handleAccountUpdate(JsonObject event) {
        try {
            JsonObject account = event.getAsJsonObject("a");
            if (account == null) {
                log.debug("{}ACCOUNT_UPDATE 無 'a' 欄位，忽略", logPrefix);
                return;
            }

            String reason = account.has("m") ? account.get("m").getAsString() : "";

            if ("LIQUIDATION".equals(reason)) {
                handleLiquidationEvent(account);
            } else {
                log.debug("{}ACCOUNT_UPDATE reason={} (非強制平倉，忽略)", logPrefix, reason);
            }
        } catch (Exception e) {
            log.error("{}處理 ACCOUNT_UPDATE 失敗: {}", logPrefix, e.getMessage(), e);
        }
    }

    /**
     * 處理強制平倉事件
     *
     * 從 a.P[] 中找出被平倉的 symbol，記錄事件 + 更新 DB Trade + 發告警。
     */
    private void handleLiquidationEvent(JsonObject account) {
        log.error("{}🚨 偵測到強制平倉 (LIQUIDATION)!", logPrefix);

        // 解析被影響的倉位
        if (account.has("P") && account.get("P").isJsonArray()) {
            for (var elem : account.getAsJsonArray("P")) {
                JsonObject pos = elem.getAsJsonObject();
                String symbol = pos.has("s") ? pos.get("s").getAsString() : "UNKNOWN";
                double positionAmt = pos.has("pa") ? pos.get("pa").getAsDouble() : 0;
                double unrealizedPnl = pos.has("up") ? pos.get("up").getAsDouble() : 0;

                log.error("{}強制平倉倉位: {} amt={} unrealizedPnl={}",
                        logPrefix, symbol, positionAmt, unrealizedPnl);

                // 記錄到 TradeEvent
                try {
                    tradeRecordService.recordOrderEvent(symbol, "LIQUIDATION", null,
                            gson.toJson(Map.of(
                                    "positionAmt", positionAmt,
                                    "unrealizedPnl", unrealizedPnl,
                                    "reason", "LIQUIDATION")));
                } catch (Exception e) {
                    log.error("{}記錄強制平倉事件失敗: {}", logPrefix, e.getMessage());
                }

                // 如果倉位歸零，嘗試標記 DB Trade 為 CLOSED
                if (positionAmt == 0) {
                    try {
                        ReentrantLock lock = symbolLockRegistry.getLock(symbol);
                        lock.lock();
                        try {
                            tradeRecordService.markTradeClosedByLiquidation(symbol);
                        } finally {
                            lock.unlock();
                        }
                    } catch (Exception e) {
                        log.error("{}強制平倉標記 Trade CLOSED 失敗: {} - {}",
                                logPrefix, symbol, e.getMessage());
                    }
                }

                // 發送 CRITICAL 告警
                String alertTitle = "🚨 強制平倉 (LIQUIDATION)";
                String alertBody = String.format("%s\n倉位數量: %.6f\n未實現損益: %.2f USDT\n⚠️ 請立即檢查帳戶風險！",
                        symbol, positionAmt, unrealizedPnl);
                if (!exchangeName.isEmpty()) {
                    alertBody += "\n交易所: " + exchangeName;
                }
                notificationSender.send(alertTitle, alertBody, DiscordWebhookService.COLOR_RED);
                if (adminNotifier != null) {
                    adminNotifier.send(alertTitle, alertBody, DiscordWebhookService.COLOR_RED);
                }
            }
        }
    }

    // ==================== Bybit V5 事件處理 ====================

    /**
     * 處理 Bybit execution 事件
     *
     * Bybit V5 execution 格式：
     * - symbol: "BTCUSDT"
     * - orderId: 訂單 ID
     * - orderLinkId: 客戶端自訂 ID（對應我們的 clientOrderId）
     * - side: "Buy" / "Sell"
     * - execPrice: 成交價
     * - execQty: 成交數量
     * - execFee: 手續費
     * - execType: "Trade" / "BustTrade"（強平） / "Funding"
     * - orderType: "Market" / "Limit"
     * - stopOrderType: "" / "StopLoss" / "TakeProfit" / "TrailingStop"
     * - closedSize: 平倉數量（若為平倉）
     * - leavesQty: 剩餘數量
     * - execTime: 成交時間（毫秒）
     */
    public void handleBybitExecution(JsonObject data) {
        String symbol = getStr(data, "symbol");
        String orderId = getStr(data, "orderId");
        String orderLinkId = getStr(data, "orderLinkId");
        String side = getStr(data, "side");
        String execType = getStr(data, "execType");
        String orderType = getStr(data, "orderType");
        String stopOrderType = getStr(data, "stopOrderType");
        double execPrice = getDouble(data, "execPrice");
        double execQty = getDouble(data, "execQty");
        double execFee = getDouble(data, "execFee");
        double closedSize = getDouble(data, "closedSize");
        double leavesQty = getDouble(data, "leavesQty");
        long execTime = getLong(data, "execTime");

        log.info("{}Bybit execution: {} {} {} execType={} orderType={} stopOrderType={} price={} qty={}",
                logPrefix, symbol, side, orderId, execType, orderType, stopOrderType, execPrice, execQty);

        // Funding Fee — 忽略
        if ("Funding".equals(execType)) {
            log.debug("{}Bybit Funding Fee，忽略: {} fee={}", logPrefix, symbol, execFee);
            return;
        }

        // 強制平倉（BustTrade）
        if ("BustTrade".equals(execType)) {
            log.error("{}🚨 Bybit 偵測到強制平倉 (BustTrade): {} qty={}", logPrefix, symbol, execQty);
            try {
                tradeRecordService.recordOrderEvent(symbol, "LIQUIDATION", null,
                        gson.toJson(Map.of("orderId", orderId, "execQty", execQty,
                                "execPrice", execPrice, "reason", "BustTrade")));
            } catch (Exception e) {
                log.error("{}記錄 Bybit 強制平倉事件失敗: {}", logPrefix, e.getMessage());
            }

            if (closedSize > 0) {
                try {
                    ReentrantLock lock = symbolLockRegistry.getLock(symbol);
                    lock.lock();
                    try {
                        tradeRecordService.markTradeClosedByLiquidation(symbol);
                    } finally {
                        lock.unlock();
                    }
                } catch (Exception e) {
                    log.error("{}Bybit 強制平倉標記 Trade CLOSED 失敗: {} - {}", logPrefix, symbol, e.getMessage());
                }
            }

            String alertTitle = "🚨 強制平倉 (BustTrade)";
            String alertBody = String.format("%s\n成交價: %.2f\n數量: %.4f\n⚠️ 請立即檢查帳戶風險！",
                    symbol, execPrice, execQty);
            if (!exchangeName.isEmpty()) {
                alertBody += "\n交易所: " + exchangeName;
            }
            notificationSender.send(alertTitle, alertBody, DiscordWebhookService.COLOR_RED);
            if (adminNotifier != null) {
                adminNotifier.send(alertTitle, alertBody, DiscordWebhookService.COLOR_RED);
            }
            return;
        }

        // 正常成交（Trade）
        if (!"Trade".equals(execType)) {
            log.debug("{}Bybit 非關注 execType: {}", logPrefix, execType);
            return;
        }

        // 判斷是入場還是平倉
        if (closedSize > 0) {
            // 平倉成交
            String exitReason = resolveBybitExitReason(stopOrderType, orderType, orderLinkId);
            double realizedProfit = 0; // Bybit execution 不直接含 realizedPnl，由 DB 計算

            log.info("{}Bybit 平倉成交: {} {} exitReason={} price={} qty={} fee={}",
                    logPrefix, symbol, exitReason, exitReason, execPrice, closedSize, execFee);
            processStreamClose(symbol, execPrice, closedSize, execFee,
                    realizedProfit, orderId, exitReason, execTime);
        } else if (leavesQty == 0 && execQty > 0) {
            // LIMIT 入場成交（完全成交）
            log.info("{}Bybit 入場成交: {} {} @ {} qty={}", logPrefix, symbol, side, execPrice, execQty);
            Trade updatedTrade = tradeRecordService.recordLimitEntryFilled(
                    symbol, orderId, execPrice, execQty, execFee, execTime);

            if (updatedTrade != null) {
                String entryTitle = "✅ 限價入場成交";
                String entryBody = String.format("%s %s\n成交價: %.2f\n數量: %.4f\n手續費: %.4f USDT",
                        symbol, updatedTrade.getSide(), execPrice, execQty, execFee);
                if (!exchangeName.isEmpty()) {
                    entryBody += "\n交易所: " + exchangeName;
                }
                notificationSender.send(entryTitle, entryBody, DiscordWebhookService.COLOR_GREEN);
                if (adminNotifier != null) {
                    adminNotifier.send(entryTitle,
                            String.format("%s %s 成交 @ %.2f", symbol, updatedTrade.getSide(), execPrice),
                            DiscordWebhookService.COLOR_GREEN);
                }
            }
        } else {
            // 部分成交或其他
            log.debug("{}Bybit 部分成交或非關注情境: {} leavesQty={} execQty={}",
                    logPrefix, symbol, leavesQty, execQty);
        }
    }

    /**
     * 處理 Bybit position 事件
     *
     * 主要用途：偵測保護消失（SL/TP 被移除）
     * Bybit position 推送包含 stopLoss / takeProfit 欄位，
     * 若值為 "0" 或為空，表示保護已消失。
     *
     * 注意：position 事件目前僅做 log，不主動告警。
     * 真正的止損/止盈觸發由 execution 事件處理。
     */
    public void handleBybitPosition(JsonObject data) {
        String symbol = getStr(data, "symbol");
        String side = getStr(data, "side");
        double size = getDouble(data, "size");
        String stopLoss = getStr(data, "stopLoss");
        String takeProfit = getStr(data, "takeProfit");

        log.debug("{}Bybit position: {} {} size={} SL={} TP={}",
                logPrefix, symbol, side, size, stopLoss, takeProfit);

        // 倉位歸零 → 倉位已平
        if (size == 0) {
            log.info("{}Bybit 倉位歸零: {}", logPrefix, symbol);
        }
    }

    /**
     * 根據 Bybit stopOrderType 推斷平倉原因
     */
    private String resolveBybitExitReason(String stopOrderType, String orderType, String orderLinkId) {
        if ("StopLoss".equals(stopOrderType)) return "SL_TRIGGERED";
        if ("TakeProfit".equals(stopOrderType)) return "TP_TRIGGERED";
        if ("TrailingStop".equals(stopOrderType)) return "SL_TRIGGERED";

        // 檢查 orderLinkId 前綴
        if (orderLinkId != null) {
            if (orderLinkId.startsWith("SL-")) return "SL_TRIGGERED";
            if (orderLinkId.startsWith("TP-")) return "TP_TRIGGERED";
        }

        return "SIGNAL_CLOSE";
    }

    // ==================== Bitget V2 事件處理 ====================

    /**
     * 處理 Bitget orders channel 事件
     *
     * Bitget V2 orders 格式：
     * - symbol, orderId, clientOid
     * - side: "buy" / "sell"（小寫）
     * - priceAvg: 成交均價
     * - size: 委託數量, filledQty: 已成交數量
     * - fee: 手續費
     * - orderType: "limit" / "market"
     * - status: "filled" / "partially_filled" / "cancelled" / "new"
     * - enterPointSource: "WEB" / "API" / "SYS"（SYS = 系統/強平）
     * - planType: "pos_loss" / "pos_profit" — TPSL 觸發時帶此欄位
     * - tradeSide: "open" / "close"
     */
    public void handleBitgetOrder(JsonObject data) {
        String symbol = getStr(data, "symbol");
        String orderId = getStr(data, "orderId");
        String clientOid = getStr(data, "clientOid");
        String side = getStr(data, "side");
        String orderType = getStr(data, "orderType");
        String status = getStr(data, "status");
        String planType = getStr(data, "planType");
        String enterPointSource = getStr(data, "enterPointSource");
        String tradeSide = getStr(data, "tradeSide");
        double priceAvg = getDouble(data, "priceAvg");
        double size = getDouble(data, "size");
        double filledQty = getDouble(data, "filledQty");
        double fee = Math.abs(getDouble(data, "fee"));
        long uTime = getLong(data, "uTime");

        log.info("{}Bitget order: {} {} {} status={} orderType={} planType={} tradeSide={} price={} qty={}",
                logPrefix, symbol, side, orderId, status, orderType, planType, tradeSide, priceAvg, filledQty);

        // 只處理已完全成交的訂單
        if (!"filled".equalsIgnoreCase(status)) {
            if ("partially_filled".equalsIgnoreCase(status)) {
                log.debug("{}Bitget 部分成交: {} filled={}/{}", logPrefix, symbol, filledQty, size);
            }
            return;
        }

        // 強平訂單（SYS / liquidation）
        if ("SYS".equalsIgnoreCase(enterPointSource) || "liquidation".equalsIgnoreCase(orderType)) {
            log.error("{}🚨 Bitget 偵測到強制平倉: {} qty={}", logPrefix, symbol, filledQty);
            try {
                tradeRecordService.recordOrderEvent(symbol, "LIQUIDATION", null,
                        gson.toJson(Map.of("orderId", orderId, "filledQty", filledQty,
                                "priceAvg", priceAvg, "reason", "Bitget_SYS")));
            } catch (Exception e) {
                log.error("{}記錄 Bitget 強制平倉事件失敗: {}", logPrefix, e.getMessage());
            }

            try {
                ReentrantLock lock = symbolLockRegistry.getLock(symbol);
                lock.lock();
                try {
                    tradeRecordService.markTradeClosedByLiquidation(symbol);
                } finally {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.error("{}Bitget 強制平倉標記 Trade CLOSED 失敗: {} - {}", logPrefix, symbol, e.getMessage());
            }

            String alertTitle = "🚨 強制平倉 (Bitget)";
            String alertBody = String.format("%s\n成交價: %.2f\n數量: %.4f\n⚠️ 請立即檢查帳戶風險！",
                    symbol, priceAvg, filledQty);
            if (!exchangeName.isEmpty()) {
                alertBody += "\n交易所: " + exchangeName;
            }
            notificationSender.send(alertTitle, alertBody, DiscordWebhookService.COLOR_RED);
            if (adminNotifier != null) {
                adminNotifier.send(alertTitle, alertBody, DiscordWebhookService.COLOR_RED);
            }
            return;
        }

        // 判斷平倉還是入場
        if ("close".equalsIgnoreCase(tradeSide)) {
            // 平倉成交
            String exitReason = resolveBitgetExitReason(planType, orderType, clientOid);
            double realizedProfit = 0; // Bitget WS 不直接含 realizedPnl，由 DB 計算

            log.info("{}Bitget 平倉成交: {} exitReason={} price={} qty={} fee={}",
                    logPrefix, symbol, exitReason, priceAvg, filledQty, fee);
            processStreamClose(symbol, priceAvg, filledQty, fee,
                    realizedProfit, orderId, exitReason, uTime);
        } else if ("open".equalsIgnoreCase(tradeSide) && "limit".equalsIgnoreCase(orderType)) {
            // LIMIT 入場成交
            log.info("{}Bitget 入場成交: {} {} @ {} qty={}", logPrefix, symbol, side, priceAvg, filledQty);
            Trade updatedTrade = tradeRecordService.recordLimitEntryFilled(
                    symbol, orderId, priceAvg, filledQty, fee, uTime);

            if (updatedTrade != null) {
                String entryTitle = "✅ 限價入場成交";
                String entryBody = String.format("%s %s\n成交價: %.2f\n數量: %.4f\n手續費: %.4f USDT",
                        symbol, updatedTrade.getSide(), priceAvg, filledQty, fee);
                if (!exchangeName.isEmpty()) {
                    entryBody += "\n交易所: " + exchangeName;
                }
                notificationSender.send(entryTitle, entryBody, DiscordWebhookService.COLOR_GREEN);
                if (adminNotifier != null) {
                    adminNotifier.send(entryTitle,
                            String.format("%s %s 成交 @ %.2f", symbol, updatedTrade.getSide(), priceAvg),
                            DiscordWebhookService.COLOR_GREEN);
                }
            }
        } else {
            log.debug("{}Bitget 非關注訂單: {} tradeSide={} orderType={}", logPrefix, symbol, tradeSide, orderType);
        }
    }

    /**
     * 處理 Bitget positions channel 事件
     *
     * 目前僅做 log（同 Bybit 策略）
     */
    public void handleBitgetPosition(JsonObject data) {
        String symbol = getStr(data, "symbol");
        String holdSide = getStr(data, "holdSide");
        double total = getDouble(data, "total");
        String stopLossTriggerPrice = getStr(data, "stopLossTriggerPrice");
        String stopSurplusTriggerPrice = getStr(data, "stopSurplusTriggerPrice");

        log.debug("{}Bitget position: {} {} total={} SL={} TP={}",
                logPrefix, symbol, holdSide, total, stopLossTriggerPrice, stopSurplusTriggerPrice);

        if (total == 0) {
            log.info("{}Bitget 倉位歸零: {}", logPrefix, symbol);
        }
    }

    /**
     * 根據 Bitget planType 推斷平倉原因
     */
    private String resolveBitgetExitReason(String planType, String orderType, String clientOid) {
        if ("pos_loss".equals(planType)) return "SL_TRIGGERED";
        if ("pos_profit".equals(planType)) return "TP_TRIGGERED";

        if (clientOid != null) {
            if (clientOid.startsWith("SL-")) return "SL_TRIGGERED";
            if (clientOid.startsWith("TP-")) return "TP_TRIGGERED";
        }

        return "SIGNAL_CLOSE";
    }

    // ==================== JSON 工具方法 ====================

    private static String getStr(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : "";
    }

    private static double getDouble(JsonObject obj, String key) {
        try {
            return obj.has(key) ? obj.get(key).getAsDouble() : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long getLong(JsonObject obj, String key) {
        try {
            return obj.has(key) ? obj.get(key).getAsLong() : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
