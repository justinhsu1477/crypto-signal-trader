package com.trader.auth.service;

import com.trader.auth.config.EmailConfig;
import com.trader.auth.entity.EmailVerificationCode;
import com.trader.auth.repository.EmailVerificationCodeRepository;
import com.trader.shared.config.AppConstants;
import com.trader.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * Email OTP 驗證服務
 *
 * 負責：產生 OTP、驗證 OTP、重送 OTP、Rate Limit
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private final EmailVerificationCodeRepository codeRepository;
    private final UserRepository userRepository;
    private final ResendEmailService resendEmailService;
    private final EmailConfig emailConfig;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 產生 OTP 並發送 Email
     *
     * @param email 目標信箱
     * @throws IllegalStateException 超過每小時發送上限
     */
    public void generateAndSend(String email) {
        LocalDateTime oneHourAgo = LocalDateTime.now(AppConstants.ZONE_ID).minusHours(1);
        long recentCount = codeRepository.countByEmailAndCreatedAtAfter(email, oneHourAgo);

        if (recentCount >= emailConfig.getMaxSendsPerHour()) {
            throw new IllegalStateException("發送過於頻繁，請稍後再試");
        }

        String code = generateCode();
        LocalDateTime expiresAt = LocalDateTime.now(AppConstants.ZONE_ID)
                .plusMinutes(emailConfig.getOtpExpiryMinutes());

        EmailVerificationCode entity = EmailVerificationCode.builder()
                .email(email)
                .code(code)
                .expiresAt(expiresAt)
                .build();

        codeRepository.save(entity);
        resendEmailService.sendOtpEmail(email, code);

        log.info("OTP 已產生並發送: email={} expiresAt={}", email, expiresAt);
    }

    /**
     * 驗證 OTP
     *
     * @param email 信箱
     * @param code  用戶輸入的 6 位數 OTP
     * @return true 表示驗證成功
     * @throws IllegalArgumentException 驗證碼不存在/過期/錯誤/超過次數
     */
    @Transactional
    public boolean verifyCode(String email, String code) {
        EmailVerificationCode entity = codeRepository
                .findTopByEmailAndUsedFalseOrderByCreatedAtDesc(email)
                .orElseThrow(() -> new IllegalArgumentException("驗證碼不存在或已過期"));

        LocalDateTime now = LocalDateTime.now(AppConstants.ZONE_ID);

        // 已過期
        if (now.isAfter(entity.getExpiresAt())) {
            throw new IllegalArgumentException("驗證碼已過期，請重新發送");
        }

        // 超過最大嘗試次數
        if (entity.getAttempts() >= emailConfig.getMaxAttemptsPerCode()) {
            throw new IllegalArgumentException("驗證碼已失效，請重新發送");
        }

        // 驗證碼不符
        if (!entity.getCode().equals(code)) {
            entity.setAttempts(entity.getAttempts() + 1);
            codeRepository.save(entity);

            int remaining = emailConfig.getMaxAttemptsPerCode() - entity.getAttempts();
            if (remaining <= 0) {
                throw new IllegalArgumentException("驗證碼已失效，請重新發送");
            }
            throw new IllegalArgumentException("驗證碼錯誤，剩餘 " + remaining + " 次機會");
        }

        // 驗證通過
        entity.setUsed(true);
        codeRepository.save(entity);
        log.info("OTP 驗證成功: email={}", email);
        return true;
    }

    /**
     * 重新發送 OTP（供已註冊但未驗證的用戶使用）
     *
     * @param email 信箱
     * @throws IllegalArgumentException 找不到用戶或已驗證
     */
    public void resendCode(String email) {
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("找不到此 Email 的帳號"));

        if (user.isEmailVerified()) {
            throw new IllegalArgumentException("此帳號已完成 Email 驗證");
        }

        generateAndSend(email);
    }

    /**
     * 產生 6 位數隨機驗證碼
     */
    String generateCode() {
        int num = secureRandom.nextInt(1_000_000);
        return String.format("%06d", num);
    }
}
