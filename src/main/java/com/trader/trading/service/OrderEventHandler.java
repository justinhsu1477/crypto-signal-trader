package com.trader.trading.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.notification.service.DiscordWebhookService;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 共用的 ORDER_TRADE_UPDATE 事件處理邏輯
 *
 * 單用戶 (BinanceUserDataStreamService) 和多用戶 (MultiUserDataStreamManager)
 * 的 WebSocket Listener 都呼叫這裡，避免重複 150+ 行相同邏輯。
 *
 * 通知策略透過 NotificationSender 函式介面抽象：
 * - 單用戶版傳 sendNotification(title, msg, color)
 * - 多用戶版傳 sendNotificationToUser(userId, title, msg, color)
 */
@Slf4j
public class OrderEventHandler {

    private final TradeRecordService tradeRecordService;
    private final SymbolLockRegistry symbolLockRegistry;
    private final NotificationSender notificationSender;
    private final Gson gson;
    private final String logPrefix;  // 日誌前綴：空字串 or "用戶 {userId} "

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
                              Gson gson,
                              String logPrefix) {
        this.tradeRecordService = tradeRecordService;
        this.symbolLockRegistry = symbolLockRegistry;
        this.notificationSender = notificationSender;
        this.gson = gson;
        this.logPrefix = logPrefix != null ? logPrefix : "";
    }

    /**
     * 處理 ORDER_TRADE_UPDATE 事件
     * - FILLED 的 STOP_MARKET / TAKE_PROFIT_MARKET → 記錄平倉
     * - CANCELED / EXPIRED 的 STOP_MARKET / TAKE_PROFIT_MARKET → 告警保護消失
     * - PARTIALLY_FILLED 的 SL/TP → 告警 + 記錄事件
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

            case "LIMIT":
                log.info("{}{} 訂單成交: {} {} @ {} qty={}",
                        logPrefix, orderType, symbol, side, avgPrice, filledQty);
                break;

            case "MARKET":
                log.info("{}{} 訂單成交: {} {} @ {} qty={}",
                        logPrefix, orderType, symbol, side, avgPrice, filledQty);
                break;

            default:
                log.debug("{}非關注訂單類型: {} {}", logPrefix, orderType, symbol);
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

        notificationSender.send(
                "⚠️ " + (isSL ? "止損" : "止盈") + "單部分成交",
                String.format("%s %s\n成交: %.4f / %.4f\n剩餘 %.4f 等待完全成交",
                        symbol, orderType, filledQty, origQty, remainingQty),
                DiscordWebhookService.COLOR_YELLOW);
    }

    private void handleProtectionLost(String symbol, String orderType, String orderId, String reason) {
        boolean isSL = "STOP_MARKET".equals(orderType);
        String label = isSL ? "止損" : "止盈";

        log.warn("{}{} 被{}: {} orderId={}", logPrefix, label, reason, symbol, orderId);

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

        int color = isSL ? DiscordWebhookService.COLOR_RED : DiscordWebhookService.COLOR_YELLOW;
        String urgency = isSL ? "🚨" : "⚠️";

        notificationSender.send(
                urgency + " " + label + "單被取消",
                String.format("%s\n訂單號: %s\n原因: %s\n%s",
                        symbol, orderId, reason,
                        isSL ? "⚠️ 持倉已失去止損保護！請立即檢查" : "止盈保護已消失，止損仍有效"),
                color);
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
            notificationSender.send(
                    emoji + " " + label + " (自動)",
                    String.format("%s\n出場價: %.2f\n數量: %.4f\n手續費: %.4f USDT\n已實現損益: %.2f USDT",
                            symbol, exitPrice, exitQty, commission, realizedProfit),
                    "SL_TRIGGERED".equals(exitReason)
                            ? DiscordWebhookService.COLOR_RED
                            : DiscordWebhookService.COLOR_GREEN);

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
}
