package com.trader.trading.service;

import com.trader.notification.service.DiscordWebhookService;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Discord Monitor 心跳監控服務
 *
 * 偵測兩種斷線：
 * 1. Python 掛了 → 心跳停止 → 逾時告警
 * 2. Discord 關了但 Python 還活著 → 心跳帶 status="reconnecting" → 立即告警
 *
 * 機制：
 * - Python 每 30 秒 POST /api/heartbeat {"status":"connected"/"reconnecting"}
 * - Java 排程每 60 秒檢查逾時
 * - 收到 reconnecting 狀態 → 立即發 Discord 告警
 * - 恢復 connected → 自動發「已恢復」通知
 */
@Slf4j
@Service
public class MonitorHeartbeatService {

    private final DiscordWebhookService webhookService;

    /** 最後一次收到心跳的時間 */
    private final AtomicReference<Instant> lastHeartbeat = new AtomicReference<>(null);

    /** 最後收到的狀態 */
    private volatile String lastStatus = "unknown";

    /** 是否已經發過斷線告警（避免重複發送） */
    private volatile boolean alertSent = false;

    /** 是否已經發過 AI 離線告警（只發一次） */
    private volatile boolean aiAlertSent = false;

    /** 最後收到的 AI 狀態 */
    private volatile String lastAiStatus = "unknown";

    /** 心跳逾時秒數：超過此時間沒收到心跳就告警 */
    private static final long HEARTBEAT_TIMEOUT_SECONDS = 90;

    public MonitorHeartbeatService(DiscordWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    /**
     * 接收 Python monitor 的心跳
     *
     * @param status   Python 端傳來的狀態（connected / reconnecting / connecting）
     * @param aiStatus AI parser 狀態（active / disabled）
     * @return 回應資訊
     */
    public Map<String, Object> receiveHeartbeat(String status, String aiStatus) {
        Instant now = Instant.now();
        Instant previous = lastHeartbeat.getAndSet(now);
        String previousStatus = lastStatus;
        lastStatus = status;

        // 更新 AI 狀態
        if (aiStatus != null) {
            lastAiStatus = aiStatus;
        }

        // ===== 情況 1: Discord 斷了，Python 在重連 =====
        if ("reconnecting".equals(status) && !alertSent) {
            alertSent = true;
            log.warn("Discord 連線中斷! Python monitor 正在重連...");
            webhookService.sendNotification(
                    "🚨 Discord 連線中斷",
                    "Python monitor 回報 Discord CDP 連線已斷開\n"
                    + "正在自動重連中...\n\n"
                    + "⚠️ 訊號監控已中斷，新的交易訊號暫時不會被接收！\n"
                    + "請確認 Discord 是否還在運行",
                    DiscordWebhookService.COLOR_RED);
        }

        // ===== 情況 2: 從斷線恢復了 =====
        if ("connected".equals(status) && alertSent) {
            alertSent = false;
            long downSeconds = previous != null ? now.getEpochSecond() - previous.getEpochSecond() : 0;
            log.info("Discord Monitor 已恢復連線，斷線時長: {}秒", downSeconds);
            webhookService.sendNotification(
                    "✅ Discord Monitor 已恢復",
                    String.format("監控服務已重新連線\n斷線時長約: %d 秒\n訊號監控已恢復正常", downSeconds),
                    DiscordWebhookService.COLOR_GREEN);
        }

        // ===== 情況 3: AI parser 未啟用 =====
        if ("disabled".equals(aiStatus) && !aiAlertSent) {
            aiAlertSent = true;
            log.warn("AI Signal Parser 未啟用! 將使用 regex fallback");
            webhookService.sendNotification(
                    "⚠️ AI Agent 未啟用",
                    "Python monitor 回報 AI Signal Parser 無法連線\n"
                    + "可能原因:\n"
                    + "• GEMINI_API_KEY 環境變數未設定\n"
                    + "• API Key 無效或過期\n\n"
                    + "目前使用 regex fallback 模式解析訊號\n"
                    + "部分非標準格式的訊號可能無法辨識",
                    DiscordWebhookService.COLOR_YELLOW);
        }

        // ===== 情況 4: AI parser 恢復了 =====
        if ("active".equals(aiStatus) && aiAlertSent) {
            aiAlertSent = false;
            log.info("AI Signal Parser 已恢復啟用");
            webhookService.sendNotification(
                    "✅ AI Agent 已啟用",
                    "AI Signal Parser 已成功連線\n訊號解析已切換回 AI 模式",
                    DiscordWebhookService.COLOR_GREEN);
        }

        log.debug("收到心跳: status={}, aiStatus={}", status, aiStatus);
        return Map.of(
                "received", true,
                "timestamp", now.toString(),
                "status", "ok"
        );
    }

    /**
     * 每 60 秒檢查心跳是否逾時（Python 整個掛了的情況）
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
                    + "• Python monitor 程序崩潰\n"
                    + "• Discord 被關閉且 Python 也跟著掛了\n"
                    + "• 機器斷網或重啟\n\n"
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
        boolean isOnline = last != null
                && elapsed <= HEARTBEAT_TIMEOUT_SECONDS
                && "connected".equals(lastStatus);
        return Map.of(
                "lastHeartbeat", last != null ? last.toString() : "never",
                "elapsedSeconds", elapsed,
                "online", isOnline,
                "monitorStatus", lastStatus,
                "aiStatus", lastAiStatus,
                "alertSent", alertSent
        );
    }
}
