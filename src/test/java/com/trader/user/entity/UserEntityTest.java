package com.trader.user.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * User Entity 預設值 & Builder 測試
 *
 * 確保新用戶的安全預設值：
 * - emailVerified = false（需 OTP 驗證）
 * - autoTradeEnabled = false（需用戶手動開啟）
 */
class UserEntityTest {

    @Nested
    @DisplayName("Builder 預設值")
    class BuilderDefaults {

        @Test
        @DisplayName("emailVerified 預設 false — 新用戶需完成 Email 驗證")
        void emailVerifiedDefaultsFalse() {
            User user = User.builder()
                    .userId("u1")
                    .email("test@test.com")
                    .passwordHash("hash")
                    .build();

            assertThat(user.isEmailVerified()).isFalse();
        }

        @Test
        @DisplayName("autoTradeEnabled 預設 false — 新用戶不會被自動跟單")
        void autoTradeEnabledDefaultsFalse() {
            User user = User.builder()
                    .userId("u1")
                    .email("test@test.com")
                    .passwordHash("hash")
                    .build();

            assertThat(user.isAutoTradeEnabled()).isFalse();
        }

        @Test
        @DisplayName("enabled 預設 true — 新帳號預設啟用")
        void enabledDefaultsTrue() {
            User user = User.builder()
                    .userId("u1")
                    .email("test@test.com")
                    .passwordHash("hash")
                    .build();

            assertThat(user.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("role 預設 USER — 新用戶為普通用戶")
        void roleDefaultsUser() {
            User user = User.builder()
                    .userId("u1")
                    .email("test@test.com")
                    .passwordHash("hash")
                    .build();

            assertThat(user.getRole()).isEqualTo(User.Role.USER);
        }

        @Test
        @DisplayName("discordNotificationEnabled 預設 false — 新用戶需自行設定 Discord webhook")
        void discordNotificationEnabledDefaultsFalse() {
            User user = User.builder()
                    .userId("u1")
                    .email("test@test.com")
                    .passwordHash("hash")
                    .build();

            assertThat(user.isDiscordNotificationEnabled()).isFalse();
        }

        @Test
        @DisplayName("lineNotificationEnabled 預設 true — LINE 通知預設開啟")
        void lineNotificationEnabledDefaultsTrue() {
            User user = User.builder()
                    .userId("u1")
                    .email("test@test.com")
                    .passwordHash("hash")
                    .build();

            assertThat(user.isLineNotificationEnabled()).isTrue();
        }

        @Test
        @DisplayName("Builder 明確設定會覆蓋預設值")
        void explicitValuesOverrideDefaults() {
            User user = User.builder()
                    .userId("u1")
                    .email("test@test.com")
                    .passwordHash("hash")
                    .emailVerified(true)
                    .autoTradeEnabled(true)
                    .enabled(false)
                    .role(User.Role.ADMIN)
                    .build();

            assertThat(user.isEmailVerified()).isTrue();
            assertThat(user.isAutoTradeEnabled()).isTrue();
            assertThat(user.isEnabled()).isFalse();
            assertThat(user.getRole()).isEqualTo(User.Role.ADMIN);
        }
    }
}
