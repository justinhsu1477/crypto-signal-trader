package com.trader.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discord Monitor 心跳監控服務
 *
 * 機制：
 * 1. Python discord-monitor 每 30 秒 POST /api/heartbeat
 * 2. 本服務記錄最後一次心跳時間
 * 3. 排程每 60 秒檢查：若超過 90 秒沒收到心跳 → Discord webhook 告警
 * 4. 恢復連線時自動發送「已恢復」通知
 */
@Slf4j
@Service
public class MonitorHeartbeatService {

    private final DiscordWebhookService webhookService;

    /** 最後一次收到心跳的時間（epoch seconds） */
    private final AtomicReference<Instant> lastHeartbeat = new AtomicReference<>(null);

    /** 是否已經發過斷線告警（避免重複發送） */
    private volatile boolean alertSent = false;

    /** 心跳逾時秒數：超過此時間沒收到心跳就告警 */
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 90;

    public MonitorHeartbeatService(DiscordWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * 接收 Python monitor 的心跳
     *
     * @param status Python 端傳來的狀態（connected / reconnecting 等）
     * @return 回應資訊
     */
    public Map<String, Object> receiveHeartbeat(String status) {
        Instant now = Instant.now();
        Instant previous = lastHeartbeat.getAndSet(now);

        // 如果之前斷線過且已發告警，現在恢復了 → 發恢復通知
        if (alertSent) {
            alertSent = false;
            long downSeconds = previous != null ? now.getEpochSecond() - previous.getEpochSecond() : 0;
            log.info("Discord Monitor 已恢復連線，斷線時長: {}秒", downSeconds);
            webhookService.sendNotification(
                    "✅ Discord Monitor 已恢復",
                    String.format("監控服務已重新連線\n斷線時長: %d 秒\n狀態: %s", downSeconds, status),
                    DiscordWebhookService.COLOR_GREEN);
        }

        log.debug("收到心跳: status={}", status);
        return Map.of(
                "received", true,
                "timestamp", now.toString(),
                "status", "ok"
        );
    }

    /**
     * 每 60 秒檢查心跳是否逾時
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 120_000)
    public void checkHeartbeat() {
        Instant last = lastHeartbeat.get();

        // 從未收到心跳（剛啟動，monitor 還沒連上）
        if (last == null) {
            log.debug("尚未收到任何心跳，等待 Discord Monitor 連線...");
            return;
        }

        long elapsed = Instant.now().getEpochSecond() - last.getEpochSecond();

        if (elapsed > HEARTBEAT_TIMEOUT_SECONDS && !alertSent) {
            alertSent = true;
            String msg = String.format(
                    "Discord Monitor 已 %d 秒未回報心跳！\n"
                    + "可能原因:\n"
                    + "• Discord 被關閉或崩潰\n"
                    + "• CDP 連線中斷\n"
                    + "• Python monitor 程序掛掉\n\n"
                    + "⚠️ 訊號監控已中斷，新的交易訊號將不會被接收！",
                    elapsed);
            log.error("Discord Monitor 心跳逾時! 已 {}秒 未收到心跳", elapsed);
            webhookService.sendNotification(
                    "🚨 Discord Monitor 離線",
                    msg,
                    DiscordWebhookService.COLOR_RED);
        }
    }

    /**
     * 取得心跳狀態（供 API 查詢）
     */
    public Map<String, Object> getStatus() {
        Instant last = lastHeartbeat.get();
        long elapsed = last != null ? Instant.now().getEpochSecond() - last.getEpochSecond() : -1;
        return Map.of(
                "lastHeartbeat", last != null ? last.toString() : "never",
                "elapsedSeconds", elapsed,
                "online", last != null && elapsed <= HEARTBEAT_TIMEOUT_SECONDS,
                "alertSent", alertSent
        );
    }
}
