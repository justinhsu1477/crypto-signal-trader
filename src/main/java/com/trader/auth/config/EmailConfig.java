package com.trader.auth.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Email 發信設定（Resend API）
 *
 * 對應 application.yml:
 * email:
 *   enabled: true/false
 *   resend-api-key: re_xxx
 *   from-address: noreply@hookfi.com
 *   otp-expiry-minutes: 10
 *   max-attempts-per-code: 3
 *   max-sends-per-hour: 5
 */
@Slf4j
@Getter
@ConfigurationProperties(prefix = "email")
public class EmailConfig {

    private final boolean enabled;
    private final String resendApiKey;
    private final String fromAddress;
    private final int otpExpiryMinutes;
    private final int maxAttemptsPerCode;
    private final int maxSendsPerHour;

    public EmailConfig(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String resendApiKey,
            @DefaultValue("noreply@hookfi.com") String fromAddress,
            @DefaultValue("10") int otpExpiryMinutes,
            @DefaultValue("3") int maxAttemptsPerCode,
            @DefaultValue("5") int maxSendsPerHour) {
        this.enabled = enabled;
        this.resendApiKey = resendApiKey;
        this.fromAddress = fromAddress;
        this.otpExpiryMinutes = otpExpiryMinutes;
        this.maxAttemptsPerCode = maxAttemptsPerCode;
        this.maxSendsPerHour = maxSendsPerHour;
    }

    @PostConstruct
    void validate() {
        if (enabled && (resendApiKey == null || resendApiKey.isBlank())) {
            log.error("⚠ email.enabled=true 但 email.resend-api-key 未設定！Email 發送將失敗");
        }
        if (!enabled) {
            log.info("📧 Email 功能已停用（email.enabled=false），OTP 將只記錄到 log");
        }
    }
}
