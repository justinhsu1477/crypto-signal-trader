package com.trader.service;

import com.trader.auth.config.EmailConfig;
import com.trader.auth.entity.EmailVerificationCode;
import com.trader.auth.repository.EmailVerificationCodeRepository;
import com.trader.auth.service.EmailVerificationService;
import com.trader.auth.service.ResendEmailService;
import com.trader.shared.config.AppConstants;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmailVerificationServiceTest {

    private EmailVerificationCodeRepository codeRepository;
    private UserRepository userRepository;
    private ResendEmailService resendEmailService;
    private EmailConfig emailConfig;
    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        codeRepository = mock(EmailVerificationCodeRepository.class);
        userRepository = mock(UserRepository.class);
        resendEmailService = mock(ResendEmailService.class);
        emailConfig = new EmailConfig(false, "", "noreply@hookfi.com", 10, 3, 5, 60, 3, "http://localhost:3000");
        service = new EmailVerificationService(codeRepository, userRepository, resendEmailService, emailConfig);
    }

    // ─── generateAndSend ───

    @Nested
    @DisplayName("generateAndSend — 產生 OTP 並發送")
    class GenerateAndSend {

        @Test
        @DisplayName("成功 → 儲存 OTP 並呼叫發信")
        void success_savesCodeAndSendsEmail() {
            when(codeRepository.countByEmailAndCreatedAtAfter(anyString(), any())).thenReturn(0L);
            when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.generateAndSend("user@example.com");

            ArgumentCaptor<EmailVerificationCode> captor = ArgumentCaptor.forClass(EmailVerificationCode.class);
            verify(codeRepository).save(captor.capture());

            EmailVerificationCode saved = captor.getValue();
            assertThat(saved.getEmail()).isEqualTo("user@example.com");
            assertThat(saved.getCode()).hasSize(6).matches("\\d{6}");
            assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now(AppConstants.ZONE_ID));

            verify(resendEmailService).sendOtpEmail(eq("user@example.com"), eq(saved.getCode()));
        }

        @Test
        @DisplayName("超過每小時發送上限 → 拋出 IllegalStateException")
        void rateLimitExceeded_throwsException() {
            when(codeRepository.countByEmailAndCreatedAtAfter(anyString(), any())).thenReturn(5L);

            assertThatThrownBy(() -> service.generateAndSend("user@example.com"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("發送過於頻繁");

            verify(codeRepository, never()).save(any());
            verify(resendEmailService, never()).sendOtpEmail(anyString(), anyString());
        }

        @Test
        @DisplayName("未達上限 → 正常發送")
        void belowLimit_sendsNormally() {
            when(codeRepository.countByEmailAndCreatedAtAfter(anyString(), any())).thenReturn(4L);
            when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatCode(() -> service.generateAndSend("user@example.com"))
                    .doesNotThrowAnyException();

            verify(resendEmailService).sendOtpEmail(anyString(), anyString());
        }
    }

    // ─── verifyCode ───

    @Nested
    @DisplayName("verifyCode — 驗證 OTP")
    class VerifyCode {

        @Test
        @DisplayName("正確的驗證碼 → 回傳 true 並標記 used")
        void correctCode_returnsTrueAndMarksUsed() {
            EmailVerificationCode entity = EmailVerificationCode.builder()
                    .id(1L)
                    .email("user@example.com")
                    .code("123456")
                    .attempts(0)
                    .used(false)
                    .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).plusMinutes(5))
                    .build();

            when(codeRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("user@example.com"))
                    .thenReturn(Optional.of(entity));
            when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            boolean result = service.verifyCode("user@example.com", "123456");

            assertThat(result).isTrue();
            assertThat(entity.isUsed()).isTrue();
            verify(codeRepository).save(entity);
        }

        @Test
        @DisplayName("找不到 code → 拋出「驗證碼不存在或已過期」")
        void noCode_throwsException() {
            when(codeRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("user@example.com"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.verifyCode("user@example.com", "000000"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("驗證碼不存在或已過期");
        }

        @Test
        @DisplayName("驗證碼已過期 → 拋出「驗證碼已過期，請重新發送」")
        void expiredCode_throwsException() {
            EmailVerificationCode entity = EmailVerificationCode.builder()
                    .id(1L)
                    .email("user@example.com")
                    .code("123456")
                    .attempts(0)
                    .used(false)
                    .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).minusMinutes(1))
                    .build();

            when(codeRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("user@example.com"))
                    .thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.verifyCode("user@example.com", "123456"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("驗證碼已過期");
        }

        @Test
        @DisplayName("超過最大嘗試次數 → 拋出「驗證碼已失效」")
        void maxAttemptsReached_throwsException() {
            EmailVerificationCode entity = EmailVerificationCode.builder()
                    .id(1L)
                    .email("user@example.com")
                    .code("123456")
                    .attempts(3)  // maxAttemptsPerCode = 3
                    .used(false)
                    .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).plusMinutes(5))
                    .build();

            when(codeRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("user@example.com"))
                    .thenReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.verifyCode("user@example.com", "999999"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("驗證碼已失效");
        }

        @Test
        @DisplayName("錯誤的驗證碼（剩餘次數 > 0）→ attempts+1，拋出含剩餘次數")
        void wrongCode_incrementsAttemptsAndThrows() {
            EmailVerificationCode entity = EmailVerificationCode.builder()
                    .id(1L)
                    .email("user@example.com")
                    .code("123456")
                    .attempts(1)
                    .used(false)
                    .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).plusMinutes(5))
                    .build();

            when(codeRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("user@example.com"))
                    .thenReturn(Optional.of(entity));
            when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() -> service.verifyCode("user@example.com", "000000"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("驗證碼錯誤")
                    .hasMessageContaining("1 次");

            assertThat(entity.getAttempts()).isEqualTo(2);
            verify(codeRepository).save(entity);
        }

        @Test
        @DisplayName("錯誤的驗證碼導致 attempts 達上限 → 拋出「驗證碼已失效」")
        void wrongCode_reachesMaxAttempts_throwsInvalidated() {
            EmailVerificationCode entity = EmailVerificationCode.builder()
                    .id(1L)
                    .email("user@example.com")
                    .code("123456")
                    .attempts(2)  // 再錯一次 → 3 = max
                    .used(false)
                    .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).plusMinutes(5))
                    .build();

            when(codeRepository.findTopByEmailAndUsedFalseOrderByCreatedAtDesc("user@example.com"))
                    .thenReturn(Optional.of(entity));
            when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatThrownBy(() -> service.verifyCode("user@example.com", "000000"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("驗證碼已失效");

            assertThat(entity.getAttempts()).isEqualTo(3);
        }
    }

    // ─── resendCode ───

    @Nested
    @DisplayName("resendCode — 重新發送")
    class ResendCode {

        @Test
        @DisplayName("用戶存在且未驗證 → 呼叫 generateAndSend")
        void userExistsAndNotVerified_sendsCode() {
            User user = User.builder()
                    .userId("u1")
                    .email("user@example.com")
                    .emailVerified(false)
                    .build();

            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
            when(codeRepository.countByEmailAndCreatedAtAfter(anyString(), any())).thenReturn(0L);
            when(codeRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThatCode(() -> service.resendCode("user@example.com"))
                    .doesNotThrowAnyException();

            verify(resendEmailService).sendOtpEmail(eq("user@example.com"), anyString());
        }

        @Test
        @DisplayName("用戶不存在 → 拋出「找不到此 Email 的帳號」")
        void userNotFound_throwsException() {
            when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resendCode("nobody@example.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("找不到此 Email 的帳號");

            verify(resendEmailService, never()).sendOtpEmail(anyString(), anyString());
        }

        @Test
        @DisplayName("用戶已驗證 → 拋出「此帳號已完成 Email 驗證」")
        void userAlreadyVerified_throwsException() {
            User user = User.builder()
                    .userId("u1")
                    .email("user@example.com")
                    .emailVerified(true)
                    .build();

            when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> service.resendCode("user@example.com"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("此帳號已完成 Email 驗證");

            verify(resendEmailService, never()).sendOtpEmail(anyString(), anyString());
        }
    }
}
