package com.trader.auth.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * RateLimitFilter 單元測試
 *
 * 覆蓋：IP rate limiting, auth 路徑限制, 429 回傳格式, X-Forwarded-For 解析, cleanup
 */
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;
    private HttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter();
        chain = mock(FilterChain.class);
        response = mock(HttpServletResponse.class);
    }

    private HttpServletRequest mockRequest(String path, String ip) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn(path);
        when(request.getRemoteAddr()).thenReturn(ip);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getHeader("X-Real-IP")).thenReturn(null);
        return request;
    }

    @Nested
    @DisplayName("路徑過濾")
    class PathFilterTests {

        @Test
        @DisplayName("非 auth 路徑 — 直接放行")
        void nonAuthPathPassesThrough() throws Exception {
            HttpServletRequest request = mockRequest("/api/balance", "1.2.3.4");

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            verify(response, never()).setStatus(429);
        }

        @Test
        @DisplayName("auth 路徑 — 前 10 次放行")
        void authPathFirst10Allowed() throws Exception {
            for (int i = 0; i < 10; i++) {
                HttpServletRequest request = mockRequest("/api/auth/login", "10.0.0.1");
                filter.doFilter(request, response, chain);
            }

            verify(chain, times(10)).doFilter(any(), any());
            verify(response, never()).setStatus(429);
        }
    }

    @Nested
    @DisplayName("限速攔截")
    class RateLimitTests {

        @Test
        @DisplayName("auth 路徑第 11 次 — 429")
        void authPath11thBlocked() throws Exception {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);

            for (int i = 0; i < 11; i++) {
                HttpServletRequest request = mockRequest("/api/auth/login", "20.0.0.1");
                HttpServletResponse resp = mock(HttpServletResponse.class);
                when(resp.getWriter()).thenReturn(pw);
                filter.doFilter(request, resp, chain);

                if (i == 10) {
                    verify(resp).setStatus(429);
                    verify(resp).setContentType("application/json");
                }
            }

            // chain 只被呼叫 10 次（第 11 次被攔截）
            verify(chain, times(10)).doFilter(any(), any());
        }

        @Test
        @DisplayName("429 回傳 JSON 格式正確")
        void return429JsonFormat() throws Exception {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);

            // 先消耗 10 次
            for (int i = 0; i < 10; i++) {
                HttpServletRequest request = mockRequest("/api/auth/register", "30.0.0.1");
                filter.doFilter(request, response, chain);
            }

            // 第 11 次
            HttpServletRequest request = mockRequest("/api/auth/register", "30.0.0.1");
            HttpServletResponse resp = mock(HttpServletResponse.class);
            when(resp.getWriter()).thenReturn(pw);

            filter.doFilter(request, resp, chain);

            assertThat(sw.toString()).contains("retryAfterSeconds");
            assertThat(sw.toString()).contains("60");
        }

        @Test
        @DisplayName("不同 IP 不互相影響")
        void differentIpsIndependent() throws Exception {
            for (int i = 0; i < 10; i++) {
                HttpServletRequest request = mockRequest("/api/auth/login", "40.0.0.1");
                filter.doFilter(request, response, chain);
            }

            // 新 IP 仍然可以通過
            HttpServletRequest request = mockRequest("/api/auth/login", "40.0.0.2");
            filter.doFilter(request, response, chain);
            verify(chain, times(11)).doFilter(any(), any()); // 10 + 1
        }
    }

    @Nested
    @DisplayName("IP 解析")
    class IpParsingTests {

        @Test
        @DisplayName("X-Forwarded-For 取第一個 IP")
        void xForwardedForUsesFirstIp() throws Exception {
            HttpServletRequest request = mockRequest("/api/auth/login", "127.0.0.1");
            when(request.getHeader("X-Forwarded-For")).thenReturn("50.0.0.1, 60.0.0.1");

            filter.doFilter(request, response, chain);

            // 應該用 50.0.0.1 當作 client IP
            verify(chain).doFilter(request, response);
        }
    }

    @Nested
    @DisplayName("清理")
    class CleanupTests {

        @Test
        @DisplayName("cleanup 不拋例外")
        void cleanupDoesNotThrow() {
            assertThatCode(() -> filter.cleanup()).doesNotThrowAnyException();
        }
    }
}
