package com.trader.shared.service;

import com.trader.shared.config.AppConstants;
import com.trader.shared.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 定時清理過期審計日誌
 *
 * 保留最近 60 天的日誌，每天凌晨 4 點執行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogCleanupTask {

    private static final int RETENTION_DAYS = 60;

    private final AuditLogRepository auditLogRepository;

    /**
     * 每天凌晨 4 點清理 60 天前的審計日誌
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "${app.timezone}")
    @Transactional
    public void cleanupOldAuditLogs() {
        try {
            LocalDateTime cutoff = LocalDateTime.now(AppConstants.ZONE_ID).minusDays(RETENTION_DAYS);
            int deleted = auditLogRepository.deleteByTimestampBefore(cutoff);
            log.info("審計日誌清理完成: 刪除 {} 筆 (截止日期: {})", deleted, cutoff);
        } catch (Exception e) {
            log.error("審計日誌清理失敗", e);
        }
    }
}
