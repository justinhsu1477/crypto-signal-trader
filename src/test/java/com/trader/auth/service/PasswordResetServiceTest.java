package com.trader.auth.service;

import com.trader.auth.config.EmailConfig;
import com.trader.auth.dto.ChangePasswordRequest;
import com.trader.auth.dto.ResetPasswordRequest;
import com.trader.auth.entity.PasswordResetToken;
import com.trader.auth.repository.PasswordResetTokenRepository;
import com.trader.shared.config.AppConstants;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PasswordResetService 單元測試
 *
 * 覆蓋：修改密碼、忘記密碼（rate limit、防枚舉）、重設密碼（token 驗證、過期）
 */
class PasswordResetServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private PasswordResetTokenRepository resetTokenRepository;
    private ResendEmailService resendEmailService;
    private EmailConfig emailConfig;
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = new BCryptPasswordEncoder();
        resetTokenRepository = mock(PasswordResetTokenRepository.class);
        resendEmailService = mock(ResendEmailService.class);
        emailConfig = new EmailConfig(false, "", "noreply@hookfi.com", 10, 3, 5, 60, 3, "http://localhost:3000");
        service = new PasswordResetService(userRepository, passwordEncoder, resetTokenRepository, resendEmailService, emailConfig);
    }

    // ========== 修改密碼 ==========

    @Nested
    @DisplayName("修改密碼")
    class ChangePasswordTests {

        @Test
        @DisplayName("成功修改密碼 → 更新 passwordHash + passwordChangedAt")
        void changePasswordSuccess() {
            String userId = "user-1";
            String oldHash = new BCryptPasswordEncoder().encode("OldPass123");
            User user = User.builder().userId(userId).passwordHash(oldHash).build();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            ChangePasswordRequest req = new ChangePasswordRequest("OldPass123", "NewPass456", "NewPass456");
            service.changePassword(userId, req);

            verify(userRepository).save(argThat(u ->
                    !u.getPasswordHash().equals(oldHash) && u.getPasswordChangedAt() != null
            ));
        }

        @Test
        @DisplayName("新密碼 != 確認密碼 → 拋例外")
        void changePasswordMismatch() {
            ChangePasswordRequest req = new ChangePasswordRequest("OldPass123", "NewPass456", "DifferentPw");
            assertThatThrownBy(() -> service.changePassword("user-1", req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不一致");
        }

        @Test
        @DisplayName("現有密碼錯誤 → 拋例外")
        void changePasswordWrongCurrent() {
            String userId = "user-1";
            User user = User.builder().userId(userId)
                    .passwordHash(new BCryptPasswordEncoder().encode("RealPassword"))
                    .build();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            ChangePasswordRequest req = new ChangePasswordRequest("WrongPassword", "NewPass456", "NewPass456");
            assertThatThrownBy(() -> service.changePassword(userId, req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("目前密碼錯誤");
        }

        @Test
        @DisplayName("新密碼與現有密碼相同 → 拋例外")
        void changePasswordSameAsOld() {
            String userId = "user-1";
            User user = User.builder().userId(userId)
                    .passwordHash(new BCryptPasswordEncoder().encode("SamePass123"))
                    .build();
            when(userRepository.findById(userId)).thenReturn(Optional.of(user));

            ChangePasswordRequest req = new ChangePasswordRequest("SamePass123", "SamePass123", "SamePass123");
            assertThatThrownBy(() -> service.changePassword(userId, req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不能與目前密碼相同");
        }
    }

    // ========== 忘記密碼 ==========

    @Nested
    @DisplayName("忘記密碼（請求重設）")
    class ForgotPasswordTests {

        @Test
        @DisplayName("email 不存在 → 靜默返回（不拋例外）")
        void emailNotFound_silent() {
            when(userRepository.findByEmailIgnoreCase("unknown@test.com")).thenReturn(Optional.empty());

            assertThatCode(() -> service.requestPasswordReset("unknown@test.com"))
                    .doesNotThrowAnyException();

            verify(resetTokenRepository, never()).save(any());
            verify(resendEmailService, never()).sendPasswordResetEmail(anyString(), anyString());
        }

        @Test
        @DisplayName("rate limit 超過 → 靜默返回")
        void rateLimitExceeded_silent() {
            User user = User.builder().userId("user-1").email("test@test.com").build();
            when(userRepository.findByEmailIgnoreCase("test@test.com")).thenReturn(Optional.of(user));
            when(resetTokenRepository.countByUserIdAndCreatedAtAfter(eq("user-1"), any()))
                    .thenReturn(3L); // >= maxResetPerQuarterHour

            assertThatCode(() -> service.requestPasswordReset("test@test.com"))
                    .doesNotThrowAnyException();

            verify(resetTokenRepository, never()).save(any());
        }

        @Test
        @DisplayName("成功 → 存 token + 寄信")
        void success_savesTokenAndSendsEmail() {
            User user = User.builder().userId("user-1").email("test@test.com").build();
            when(userRepository.findByEmailIgnoreCase("test@test.com")).thenReturn(Optional.of(user));
            when(resetTokenRepository.countByUserIdAndCreatedAtAfter(eq("user-1"), any()))
                    .thenReturn(0L);

            service.requestPasswordReset("test@test.com");

            verify(resetTokenRepository).save(argThat(token ->
                    token.getUserId().equals("user-1")
                            && token.getTokenHash() != null
                            && !token.getTokenHash().isEmpty()
                            && token.getExpiresAt() != null
            ));
            verify(resendEmailService).sendPasswordResetEmail(
                    eq("test@test.com"),
                    argThat(url -> url.startsWith("http://localhost:3000/reset-password?token="))
            );
        }
    }

    // ========== 密碼重設 ==========

    @Nested
    @DisplayName("密碼重設（使用 token）")
    class ResetPasswordTests {

        @Test
        @DisplayName("token 無效 → 拋例外")
        void invalidToken() {
            when(resetTokenRepository.findByTokenHashAndUsedFalse(anyString()))
                    .thenReturn(Optional.empty());

            ResetPasswordRequest req = new ResetPasswordRequest("invalid-token", "NewPass123", "NewPass123");
            assertThatThrownBy(() -> service.resetPassword(req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("無效或已過期");
        }

        @Test
        @DisplayName("token 已過期 → 拋例外")
        void expiredToken() {
            PasswordResetToken token = PasswordResetToken.builder()
                    .userId("user-1")
                    .tokenHash(PasswordResetService.sha256("test-token"))
                    .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).minusHours(1))
                    .used(false)
                    .build();
            when(resetTokenRepository.findByTokenHashAndUsedFalse(anyString()))
                    .thenReturn(Optional.of(token));

            ResetPasswordRequest req = new ResetPasswordRequest("test-token", "NewPass123", "NewPass123");
            assertThatThrownBy(() -> service.resetPassword(req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("無效或已過期");
        }

        @Test
        @DisplayName("成功重設 → 更新密碼 + 標記 token 已用")
        void resetSuccess() {
            String rawToken = "valid-raw-token";
            String tokenHash = PasswordResetService.sha256(rawToken);

            PasswordResetToken token = PasswordResetToken.builder()
                    .userId("user-1")
                    .tokenHash(tokenHash)
                    .expiresAt(LocalDateTime.now(AppConstants.ZONE_ID).plusHours(1))
                    .used(false)
                    .build();
            when(resetTokenRepository.findByTokenHashAndUsedFalse(tokenHash))
                    .thenReturn(Optional.of(token));

            User user = User.builder().userId("user-1")
                    .passwordHash(new BCryptPasswordEncoder().encode("OldPass"))
                    .build();
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(resetTokenRepository.save(any(PasswordResetToken.class)))
                    .thenAnswer(inv -> inv.getArgument(0));

            ResetPasswordRequest req = new ResetPasswordRequest(rawToken, "NewPass123", "NewPass123");
            service.resetPassword(req);

            // token 標記已用
            verify(resetTokenRepository).save(argThat(PasswordResetToken::isUsed));
            // 用戶密碼更新 + passwordChangedAt 設定
            verify(userRepository).save(argThat(u ->
                    u.getPasswordChangedAt() != null
            ));
        }

        @Test
        @DisplayName("新密碼 != 確認密碼 → 拋例外")
        void resetPasswordMismatch() {
            ResetPasswordRequest req = new ResetPasswordRequest("some-token", "NewPass123", "DifferentPw");
            assertThatThrownBy(() -> service.resetPassword(req))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不一致");
        }
    }

    // ========== SHA-256 ==========

    @Test
    @DisplayName("SHA-256 hash 一致性")
    void sha256Consistency() {
        String hash1 = PasswordResetService.sha256("test-input");
        String hash2 = PasswordResetService.sha256("test-input");
        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).hasSize(64); // SHA-256 hex = 64 chars
    }
}
