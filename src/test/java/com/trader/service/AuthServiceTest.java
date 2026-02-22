package com.trader.service;

import com.trader.auth.dto.LoginRequest;
import com.trader.auth.dto.LoginResponse;
import com.trader.auth.dto.RegisterRequest;
import com.trader.auth.service.AuthService;
import com.trader.auth.service.JwtService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    private UserRepository userRepository;
    private PasswordEncoder passwordEncoder;
    private JwtService jwtService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        passwordEncoder = mock(PasswordEncoder.class);
        jwtService = mock(JwtService.class);
        authService = new AuthService(userRepository, passwordEncoder, jwtService);
    }

    @Nested
    @DisplayName("用戶註冊")
    class Register {

        @Test
        @DisplayName("註冊成功 → 密碼已加密後存入 DB")
        void registerSuccess_savesUserWithHashedPassword() {
            RegisterRequest request = new RegisterRequest();
            request.setEmail("test@example.com");
            request.setPassword("password123");
            request.setName("Test User");

            when(userRepository.existsByEmail("test@example.com")).thenReturn(false);
            when(passwordEncoder.encode("password123")).thenReturn("$2a$10$hashedPassword");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = authService.register(request);

            assertThat(result.getEmail()).isEqualTo("test@example.com");
            assertThat(result.getName()).isEqualTo("Test User");
            assertThat(result.getUserId()).isNotNull().isNotBlank();

            ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
            verify(userRepository).save(userCaptor.capture());
            User savedUser = userCaptor.getValue();
            assertThat(savedUser.getPasswordHash()).isEqualTo("$2a$10$hashedPassword");
            assertThat(savedUser.getPasswordHash()).isNotEqualTo("password123");
        }

        @Test
        @DisplayName("Email 已被註冊 → 拋出 IllegalArgumentException")
        void registerDuplicateEmail_throwsException() {
            RegisterRequest request = new RegisterRequest();
            request.setEmail("existing@example.com");
            request.setPassword("password123");

            when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

            assertThatThrownBy(() -> authService.register(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Email 已被註冊");

            verify(userRepository, never()).save(any());
        }

        @Test
        @DisplayName("註冊成功 → userId 為 UUID 格式")
        void registerSuccess_userIdIsUuid() {
            RegisterRequest request = new RegisterRequest();
            request.setEmail("new@example.com");
            request.setPassword("password123");

            when(userRepository.existsByEmail(anyString())).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("hashed");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            User result = authService.register(request);

            assertThat(result.getUserId()).matches(
                    "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
        }
    }

    @Nested
    @DisplayName("用戶登入")
    class Login {

        @Test
        @DisplayName("登入成功 → 回傳 LoginResponse 含 token 和 refreshToken")
        void loginSuccess_returnsLoginResponse() {
            LoginRequest request = new LoginRequest();
            request.setEmail("test@example.com");
            request.setPassword("password123");

            User user = User.builder()
                    .userId("test-uuid")
                    .email("test@example.com")
                    .passwordHash("$2a$10$hashedPassword")
                    .name("Test User")
                    .role(User.Role.USER)
                    .enabled(true)
                    .build();

            when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("password123", "$2a$10$hashedPassword")).thenReturn(true);
            when(jwtService.generateToken("test-uuid", "USER")).thenReturn("jwt-access-token");
            when(jwtService.generateRefreshToken("test-uuid", "USER")).thenReturn("jwt-refresh-token");
            when(jwtService.getExpirationMs()).thenReturn(86400000L);

            LoginResponse response = authService.login(request);

            assertThat(response.getToken()).isEqualTo("jwt-access-token");
            assertThat(response.getRefreshToken()).isEqualTo("jwt-refresh-token");
            assertThat(response.getExpiresIn()).isEqualTo(86400L);
            assertThat(response.getUserId()).isEqualTo("test-uuid");
            assertThat(response.getEmail()).isEqualTo("test@example.com");
            assertThat(response.getRole()).isEqualTo("USER");
        }

        @Test
        @DisplayName("密碼錯誤 → 拋出「帳號或密碼錯誤」")
        void loginWrongPassword_throwsException() {
            LoginRequest request = new LoginRequest();
            request.setEmail("test@example.com");
            request.setPassword("wrong-password");

            User user = User.builder()
                    .userId("test-uuid")
                    .email("test@example.com")
                    .passwordHash("$2a$10$hashedPassword")
                    .enabled(true)
                    .build();

            when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches("wrong-password", "$2a$10$hashedPassword")).thenReturn(false);

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("帳號或密碼錯誤");

            verify(jwtService, never()).generateToken(anyString(), anyString());
        }

        @Test
        @DisplayName("Email 不存在 → 拋出相同的「帳號或密碼錯誤」（防列舉攻擊）")
        void loginEmailNotFound_throwsSameMessage() {
            LoginRequest request = new LoginRequest();
            request.setEmail("nonexistent@example.com");
            request.setPassword("password123");

            when(userRepository.findByEmail("nonexistent@example.com")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("帳號或密碼錯誤");
        }

        @Test
        @DisplayName("帳號已停用 → 拋出「帳號已停用」")
        void loginDisabledAccount_throwsException() {
            LoginRequest request = new LoginRequest();
            request.setEmail("disabled@example.com");
            request.setPassword("password123");

            User user = User.builder()
                    .userId("disabled-uuid")
                    .email("disabled@example.com")
                    .passwordHash("$2a$10$hashedPassword")
                    .enabled(false)
                    .build();

            when(userRepository.findByEmail("disabled@example.com")).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("帳號已停用");

            verify(passwordEncoder, never()).matches(anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("刷新 Token")
    class RefreshToken {

        @Test
        @DisplayName("有效 Refresh Token → 回傳新的 token pair")
        void validRefreshToken_returnsNewTokenPair() {
            String oldRefreshToken = "old-refresh-token";

            when(jwtService.validateToken(oldRefreshToken)).thenReturn(true);
            when(jwtService.extractUserId(oldRefreshToken)).thenReturn("test-uuid");

            User user = User.builder()
                    .userId("test-uuid")
                    .email("test@example.com")
                    .enabled(true)
                    .build();

            when(userRepository.findById("test-uuid")).thenReturn(Optional.of(user));
            when(jwtService.generateToken("test-uuid", "USER")).thenReturn("new-access-token");
            when(jwtService.generateRefreshToken("test-uuid", "USER")).thenReturn("new-refresh-token");
            when(jwtService.getExpirationMs()).thenReturn(86400000L);

            LoginResponse response = authService.refreshToken(oldRefreshToken);

            assertThat(response.getToken()).isEqualTo("new-access-token");
            assertThat(response.getRefreshToken()).isEqualTo("new-refresh-token");
            assertThat(response.getUserId()).isEqualTo("test-uuid");
            assertThat(response.getEmail()).isEqualTo("test@example.com");
            assertThat(response.getExpiresIn()).isEqualTo(86400L);
            assertThat(response.getRole()).isEqualTo("USER");
        }

        @Test
        @DisplayName("無效/過期 Refresh Token → 拋出異常")
        void invalidRefreshToken_throwsException() {
            when(jwtService.validateToken("bad-token")).thenReturn(false);

            assertThatThrownBy(() -> authService.refreshToken("bad-token"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Refresh Token 無效或已過期");

            verify(jwtService, never()).extractUserId(anyString());
        }

        @Test
        @DisplayName("用戶不存在 → 拋出「用戶不存在」")
        void userNotFound_throwsException() {
            when(jwtService.validateToken("valid-token")).thenReturn(true);
            when(jwtService.extractUserId("valid-token")).thenReturn("deleted-user");
            when(userRepository.findById("deleted-user")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> authService.refreshToken("valid-token"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("用戶不存在");
        }

        @Test
        @DisplayName("帳號已停用 → 拋出「帳號已停用」，不發新 token")
        void disabledAccount_throwsException() {
            when(jwtService.validateToken("valid-token")).thenReturn(true);
            when(jwtService.extractUserId("valid-token")).thenReturn("disabled-uuid");

            User user = User.builder()
                    .userId("disabled-uuid")
                    .email("disabled@example.com")
                    .enabled(false)
                    .build();

            when(userRepository.findById("disabled-uuid")).thenReturn(Optional.of(user));

            assertThatThrownBy(() -> authService.refreshToken("valid-token"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("帳號已停用");

            verify(jwtService, never()).generateToken(anyString(), anyString());
        }
    }
}
