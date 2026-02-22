package com.trader.auth.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MonitorApiKeyFilter 單元測試
 *
 * 覆蓋：正確/錯誤 API Key, 已有 JWT 認證跳過, API Key 未配置
 */
class MonitorApiKeyFilterTest {

    private MonitorApiKeyFilter filter;
    private FilterChain chain;
    private HttpServletRequest request;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new MonitorApiKeyFilter();
        chain = mock(FilterChain.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);

        // 注入 monitorApiKey
        ReflectionTestUtils.setField(filter, "monitorApiKey", "test-secret-key");

        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Nested
    @DisplayName("API Key 認證")
    class ApiKeyAuthTests {

        @Test
        @DisplayName("正確 API Key — 設定認證")
        void correctApiKeySetsAuth() throws Exception {
            when(request.getHeader("X-Api-Key")).thenReturn("test-secret-key");

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                    .isEqualTo("monitor");
        }

        @Test
        @DisplayName("錯誤 API Key — 不設定認證")
        void wrongApiKeyNoAuth() throws Exception {
            when(request.getHeader("X-Api-Key")).thenReturn("wrong-key");

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("無 API Key header — 跳過")
        void noApiKeySkips() throws Exception {
            when(request.getHeader("X-Api-Key")).thenReturn(null);

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("已有 JWT 認證 — 跳過 API Key 驗證")
        void existingJwtAuthSkips() throws Exception {
            // 預設 SecurityContext 已有認證
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("user-123", null));

            when(request.getHeader("X-Api-Key")).thenReturn("test-secret-key");

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            // 認證應保持原來的 JWT 認證，不是 monitor
            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                    .isEqualTo("user-123");
        }

        @Test
        @DisplayName("API Key 未配置（空字串）— 跳過")
        void emptyApiKeySkips() throws Exception {
            ReflectionTestUtils.setField(filter, "monitorApiKey", "");
            when(request.getHeader("X-Api-Key")).thenReturn("some-key");

            filter.doFilterInternal(request, response, chain);

            verify(chain).doFilter(request, response);
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }
    }
}
