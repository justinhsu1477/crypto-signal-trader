package com.trader.auth.controller;

import com.trader.auth.dto.*;
import com.trader.auth.exception.EmailNotVerifiedException;
import com.trader.auth.service.AuthService;
import com.trader.auth.service.EmailVerificationService;
import com.trader.auth.service.JwtService;
import com.trader.auth.service.PasswordResetService;
import com.trader.shared.service.AuditService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AuthController 單元測試
 *
 * 測試重點：
 * - 每個端點的成功/失敗路徑
 * - HttpOnly Cookie 正確設定/清除
 * - 錯誤狀態碼對應（400/401/403/429）
 * - getClientIp 取 X-Forwarded-For / X-Real-IP / remoteAddr
 */
class AuthControllerTest {

    private AuthService authService;
    private AuditService auditService;
    private EmailVerificationService emailVerificationService;
    private PasswordResetService passwordResetService;
    private UserRepository userRepository;
    private JwtService jwtService;
    private AuthController controller;

    private HttpServletRequest httpRequest;
    private HttpServletResponse httpResponse;

    @BeforeEach
    void setUp() {
        authService = mock(AuthService.class);
        auditService = mock(AuditService.class);
        emailVerificationService = mock(EmailVerificationService.class);
        passwordResetService = mock(PasswordResetService.class);
        userRepository = mock(UserRepository.class);
        jwtService = mock(JwtService.class);

        controller = new AuthController(
                authService, auditService, emailVerificationService,
                passwordResetService, userRepository, jwtService);

        httpRequest = mock(HttpServletRequest.class);
        httpResponse = mock(HttpServletResponse.class);

        when(httpRequest.getRemoteAddr()).thenReturn("127.0.0.1");
        when(jwtService.getExpirationMs()).thenReturn(1800000L);        // 30 min
        when(jwtService.getRefreshExpirationMs()).thenReturn(259200000L); // 3 days
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== register ====================

    @Nested
    @DisplayName("POST /register")
    class Register {

        @Test
        @DisplayName("註冊成功 → 200 + RegisterResponse")
        void success() {
            RegisterRequest req = new RegisterRequest();
            req.setEmail("test@email.com");
            req.setPassword("password123");
            req.setName("Test");

            User mockUser = User.builder()
                    .userId("user-123")
                    .email("test@email.com")
                    .build();
            when(authService.register(any())).thenReturn(mockUser);

            ResponseEntity<?> response = controller.register(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            RegisterResponse body = (RegisterResponse) response.getBody();
            assertThat(body.getUserId()).isEqualTo("user-123");
            assertThat(body.getEmail()).isEqualTo("test@email.com");
            assertThat(body.isNeedsVerification()).isTrue();
        }

        @Test
        @DisplayName("Email 已被註冊（IllegalArgument）→ 400")
        void emailExists_returns400() {
            RegisterRequest req = new RegisterRequest();
            req.setEmail("dup@email.com");
            req.setPassword("password123");

            when(authService.register(any()))
                    .thenThrow(new IllegalArgumentException("此 Email 已被註冊"));

            ResponseEntity<?> response = controller.register(req);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("註冊頻率限制（IllegalState）→ 429")
        void rateLimited_returns429() {
            RegisterRequest req = new RegisterRequest();
            req.setEmail("test@email.com");
            req.setPassword("password123");

            when(authService.register(any()))
                    .thenThrow(new IllegalStateException("註冊請求過於頻繁"));

            ResponseEntity<?> response = controller.register(req);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    // ==================== login ====================

    @Nested
    @DisplayName("POST /login")
    class Login {

        @Test
        @DisplayName("登入成功 → 200 + 設定 Cookie + 審計紀錄")
        void success() {
            LoginRequest req = new LoginRequest();
            req.setEmail("test@email.com");
            req.setPassword("pass");

            LoginResponse loginResp = LoginResponse.builder()
                    .token("access-jwt")
                    .refreshToken("refresh-jwt")
                    .userId("user-1")
                    .email("test@email.com")
                    .role("USER")
                    .build();
            when(authService.login(any())).thenReturn(loginResp);

            ResponseEntity<?> response = controller.login(req, httpRequest, httpResponse);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // 驗證 Cookie 設定
            verify(httpResponse, atLeast(2)).addHeader(eq("Set-Cookie"), anyString());
            // 驗證審計紀錄
            verify(auditService).log(eq("user-1"), eq("LOGIN"), anyString(), eq("SUCCESS"),
                    anyString(), contains("test@email.com"));

            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("userId", "user-1");
            assertThat(body).containsEntry("role", "USER");
            assertThat(body).containsEntry("expiresIn", 1800L);
        }

        @Test
        @DisplayName("Email 未驗證 → 403 + EMAIL_NOT_VERIFIED")
        void emailNotVerified_returns403() {
            LoginRequest req = new LoginRequest();
            req.setEmail("unverified@email.com");
            req.setPassword("pass");

            when(authService.login(any()))
                    .thenThrow(new EmailNotVerifiedException("Email 未驗證"));

            ResponseEntity<?> response = controller.login(req, httpRequest, httpResponse);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("error", "EMAIL_NOT_VERIFIED");
        }

        @Test
        @DisplayName("密碼錯誤（IllegalArgument）→ 401 + 審計失敗")
        void wrongPassword_returns401() {
            LoginRequest req = new LoginRequest();
            req.setEmail("test@email.com");
            req.setPassword("wrong");

            when(authService.login(any()))
                    .thenThrow(new IllegalArgumentException("密碼錯誤"));

            ResponseEntity<?> response = controller.login(req, httpRequest, httpResponse);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(auditService).logFailedAuth(eq("test@email.com"), anyString(), eq("密碼錯誤"));
        }

        @Test
        @DisplayName("X-Forwarded-For → 取第一個 IP")
        void xForwardedFor_extractsFirstIp() {
            LoginRequest req = new LoginRequest();
            req.setEmail("test@email.com");
            req.setPassword("pass");

            when(httpRequest.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4, 5.6.7.8");

            LoginResponse loginResp = LoginResponse.builder()
                    .token("t").refreshToken("r").userId("u1")
                    .email("test@email.com").role("USER").build();
            when(authService.login(any())).thenReturn(loginResp);

            controller.login(req, httpRequest, httpResponse);

            verify(auditService).log(anyString(), eq("LOGIN"), anyString(), eq("SUCCESS"),
                    eq("1.2.3.4"), anyString());
        }

        @Test
        @DisplayName("X-Real-IP → 使用 Real-IP")
        void xRealIp_extractsIp() {
            LoginRequest req = new LoginRequest();
            req.setEmail("test@email.com");
            req.setPassword("pass");

            when(httpRequest.getHeader("X-Forwarded-For")).thenReturn(null);
            when(httpRequest.getHeader("X-Real-IP")).thenReturn("10.0.0.1");

            LoginResponse loginResp = LoginResponse.builder()
                    .token("t").refreshToken("r").userId("u1")
                    .email("test@email.com").role("USER").build();
            when(authService.login(any())).thenReturn(loginResp);

            controller.login(req, httpRequest, httpResponse);

            verify(auditService).log(anyString(), eq("LOGIN"), anyString(), eq("SUCCESS"),
                    eq("10.0.0.1"), anyString());
        }
    }

    // ==================== verify-email ====================

    @Nested
    @DisplayName("POST /verify-email")
    class VerifyEmail {

        @Test
        @DisplayName("驗證成功 → 200 + 設定 emailVerified=true")
        void success() {
            VerifyEmailRequest req = new VerifyEmailRequest("test@email.com", "123456");

            User user = User.builder().userId("u1").email("test@email.com").build();
            when(userRepository.findByEmailIgnoreCase("test@email.com")).thenReturn(Optional.of(user));

            ResponseEntity<?> response = controller.verifyEmail(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(emailVerificationService).verifyCode("test@email.com", "123456");
            assertThat(user.isEmailVerified()).isTrue();
            verify(userRepository).save(user);
        }

        @Test
        @DisplayName("驗證碼錯誤（IllegalArgument）→ 400")
        void wrongCode_returns400() {
            VerifyEmailRequest req = new VerifyEmailRequest("test@email.com", "000000");

            doThrow(new IllegalArgumentException("驗證碼錯誤"))
                    .when(emailVerificationService).verifyCode(anyString(), anyString());

            ResponseEntity<?> response = controller.verifyEmail(req);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ==================== resend-code ====================

    @Nested
    @DisplayName("POST /resend-code")
    class ResendCode {

        @Test
        @DisplayName("重發成功 → 200")
        void success() {
            ResendCodeRequest req = new ResendCodeRequest("test@email.com");

            ResponseEntity<?> response = controller.resendCode(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(emailVerificationService).resendCode("test@email.com");
        }

        @Test
        @DisplayName("Email 不存在（IllegalArgument）→ 200（防枚舉）")
        void emailNotFound_returns200() {
            ResendCodeRequest req = new ResendCodeRequest("noexist@email.com");

            doThrow(new IllegalArgumentException("找不到此 Email"))
                    .when(emailVerificationService).resendCode(anyString());

            ResponseEntity<?> response = controller.resendCode(req);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("頻率限制（IllegalState）→ 429")
        void rateLimited_returns429() {
            ResendCodeRequest req = new ResendCodeRequest("test@email.com");

            doThrow(new IllegalStateException("請等候 60 秒"))
                    .when(emailVerificationService).resendCode(anyString());

            ResponseEntity<?> response = controller.resendCode(req);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    // ==================== refresh ====================

    @Nested
    @DisplayName("POST /refresh")
    class Refresh {

        @Test
        @DisplayName("刷新成功 → 200 + 設定新 Cookie")
        void success() {
            // 模擬 Cookie 中有 Refresh Token
            jakarta.servlet.http.Cookie[] cookies = {
                    new jakarta.servlet.http.Cookie("REFRESH_TOKEN", "old-refresh-jwt")
            };
            when(httpRequest.getCookies()).thenReturn(cookies);

            LoginResponse refreshResp = LoginResponse.builder()
                    .token("new-access").refreshToken("new-refresh")
                    .userId("u1").email("test@email.com").role("USER")
                    .build();
            when(authService.refreshToken("old-refresh-jwt")).thenReturn(refreshResp);

            ResponseEntity<?> response = controller.refreshToken(httpRequest, httpResponse);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(httpResponse, atLeast(2)).addHeader(eq("Set-Cookie"), anyString());
            verify(auditService).log(eq("u1"), eq("REFRESH_TOKEN"), anyString(),
                    eq("SUCCESS"), anyString(), eq(""));
        }

        @Test
        @DisplayName("無 Refresh Token Cookie → 401")
        void noRefreshCookie_returns401() {
            when(httpRequest.getCookies()).thenReturn(null);

            ResponseEntity<?> response = controller.refreshToken(httpRequest, httpResponse);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Refresh Token 無效（IllegalArgument）→ 401 + 清除 Cookie")
        void invalidToken_returns401_clearsCookies() {
            jakarta.servlet.http.Cookie[] cookies = {
                    new jakarta.servlet.http.Cookie("REFRESH_TOKEN", "invalid-jwt")
            };
            when(httpRequest.getCookies()).thenReturn(cookies);

            when(authService.refreshToken("invalid-jwt"))
                    .thenThrow(new IllegalArgumentException("Refresh Token 無效"));

            ResponseEntity<?> response = controller.refreshToken(httpRequest, httpResponse);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            // 應清除無效 Cookie
            verify(httpResponse, atLeast(2)).addHeader(eq("Set-Cookie"), anyString());
        }
    }

    // ==================== logout ====================

    @Nested
    @DisplayName("POST /logout")
    class Logout {

        @Test
        @DisplayName("已登入用戶登出 → 200 + 清除 Cookie + 審計")
        void loggedInUser_clearsAndAudits() {
            // 設定 SecurityContext
            Authentication auth = mock(Authentication.class);
            when(auth.getPrincipal()).thenReturn("user-123");
            SecurityContext ctx = mock(SecurityContext.class);
            when(ctx.getAuthentication()).thenReturn(auth);
            SecurityContextHolder.setContext(ctx);

            ResponseEntity<?> response = controller.logout(httpRequest, httpResponse);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            // 清除 Cookie
            verify(httpResponse, atLeast(2)).addHeader(eq("Set-Cookie"), anyString());
            // 審計
            verify(auditService).log(eq("user-123"), eq("LOGOUT"), anyString(),
                    eq("SUCCESS"), anyString(), eq(""));
        }

        @Test
        @DisplayName("未登入狀態登出 → 200（不審計）")
        void notLoggedIn_stillReturns200() {
            SecurityContextHolder.clearContext();

            ResponseEntity<?> response = controller.logout(httpRequest, httpResponse);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(auditService, never()).log(anyString(), eq("LOGOUT"), anyString(),
                    anyString(), anyString(), anyString());
        }
    }

    // ==================== change-password ====================

    @Nested
    @DisplayName("POST /change-password")
    class ChangePassword {

        private Authentication authentication;

        @BeforeEach
        void setUpAuth() {
            authentication = mock(Authentication.class);
            when(authentication.getPrincipal()).thenReturn("user-123");
        }

        @Test
        @DisplayName("修改成功 → 200 + 清除 Cookie + 審計")
        void success() {
            ChangePasswordRequest req = new ChangePasswordRequest("oldPass", "newPass12", "newPass12");

            ResponseEntity<?> response = controller.changePassword(req, authentication,
                    httpRequest, httpResponse);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(passwordResetService).changePassword(eq("user-123"), any());
            // 清除 Cookie
            verify(httpResponse, atLeast(2)).addHeader(eq("Set-Cookie"), anyString());
            // 審計
            verify(auditService).log(eq("user-123"), eq("CHANGE_PASSWORD"), anyString(),
                    eq("SUCCESS"), anyString(), eq(""));
        }

        @Test
        @DisplayName("舊密碼錯誤（IllegalArgument）→ 400 + 審計失敗")
        void wrongOldPassword_returns400() {
            ChangePasswordRequest req = new ChangePasswordRequest("wrong", "newPass12", "newPass12");

            doThrow(new IllegalArgumentException("密碼不正確"))
                    .when(passwordResetService).changePassword(anyString(), any());

            ResponseEntity<?> response = controller.changePassword(req, authentication,
                    httpRequest, httpResponse);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            verify(auditService).log(eq("user-123"), eq("CHANGE_PASSWORD"), anyString(),
                    eq("FAILED"), anyString(), eq("密碼不正確"));
        }
    }

    // ==================== forgot-password ====================

    @Nested
    @DisplayName("POST /forgot-password")
    class ForgotPassword {

        @Test
        @DisplayName("Email 存在 → 200 + 統一訊息")
        void emailExists_returns200() {
            ForgotPasswordRequest req = new ForgotPasswordRequest("test@email.com");

            ResponseEntity<?> response = controller.forgotPassword(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(passwordResetService).requestPasswordReset("test@email.com");
        }

        @Test
        @DisplayName("Email 不存在（拋異常）→ 仍回 200（防枚舉）")
        void emailNotExists_stillReturns200() {
            ForgotPasswordRequest req = new ForgotPasswordRequest("noexist@email.com");

            doThrow(new RuntimeException("找不到用戶"))
                    .when(passwordResetService).requestPasswordReset(anyString());

            ResponseEntity<?> response = controller.forgotPassword(req);

            // 安全原則：永遠回相同訊息
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ==================== reset-password ====================

    @Nested
    @DisplayName("POST /reset-password")
    class ResetPassword {

        @Test
        @DisplayName("重設成功 → 200")
        void success() {
            ResetPasswordRequest req = new ResetPasswordRequest("valid-token", "newPass12", "newPass12");

            ResponseEntity<?> response = controller.resetPassword(req);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            verify(passwordResetService).resetPassword(req);
        }

        @Test
        @DisplayName("Token 無效（IllegalArgument）→ 400")
        void invalidToken_returns400() {
            ResetPasswordRequest req = new ResetPasswordRequest("bad-token", "newPass12", "newPass12");

            doThrow(new IllegalArgumentException("Token 無效或已過期"))
                    .when(passwordResetService).resetPassword(any());

            ResponseEntity<?> response = controller.resetPassword(req);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ==================== me ====================

    @Nested
    @DisplayName("GET /me")
    class Me {

        @Test
        @DisplayName("已登入 + 用戶存在 → 200 + 用戶資訊")
        void loggedIn_returnsUserInfo() {
            Authentication auth = mock(Authentication.class);
            when(auth.getPrincipal()).thenReturn("user-123");

            User user = User.builder()
                    .userId("user-123")
                    .email("test@email.com")
                    .role(User.Role.USER)
                    .build();
            when(userRepository.findById("user-123")).thenReturn(Optional.of(user));

            ResponseEntity<?> response = controller.me(auth);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("userId", "user-123");
            assertThat(body).containsEntry("email", "test@email.com");
            assertThat(body).containsEntry("role", "USER");
        }

        @Test
        @DisplayName("未登入（auth=null）→ 401")
        void notAuthenticated_returns401() {
            ResponseEntity<?> response = controller.me(null);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("Token 有效但用戶已刪除 → 401")
        void userDeleted_returns401() {
            Authentication auth = mock(Authentication.class);
            when(auth.getPrincipal()).thenReturn("deleted-user");
            when(userRepository.findById("deleted-user")).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.me(auth);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("ADMIN 用戶 → 200 + role=ADMIN")
        void adminUser_returnsAdminRole() {
            Authentication auth = mock(Authentication.class);
            when(auth.getPrincipal()).thenReturn("admin-1");

            User admin = User.builder()
                    .userId("admin-1")
                    .email("admin@email.com")
                    .role(User.Role.ADMIN)
                    .build();
            when(userRepository.findById("admin-1")).thenReturn(Optional.of(admin));

            ResponseEntity<?> response = controller.me(auth);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) response.getBody();
            assertThat(body).containsEntry("role", "ADMIN");
        }
    }
}
