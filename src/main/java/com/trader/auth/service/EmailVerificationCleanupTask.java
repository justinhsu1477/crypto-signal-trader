package com.trader.auth.service;

import com.trader.auth.repository.EmailVerificationCodeRepository;
import com.trader.shared.config.AppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 定時清理過期 Email OTP 驗證碼
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailVerificationCleanupTask {

    private final EmailVerificationCodeRepository codeRepository;

    /**
     * 每天凌晨 3 點清理 24 小時前的過期驗證碼
     */
    @Scheduled(cron = "0 0 3 * * *", zone = "${app.timezone}")
    @Transactional
    public void cleanupExpiredCodes() {
        try {
            LocalDateTime cutoff = LocalDateTime.now(AppConstants.ZONE_ID).minusHours(24);
            codeRepository.deleteByExpiresAtBefore(cutoff);
            log.info("已清理過期驗證碼 (cutoff={})", cutoff);
        } catch (Exception e) {
            log.error("清理過期驗證碼失敗", e);
        }
    }
}
