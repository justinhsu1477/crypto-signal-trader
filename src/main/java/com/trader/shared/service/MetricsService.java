package com.trader.shared.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 系統指標收集服務 — 封裝 Micrometer MeterRegistry
 *
 * <pre>
 * 放在 shared 模組，trading + notification 都能注入使用。
 * 指標命名慣例：{domain}.{metric}.{detail}
 *
 * 收集端：BinanceFuturesService（下單/訊號/API延遲）、CompositeNotificationService（通知）
 * 查詢端：AdminDashboardController GET /api/admin/dashboard/metrics
 * </pre>
 */
@Slf4j
@Service
public class MetricsService {

    private final MeterRegistry meterRegistry;

    public MetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ==================== 記錄指標 ====================

    /** 記錄下單結果 */
    public void recordOrder(String orderType, boolean success) {
        Counter.builder("trading.orders.total")
                .tag("type", orderType)
                .tag("status", success ? "success" : "fail")
                .register(meterRegistry)
                .increment();
    }

    /** 記錄訊號處理 */
    public void recordSignal(String signalType) {
        Counter.builder("trading.signals.processed")
                .tag("signalType", signalType)
                .register(meterRegistry)
                .increment();
    }

    /** 記錄 Binance API 延遲 */
    public void recordApiLatency(String operation, long durationMs) {
        Timer.builder("trading.binance.api.latency")
                .tag("operation", operation)
                .register(meterRegistry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** 記錄通知發送 */
    public void recordNotification(String channel, boolean success) {
        Counter.builder("notification.sent")
                .tag("channel", channel)
                .tag("status", success ? "success" : "fail")
                .register(meterRegistry)
                .increment();
    }

    // ==================== 查詢指標（Admin Dashboard 用）====================

    /**
     * 取得 Admin Dashboard 指標摘要
     * 回傳結構化 Map，由 Controller 轉成 DTO
     */
    public Map<String, Object> getMetricsSummary() {
        Map<String, Object> result = new LinkedHashMap<>();

        // 1. 下單統計
        Map<String, Object> orders = new LinkedHashMap<>();
        double orderSuccess = getCounterValue("trading.orders.total", "status", "success");
        double orderFail = getCounterValue("trading.orders.total", "status", "fail");
        double orderTotal = orderSuccess + orderFail;
        orders.put("total", (long) orderTotal);
        orders.put("success", (long) orderSuccess);
        orders.put("failed", (long) orderFail);
        orders.put("successRate", orderTotal > 0 ? round(orderSuccess / orderTotal * 100, 1) : 0);
        result.put("orders", orders);

        // 2. 訊號統計
        Map<String, Object> signals = new LinkedHashMap<>();
        double signalEntry = getCounterValue("trading.signals.processed", "signalType", "ENTRY");
        double signalClose = getCounterValue("trading.signals.processed", "signalType", "CLOSE");
        double signalMoveSL = getCounterValue("trading.signals.processed", "signalType", "MOVE_SL");
        double signalCancel = getCounterValue("trading.signals.processed", "signalType", "CANCEL");
        double signalTotal = signalEntry + signalClose + signalMoveSL + signalCancel;
        signals.put("total", (long) signalTotal);
        Map<String, Long> byType = new LinkedHashMap<>();
        byType.put("ENTRY", (long) signalEntry);
        byType.put("CLOSE", (long) signalClose);
        byType.put("MOVE_SL", (long) signalMoveSL);
        byType.put("CANCEL", (long) signalCancel);
        signals.put("byType", byType);
        result.put("signals", signals);

        // 3. 通知統計
        Map<String, Object> notifications = new LinkedHashMap<>();
        double notiDiscordOk = getCounterValue("notification.sent", "channel", "discord", "status", "success");
        double notiDiscordFail = getCounterValue("notification.sent", "channel", "discord", "status", "fail");
        double notiLineOk = getCounterValue("notification.sent", "channel", "line", "status", "success");
        double notiLineFail = getCounterValue("notification.sent", "channel", "line", "status", "fail");
        double notiTotal = notiDiscordOk + notiDiscordFail + notiLineOk + notiLineFail;
        double notiFail = notiDiscordFail + notiLineFail;
        notifications.put("total", (long) notiTotal);
        Map<String, Long> byChannel = new LinkedHashMap<>();
        byChannel.put("discord", (long) (notiDiscordOk + notiDiscordFail));
        byChannel.put("line", (long) (notiLineOk + notiLineFail));
        notifications.put("byChannel", byChannel);
        notifications.put("failRate", notiTotal > 0 ? round(notiFail / notiTotal * 100, 1) : 0);
        result.put("notifications", notifications);

        // 4. API 延遲
        Map<String, Object> api = new LinkedHashMap<>();
        Timer timer = meterRegistry.find("trading.binance.api.latency").timer();
        if (timer != null && timer.count() > 0) {
            api.put("avgLatencyMs", round(timer.mean(TimeUnit.MILLISECONDS), 0));
            api.put("p99LatencyMs", round(timer.max(TimeUnit.MILLISECONDS), 0));
            api.put("totalCalls", timer.count());
        } else {
            api.put("avgLatencyMs", 0);
            api.put("p99LatencyMs", 0);
            api.put("totalCalls", 0L);
        }
        result.put("api", api);

        // 5. 系統狀態
        Map<String, Object> system = new LinkedHashMap<>();
        long uptimeMs = ManagementFactory.getRuntimeMXBean().getUptime();
        system.put("uptimeSeconds", uptimeMs / 1000);
        result.put("system", system);

        return result;
    }

    // ==================== 內部工具 ====================

    private double getCounterValue(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter != null ? counter.count() : 0;
    }

    private double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
