package com.trader.auth.filter;

import com.trader.auth.service.JwtService;
import com.trader.user.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;

import com.trader.shared.config.AppConstants;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * JwtAuthenticationFilter 單元測試
 *
 * 覆蓋：Bearer token 提取、驗證成功/失敗、SecurityContext 設定、跳過邏輯
 */
class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private UserRepository userRepository;
    private JwtAuthenticationFilter filter;
    private FilterChain chain;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        userRepository = mock(UserRepository.class);
        filter = new JwtAuthenticationFilter(jwtService, userRepository);
        chain = mock(FilterChain.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);

        // 預設：用戶存在且 passwordChangedAt = null（向後相容）
        when(userRepository.findById(anyString())).thenReturn(Optional.empty());

        // 每次測試前清空 SecurityContext
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("JWT 認證")
    class JwtAuthTests {

        @Test
        @DisplayName("無 Authorization header — 跳過，chain 繼續")
        void noAuthHeaderSkips() throws Exception {
            when(request.getHeader("Authorization")).thenReturn(null);

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("非 Bearer token — 跳過")
        void nonBearerTokenSkips() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Basic abc123");

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("有效 JWT — 設定 SecurityContext")
        void validJwtSetsContext() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
            when(jwtService.validateToken("valid-token")).thenReturn(true);
            when(jwtService.extractUserId("valid-token")).thenReturn("user-123");
            when(jwtService.extractRole("valid-token")).thenReturn("USER");

            // 用戶存在且 passwordChangedAt = null（不檢查 iat）
            var user = com.trader.user.entity.User.builder()
                    .userId("user-123")
                    .passwordChangedAt(null)
                    .build();
            when(userRepository.findById("user-123")).thenReturn(Optional.of(user));

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                    .isEqualTo("user-123");
        }

        @Test
        @DisplayName("無效 JWT — 不設定 SecurityContext")
        void invalidJwtNoContext() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer invalid-token");
            when(jwtService.validateToken("invalid-token")).thenReturn(false);

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("JWT 驗證拋例外 — 不設定 SecurityContext，chain 繼續")
        void jwtExceptionNoContext() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer broken-token");
            when(jwtService.validateToken("broken-token")).thenThrow(new RuntimeException("Token malformed"));

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }

    @Nested
    @DisplayName("密碼變更 Token Invalidation")
    class PasswordChangeInvalidationTests {

        @Test
        @DisplayName("JWT iat < passwordChangedAt → 不設定 SecurityContext")
        void tokenBeforePasswordChange_rejected() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer old-token");
            when(jwtService.validateToken("old-token")).thenReturn(true);
            when(jwtService.extractUserId("old-token")).thenReturn("user-1");
            when(jwtService.extractRole("old-token")).thenReturn("USER");

            // JWT iat = 1 小時前
            Date iat = new Date(System.currentTimeMillis() - 3600_000);
            when(jwtService.extractIssuedAt("old-token")).thenReturn(iat);

            // 密碼 10 分鐘前改過（用 AppConstants.ZONE_ID，與生產 code 一致）
            var user = com.trader.user.entity.User.builder()
                    .userId("user-1")
                    .passwordChangedAt(LocalDateTime.now(AppConstants.ZONE_ID).minusMinutes(10))
                    .build();
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("JWT iat > passwordChangedAt → 通過認證")
        void tokenAfterPasswordChange_accepted() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer new-token");
            when(jwtService.validateToken("new-token")).thenReturn(true);
            when(jwtService.extractUserId("new-token")).thenReturn("user-1");
            when(jwtService.extractRole("new-token")).thenReturn("USER");

            // JWT iat = 剛剛（5 秒前）
            Date iat = new Date(System.currentTimeMillis() - 5000);
            when(jwtService.extractIssuedAt("new-token")).thenReturn(iat);

            // 密碼 1 小時前改過（用 AppConstants.ZONE_ID，與生產 code 一致）
            var user = com.trader.user.entity.User.builder()
                    .userId("user-1")
                    .passwordChangedAt(LocalDateTime.now(AppConstants.ZONE_ID).minusHours(1))
                    .build();
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                    .isEqualTo("user-1");
        }

        @Test
        @DisplayName("passwordChangedAt = null（舊用戶）→ 向後相容，通過認證")
        void nullPasswordChangedAt_accepted() throws Exception {
            when(request.getHeader("Authorization")).thenReturn("Bearer any-token");
            when(jwtService.validateToken("any-token")).thenReturn(true);
            when(jwtService.extractUserId("any-token")).thenReturn("user-1");
            when(jwtService.extractRole("any-token")).thenReturn("USER");

            var user = com.trader.user.entity.User.builder()
                    .userId("user-1")
                    .passwordChangedAt(null) // 從未改過密碼
                    .build();
            when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        }
    }
}
