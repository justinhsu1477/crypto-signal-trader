package com.trader.trading.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.trader.notification.service.NotificationService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.exchange.ExchangeAdapter;
import com.trader.trading.exchange.ExchangeAdapterFactory;
import com.trader.trading.exchange.ExchangeCredentials;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.ExchangeKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 強制平倉偵測排程
 *
 * 每 10 分鐘查詢 Binance /fapi/v1/forceOrders，偵測是否有
 * WebSocket 漏接的強制平倉事件。與 ACCOUNT_UPDATE 即時偵測互補。
 *
 * 防重複：用 orderId set 記錄已處理的強制平倉，避免重複告警。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiquidationDetectionTask {

    private final ExchangeAdapterFactory exchangeAdapterFactory;
    private final TradeRecordService tradeRecordService;
    private final TradeRepository tradeRepository;
    private final NotificationService notificationService;
    private final MultiUserConfig multiUserConfig;
    private final UserApiKeyService userApiKeyService;
    private final UserRepository userRepository;
    private final Gson gson;

    // 已處理的強制平倉 orderId（防重複，重啟歸零）
    private final Set<String> processedOrderIds = ConcurrentHashMap.newKeySet();

    @Scheduled(fixedRate = 10 * 60 * 1000, initialDelay = 5 * 60 * 1000)
    public void scheduledLiquidationCheck() {
        try {
            if (multiUserConfig.isEnabled()) {
                checkForAllUsers();
            } else {
                checkGlobal();
            }
        } catch (Exception e) {
            log.error("強制平倉偵測排程異常: {}", e.getMessage(), e);
        }
    }

    private void checkGlobal() {
        ExchangeAdapter adapter = exchangeAdapterFactory.getDefaultAdapter();
        int detected = detectForceOrders(null, adapter);
        if (detected > 0) {
            log.warn("強制平倉偵測完成: 發現 {} 筆", detected);
        }
    }

    private void checkForAllUsers() {
        List<User> users = userRepository.findAll().stream()
                .filter(User::isEnabled)
                .toList();

        for (User user : users) {
            String userId = user.getUserId();
            try {
                var primaryOpt = userApiKeyService.getUserPrimaryExchangeKeys(userId);
                if (primaryOpt.isEmpty()) continue;

                String exchange = primaryOpt.get().getKey();
                ExchangeKeys keys = primaryOpt.get().getValue();
                ExchangeAdapter adapter = exchangeAdapterFactory.getAdapter(exchange);
                adapter.setCredentials(new ExchangeCredentials(keys.apiKey(), keys.secretKey()));
                TradeRecordService.setCurrentUserId(userId);

                try {
                    detectForceOrders(userId, adapter);
                } finally {
                    adapter.clearCredentials();
                    TradeRecordService.clearCurrentUserId();
                }
            } catch (Exception e) {
                log.error("用戶 {} 強制平倉偵測失敗: {}", userId, e.getMessage());
            }
        }
    }

    int detectForceOrders(String userId, ExchangeAdapter adapter) {
        String response;
        try {
            response = adapter.getForceOrdersRaw();
        } catch (Exception e) {
            log.warn("查詢 forceOrders 失敗: {}", e.getMessage());
            return 0;
        }

        JsonArray orders;
        try {
            orders = gson.fromJson(response, JsonArray.class);
        } catch (Exception e) {
            log.warn("解析 forceOrders 回應失敗: {}", e.getMessage());
            return 0;
        }

        if (orders == null || orders.isEmpty()) {
            return 0;
        }

        int detected = 0;
        // 只處理最近 15 分鐘內的強制平倉（避免歷史重複）
        long cutoffMs = Instant.now().toEpochMilli() - (15 * 60 * 1000);

        for (JsonElement elem : orders) {
            JsonObject order = elem.getAsJsonObject();
            String orderId = order.has("orderId") ? order.get("orderId").getAsString() : "";
            long time = order.has("time") ? order.get("time").getAsLong() : 0;

            if (time < cutoffMs) continue;
            if (processedOrderIds.contains(orderId)) continue;

            processedOrderIds.add(orderId);
            detected++;

            String symbol = order.has("symbol") ? order.get("symbol").getAsString() : "UNKNOWN";
            String side = order.has("side") ? order.get("side").getAsString() : "";
            double price = order.has("avgPrice") ? order.get("avgPrice").getAsDouble() : 0;
            double qty = order.has("origQty") ? order.get("origQty").getAsDouble() : 0;

            log.error("🚨 偵測到強制平倉: {} {} price={} qty={} orderId={} (userId={})",
                    symbol, side, price, qty, orderId, userId);

            // 記錄事件
            try {
                tradeRecordService.recordOrderEvent(symbol, "LIQUIDATION_DETECTED", null,
                        gson.toJson(Map.of(
                                "orderId", orderId, "side", side,
                                "price", price, "qty", qty,
                                "source", "forceOrders_poll")));
            } catch (Exception e) {
                log.error("記錄強制平倉事件失敗: {}", e.getMessage());
            }

            // 標記 DB Trade
            try {
                tradeRecordService.markTradeClosedByLiquidation(symbol);
            } catch (Exception e) {
                log.error("強制平倉標記 Trade 失敗: {} - {}", symbol, e.getMessage());
            }

            // 告警
            String alertBody = String.format(
                    "%s %s\n成交價: %.2f\n數量: %.6f\nuserId: %s\n來源: 定期 forceOrders 查詢\n⚠️ 請立即檢查帳戶風險！",
                    symbol, side, price, qty, userId);

            if (userId != null) {
                notificationService.sendNotificationToUser(userId,
                        "🚨 強制平倉偵測", alertBody, NotificationService.COLOR_RED);
            }
            notificationService.sendNotificationToAdmins(
                    "🚨 強制平倉偵測", alertBody, NotificationService.COLOR_RED);
        }

        return detected;
    }

    // 暴露供測試使用
    Set<String> getProcessedOrderIds() {
        return processedOrderIds;
    }
}
