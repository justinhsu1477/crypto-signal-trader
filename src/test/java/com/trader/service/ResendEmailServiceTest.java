package com.trader.service;

import com.trader.auth.config.EmailConfig;
import com.trader.auth.service.EmailTemplateService;
import com.trader.auth.service.ResendEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ResendEmailServiceTest {

    private EmailTemplateService emailTemplateService;

    @BeforeEach
    void setUp() {
        emailTemplateService = new EmailTemplateService();
        emailTemplateService.loadTemplates();
    }

    // ─── disabled 模式 ───

    @Nested
    @DisplayName("Email disabled 模式")
    class DisabledMode {

        @Test
        @DisplayName("enabled=false → 不拋異常（只 log，不呼叫 HTTP）")
        void disabledMode_doesNotThrow() {
            EmailConfig config = new EmailConfig(false, "", "noreply@hookfi.com", 10, 3, 5, 60, 3, "http://localhost:3000");
            ResendEmailService service = new ResendEmailService(config, emailTemplateService);

            // enabled=false → sendOtpEmail 只 log，不發 HTTP
            assertThatCode(() -> service.sendOtpEmail("user@example.com", "123456"))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("enabled=false → 多次呼叫不拋異常")
        void disabledMode_multipleCallsDoNotThrow() {
            EmailConfig config = new EmailConfig(false, "", "noreply@hookfi.com", 10, 3, 5, 60, 3, "http://localhost:3000");
            ResendEmailService service = new ResendEmailService(config, emailTemplateService);

            assertThatCode(() -> {
                service.sendOtpEmail("user1@example.com", "111111");
                service.sendOtpEmail("user2@example.com", "222222");
                service.sendOtpEmail("user3@example.com", "333333");
            }).doesNotThrowAnyException();
        }
    }

    // ─── enabled 模式（無真正 API key → 預期拋出例外） ───

    @Nested
    @DisplayName("Email enabled 模式（無有效 API key）")
    class EnabledModeNoKey {

        @Test
        @DisplayName("enabled=true 但 API key 無效 → 發信失敗拋 RuntimeException")
        void enabledButInvalidKey_throwsOnSend() {
            // enabled=true 但 API key 無效，HTTP 呼叫必定失敗
            EmailConfig config = new EmailConfig(true, "invalid-key", "noreply@hookfi.com", 10, 3, 5, 60, 3, "http://localhost:3000");
            ResendEmailService service = new ResendEmailService(config, emailTemplateService);

            // Resend API 會回 401/403 → 觸發 RuntimeException
            assertThatThrownBy(() -> service.sendOtpEmail("user@example.com", "123456"))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
