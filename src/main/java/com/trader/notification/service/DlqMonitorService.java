package com.trader.notification.service;

import com.trader.notification.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * DLQ 監控服務 — 定期檢查 Dead Letter Queue 堆積狀況
 *
 * 為什麼 DLQ 不需要 Consumer？
 *   DLQ 裡的訊息是「重試 3 次都失敗」的，代表有結構性問題（bug、外部 API 掛了、資料異常）。
 *   自動重試大概率會再次失敗，反而浪費資源。
 *   正確做法是：監控 → 告警 → 人工判斷（fix code 後 requeue，或 discard）。
 *
 * 面試重點：
 *   - DLQ 是「停屍間」不是「急診室」— 需要人工介入
 *   - TTL 7 天自動過期，防止無限堆積
 *   - 排程每 5 分鐘檢查一次，有新訊息就通知 Admin
 *   - RabbitAdmin.getQueueInfo() 透過 AMQP 管理 API 查詢 queue 狀態
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DlqMonitorService {

    private final RabbitAdmin rabbitAdmin;
    private final NotificationService notificationService;

    /** 上一次告警時的 DLQ 訊息數（避免重複告警） */
    private volatile int lastAlertedCount = 0;

    /**
     * 每 5 分鐘檢查 DLQ 訊息數量
     * 有新訊息時推 Discord 告警到所有 Admin
     */
    @Scheduled(fixedRate = 5 * 60 * 1000, initialDelay = 60 * 1000) // 啟動 1 分鐘後開始
    public void checkDlq() {
        try {
            QueueInformation info = rabbitAdmin.getQueueInfo(RabbitMQConfig.DLQ_QUEUE);
            if (info == null) {
                log.debug("DLQ queue 尚未建立，跳過檢查");
                return;
            }

            int messageCount = info.getMessageCount();

            if (messageCount > 0 && messageCount != lastAlertedCount) {
                log.warn("DLQ 有 {} 筆未處理訊息（上次告警時 {} 筆）", messageCount, lastAlertedCount);

                String title = "⚠️ DLQ 告警 | " + messageCount + " 筆失敗訊息";
                String message = String.join("\n",
                        "Queue: " + RabbitMQConfig.DLQ_QUEUE,
                        "訊息數: " + messageCount,
                        "TTL: 7 天後自動過期",
                        "",
                        "請至 RabbitMQ 管理介面檢查：",
                        "http://localhost:15672/#/queues/%2F/" + RabbitMQConfig.DLQ_QUEUE
                );

                notificationService.sendNotificationToAdmins(title, message, NotificationService.COLOR_YELLOW);
                lastAlertedCount = messageCount;

            } else if (messageCount == 0 && lastAlertedCount > 0) {
                // DLQ 清空 → 發送恢復通知
                log.info("DLQ 已清空（之前有 {} 筆）", lastAlertedCount);
                notificationService.sendNotificationToAdmins(
                        "✅ DLQ 已清空",
                        "所有失敗訊息已處理或過期。",
                        NotificationService.COLOR_GREEN
                );
                lastAlertedCount = 0;
            } else {
                log.debug("DLQ 狀態正常: {} 筆", messageCount);
            }

        } catch (Exception e) {
            log.warn("DLQ 監控檢查失敗: {}", e.getMessage());
        }
    }
}
