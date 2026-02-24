package com.trader.auth.config;

import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AdminUserInitializer 單元測試
 */
class AdminUserInitializerTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private AdminUserInitializer initializer;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode(any())).thenReturn("$2a$encoded");

        initializer = new AdminUserInitializer(userRepository, passwordEncoder);
    }

    @Test
    @DisplayName("已有 ADMIN 用戶 → 跳過建立")
    void skipsWhenAdminExists() {
        when(userRepository.existsByRole(User.Role.ADMIN)).thenReturn(true);

        initializer.ensureAdminExists();

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("無 ADMIN 用戶 → 自動建立")
    void createsAdminWhenNoneExists() {
        when(userRepository.existsByRole(User.Role.ADMIN)).thenReturn(false);
        when(userRepository.existsByEmail("admin@hookfi.com")).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        initializer.ensureAdminExists();

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        User admin = captor.getValue();
        assertThat(admin.getEmail()).isEqualTo("admin@hookfi.com");
        assertThat(admin.getRole()).isEqualTo(User.Role.ADMIN);
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.isEmailVerified()).isTrue();
        assertThat(admin.isAutoTradeEnabled()).isFalse();
        assertThat(admin.getPasswordHash()).isEqualTo("$2a$encoded");
        assertThat(admin.getUserId()).isNotBlank();
        assertThat(admin.getName()).isEqualTo("System Admin");
    }

    @Test
    @DisplayName("Admin email 已被普通用戶佔用 → 跳過建立")
    void skipsWhenEmailTakenByRegularUser() {
        when(userRepository.existsByRole(User.Role.ADMIN)).thenReturn(false);
        when(userRepository.existsByEmail("admin@hookfi.com")).thenReturn(true);

        initializer.ensureAdminExists();

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("建立的 Admin 密碼有經過 BCrypt 編碼")
    void passwordIsEncoded() {
        when(userRepository.existsByRole(User.Role.ADMIN)).thenReturn(false);
        when(userRepository.existsByEmail("admin@hookfi.com")).thenReturn(false);
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        initializer.ensureAdminExists();

        verify(passwordEncoder).encode("Admin1234!");
    }
}
