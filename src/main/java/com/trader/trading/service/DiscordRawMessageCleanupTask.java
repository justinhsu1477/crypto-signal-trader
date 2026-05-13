package com.trader.trading.service;

import com.trader.shared.config.AppConstants;
import com.trader.trading.repository.DiscordRawMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * discord_raw_messages 表保留 180 天，每日凌晨 03:30 (Asia/Taipei) 清理過期紀錄。
 *
 * 為什麼是 180 天：
 * - audit 漏單偵測通常只看近期（1-7 天）
 * - eval harness 案例可以從歷史 export，不需要 DB 保留
 * - 180 天 × 50 訊息/天 × 50 KOL = 450K rows 上限，索引仍輕量
 *
 * 例外吞掉策略：scheduled task 一旦 throw 會中斷整個 scheduler，所以這裡 catch all。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DiscordRawMessageCleanupTask {

    private static final int RETENTION_DAYS = 180;

    private final DiscordRawMessageRepository repository;

    /** 每日 03:30 (Asia/Taipei) — 避開交易高峰時段 */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Taipei")
    public void cleanupExpiredMessages() {
        LocalDateTime cutoff = LocalDateTime.now(AppConstants.ZONE_ID)
                .minusDays(RETENTION_DAYS);
        try {
            int deleted = repository.deleteOlderThan(cutoff);
            log.info("discord_raw_messages cleanup: deleted {} rows older than {}",
                    deleted, cutoff);
        } catch (Exception e) {
            log.error("discord_raw_messages cleanup failed: {}", e.getMessage(), e);
        }
    }
}
