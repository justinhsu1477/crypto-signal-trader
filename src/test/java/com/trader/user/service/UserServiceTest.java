package com.trader.user.service;

import com.trader.shared.util.AesEncryptionUtil;
import com.trader.user.entity.User;
import com.trader.user.repository.*;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UserService 單元測試
 *
 * 重點覆蓋：deleteAccount（GDPR 帳號刪除）
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserApiKeyRepository userApiKeyRepository;

    @Mock
    private UserDiscordWebhookRepository userDiscordWebhookRepository;

    @Mock
    private UserLineBindingRepository userLineBindingRepository;

    @Mock
    private LineLinkingCodeRepository lineLinkingCodeRepository;

    @Mock
    private UserNotificationPreferencesRepository userNotificationPreferencesRepository;

    @Mock
    private UserTradeSettingsRepository userTradeSettingsRepository;

    @Mock
    private AesEncryptionUtil aesEncryptionUtil;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private UserService service;

    private static final String USER_ID = "user-123";
    private static final String USER_EMAIL = "test@example.com";

    private User createTestUser() {
        return User.builder()
                .userId(USER_ID)
                .email(USER_EMAIL)
                .name("Test User")
                .passwordHash("$2a$10$hashedpassword")
                .role(User.Role.USER)
                .enabled(true)
                .autoTradeEnabled(true)
                .discordNotificationEnabled(true)
                .lineNotificationEnabled(true)
                .emailVerified(true)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ==================== deleteAccount ====================

    @Nested
    @DisplayName("deleteAccount — GDPR 帳號刪除")
    class DeleteAccount {

        @Test
        @DisplayName("成功刪除 — 匿名化 PII + 停用帳號 + 清除關聯資料")
        void deletesAccountSuccessfully() {
            User user = createTestUser();
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.deleteAccount(USER_ID);

            // 驗證 User 物件被正確匿名化
            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            User saved = captor.getValue();

            // PII 匿名化
            assertThat(saved.getEmail()).startsWith("deleted_").endsWith("@deleted.com");
            assertThat(saved.getEmail()).isNotEqualTo(USER_EMAIL);
            assertThat(saved.getName()).isEqualTo("Deleted User");
            assertThat(saved.getPasswordHash()).isEqualTo("ACCOUNT_DELETED");

            // 帳號停用
            assertThat(saved.isEnabled()).isFalse();
            assertThat(saved.isAutoTradeEnabled()).isFalse();
            assertThat(saved.isDiscordNotificationEnabled()).isFalse();
            assertThat(saved.isLineNotificationEnabled()).isFalse();
            assertThat(saved.isEmailVerified()).isFalse();

            // 關聯資料清除
            verify(userApiKeyRepository).deleteByUserId(USER_ID);
            verify(userDiscordWebhookRepository).deleteByUserId(USER_ID);
            verify(userLineBindingRepository).deleteByUserId(USER_ID);
            verify(lineLinkingCodeRepository).deleteByUserId(USER_ID);
            verify(userNotificationPreferencesRepository).deleteById(USER_ID);
            verify(userTradeSettingsRepository).deleteById(USER_ID);
        }

        @Test
        @DisplayName("用戶不存在 — 拋出 IllegalArgumentException")
        void throwsWhenUserNotFound() {
            when(userRepository.findById("non-existent")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.deleteAccount("non-existent"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("用戶不存在");

            // 確保不會刪除任何資料
            verify(userRepository, never()).save(any());
            verify(userApiKeyRepository, never()).deleteByUserId(any());
        }

        @Test
        @DisplayName("匿名化 email 格式正確且唯一")
        void anonymizedEmailIsUniqueFormat() {
            User user = createTestUser();
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            service.deleteAccount(USER_ID);

            ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(captor.capture());
            User saved = captor.getValue();

            // 格式：deleted_xxxxxxxx@deleted.com（8 字元 UUID 片段）
            assertThat(saved.getEmail()).matches("deleted_[a-f0-9]{8}@deleted\\.com");
        }

        @Test
        @DisplayName("已停用帳號也能正常處理")
        void handlesAlreadyDisabledAccount() {
            User user = createTestUser();
            user.setEnabled(false);
            user.setAutoTradeEnabled(false);
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            // 不應拋出例外
            assertThatCode(() -> service.deleteAccount(USER_ID)).doesNotThrowAnyException();

            verify(userRepository).save(any(User.class));
            verify(userApiKeyRepository).deleteByUserId(USER_ID);
        }
    }
}
