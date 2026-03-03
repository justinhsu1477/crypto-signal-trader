package com.trader.auth.service;

import com.trader.auth.config.EmailConfig;
import com.trader.auth.entity.EmailVerificationCode;
import com.trader.auth.repository.EmailVerificationCodeRepository;
import com.trader.auth.util.EmailNormalizer;
import com.trader.shared.config.AppConstants;
import com.trader.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;

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

    @Value("${email.otp-hash-secret:${jwt.secret}}")
    private String otpHashSecret;

    /**
     * 產生 OTP 並發送 Email
     *
     * @param email 目標信箱
     * @throws IllegalStateException 超過每小時發送上限
     */
    public void generateAndSend(String email) {
        String normalizedEmail = EmailNormalizer.normalize(email);

        LocalDateTime oneHourAgo = LocalDateTime.now(AppConstants.ZONE_ID).minusHours(1);
        long recentCount = codeRepository.countByEmailIgnoreCaseAndCreatedAtAfter(normalizedEmail, oneHourAgo);

        if (recentCount >= emailConfig.getMaxSendsPerHour()) {
            throw new IllegalStateException("發送過於頻繁，請稍後再試");
        }

        String rawCode = generateCode();
        LocalDateTime expiresAt = LocalDateTime.now(AppConstants.ZONE_ID)
                .plusMinutes(emailConfig.getOtpExpiryMinutes());

        EmailVerificationCode entity = EmailVerificationCode.builder()
                .email(normalizedEmail)
                .code(hashOtp(normalizedEmail, rawCode))
                .expiresAt(expiresAt)
                .build();

        codeRepository.save(entity);
        resendEmailService.sendOtpEmail(normalizedEmail, rawCode);

        log.info("OTP 已產生並發送: email={} expiresAt={}", normalizedEmail, expiresAt);
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
        String normalizedEmail = EmailNormalizer.normalize(email);
        EmailVerificationCode entity = codeRepository
                .findTopByEmailIgnoreCaseAndUsedFalseOrderByCreatedAtDesc(normalizedEmail)
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
        String hashedInput = hashOtp(normalizedEmail, code);
        boolean matched = hashedInput.equals(entity.getCode());

        // 向後相容：舊資料可能仍是 6 位明文 OTP，成功後會自動升級為 hash。
        if (!matched && entity.getCode() != null && entity.getCode().matches("^\\d{6}$")) {
            matched = entity.getCode().equals(code);
            if (matched) {
                entity.setCode(hashedInput);
            }
        }

        if (!matched) {
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
        String normalizedEmail = EmailNormalizer.normalize(email);
        var user = userRepository.findByEmailIgnoreCase(normalizedEmail)
                .orElseThrow(() -> new IllegalArgumentException("找不到此 Email 的帳號"));

        if (user.isEmailVerified()) {
            throw new IllegalArgumentException("此帳號已完成 Email 驗證");
        }

        generateAndSend(normalizedEmail);
    }

    /**
     * 產生 6 位數隨機驗證碼
     */
    String generateCode() {
        int num = secureRandom.nextInt(1_000_000);
        return String.format("%06d", num);
    }

    String hashOtp(String email, String code) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(otpHashSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = hmac.doFinal((email + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new IllegalStateException("OTP hash 失敗", e);
        }
    }
}
