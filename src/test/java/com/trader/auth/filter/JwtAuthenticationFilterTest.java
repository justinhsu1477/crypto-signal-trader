package com.trader.auth.filter;

import com.trader.auth.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * JwtAuthenticationFilter 單元測試
 *
 * 覆蓋：Bearer token 提取、驗證成功/失敗、SecurityContext 設定、跳過邏輯
 */
class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private JwtAuthenticationFilter filter;
    private FilterChain chain;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        jwtService = mock(JwtService.class);
        filter = new JwtAuthenticationFilter(jwtService);
        chain = mock(FilterChain.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);

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
}
