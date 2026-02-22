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
 * 覆蓋：多路徑限流規則、group 共享計數、429 回傳格式、
 *       IP 解析（X-Forwarded-For / X-Real-IP）、cleanup、不同 IP 隔離
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

    // ==================== matchRule ====================

    @Nested
    @DisplayName("matchRule — 路徑匹配")
    class MatchRuleTests {

        @Test
        @DisplayName("auth 路徑 — 匹配 auth group")
        void authPath() {
            RateLimitFilter.RateLimitRule rule = filter.matchRule("/api/auth/login");
            assertThat(rule).isNotNull();
            assertThat(rule.group).isEqualTo("auth");
            assertThat(rule.maxPerMinute).isEqualTo(10);
        }

        @Test
        @DisplayName("execute-signal — 匹配 trade group")
        void executeSignalPath() {
            RateLimitFilter.RateLimitRule rule = filter.matchRule("/api/execute-signal");
            assertThat(rule).isNotNull();
            assertThat(rule.group).isEqualTo("trade");
            assertThat(rule.maxPerMinute).isEqualTo(30);
        }

        @Test
        @DisplayName("execute-trade — 匹配 trade group（共享計數）")
        void executeTradePath() {
            RateLimitFilter.RateLimitRule rule = filter.matchRule("/api/execute-trade");
            assertThat(rule).isNotNull();
            assertThat(rule.group).isEqualTo("trade");
        }

        @Test
        @DisplayName("broadcast-trade — 匹配 broadcast group")
        void broadcastTradePath() {
            RateLimitFilter.RateLimitRule rule = filter.matchRule("/api/broadcast-trade");
            assertThat(rule).isNotNull();
            assertThat(rule.group).isEqualTo("broadcast");
            assertThat(rule.maxPerMinute).isEqualTo(10);
        }

        @Test
        @DisplayName("dashboard — 匹配 dashboard group")
        void dashboardPath() {
            RateLimitFilter.RateLimitRule rule = filter.matchRule("/api/dashboard/overview");
            assertThat(rule).isNotNull();
            assertThat(rule.group).isEqualTo("dashboard");
            assertThat(rule.maxPerMinute).isEqualTo(60);
        }

        @Test
        @DisplayName("不在列表中的路徑 — null")
        void unmatchedPath() {
            assertThat(filter.matchRule("/api/balance")).isNull();
            assertThat(filter.matchRule("/api/health")).isNull();
            assertThat(filter.matchRule("/api/monitor/heartbeat")).isNull();
        }
    }

    // ==================== 路徑放行 ====================

    @Nested
    @DisplayName("路徑過濾 — 不限流路徑直接放行")
    class PathFilterTests {

        @Test
        @DisplayName("非限流路徑 — 直接放行")
        void unmatchedPathPassesThrough() throws Exception {
            HttpServletRequest request = mockRequest("/api/balance", "1.2.3.4");

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
            verify(response, never()).setStatus(429);
        }

        @Test
        @DisplayName("/api/health — 不限流")
        void healthPathNotLimited() throws Exception {
            HttpServletRequest request = mockRequest("/api/health", "1.2.3.4");
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("/api/monitor/heartbeat — 不限流")
        void monitorHeartbeatNotLimited() throws Exception {
            HttpServletRequest request = mockRequest("/api/monitor/heartbeat", "1.2.3.4");
            filter.doFilter(request, response, chain);
            verify(chain).doFilter(request, response);
        }
    }

    // ==================== auth 端點限流 ====================

    @Nested
    @DisplayName("auth 端點 — 10/min")
    class AuthRateLimitTests {

        @Test
        @DisplayName("前 10 次放行")
        void first10Allowed() throws Exception {
            for (int i = 0; i < 10; i++) {
                HttpServletRequest request = mockRequest("/api/auth/login", "10.0.0.1");
                filter.doFilter(request, response, chain);
            }

            verify(chain, times(10)).doFilter(any(), any());
            verify(response, never()).setStatus(429);
        }

        @Test
        @DisplayName("第 11 次 — 429")
        void eleventhBlocked() throws Exception {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);

            for (int i = 0; i < 11; i++) {
                HttpServletRequest request = mockRequest("/api/auth/login", "11.0.0.1");
                HttpServletResponse resp = mock(HttpServletResponse.class);
                when(resp.getWriter()).thenReturn(pw);
                filter.doFilter(request, resp, chain);

                if (i == 10) {
                    verify(resp).setStatus(429);
                }
            }

            verify(chain, times(10)).doFilter(any(), any());
        }
    }

    // ==================== trade 端點限流 ====================

    @Nested
    @DisplayName("trade 端點 — 30/min")
    class TradeRateLimitTests {

        @Test
        @DisplayName("execute-signal 前 30 次放行")
        void first30Allowed() throws Exception {
            for (int i = 0; i < 30; i++) {
                HttpServletRequest request = mockRequest("/api/execute-signal", "12.0.0.1");
                filter.doFilter(request, response, chain);
            }

            verify(chain, times(30)).doFilter(any(), any());
            verify(response, never()).setStatus(429);
        }

        @Test
        @DisplayName("execute-signal 第 31 次 — 429")
        void thirtyFirstBlocked() throws Exception {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);

            for (int i = 0; i < 31; i++) {
                HttpServletRequest request = mockRequest("/api/execute-signal", "13.0.0.1");
                HttpServletResponse resp = mock(HttpServletResponse.class);
                when(resp.getWriter()).thenReturn(pw);
                filter.doFilter(request, resp, chain);

                if (i == 30) {
                    verify(resp).setStatus(429);
                }
            }

            verify(chain, times(30)).doFilter(any(), any());
        }

        @Test
        @DisplayName("execute-signal + execute-trade 共享 trade group 計數")
        void sharedGroupCount() throws Exception {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);

            // 用 execute-signal 消耗 20 次
            for (int i = 0; i < 20; i++) {
                HttpServletRequest request = mockRequest("/api/execute-signal", "14.0.0.1");
                filter.doFilter(request, response, chain);
            }

            // 用 execute-trade 再 10 次 = 共 30 次
            for (int i = 0; i < 10; i++) {
                HttpServletRequest request = mockRequest("/api/execute-trade", "14.0.0.1");
                filter.doFilter(request, response, chain);
            }

            // 第 31 次應該被攔截
            HttpServletRequest request = mockRequest("/api/execute-trade", "14.0.0.1");
            HttpServletResponse resp = mock(HttpServletResponse.class);
            when(resp.getWriter()).thenReturn(pw);
            filter.doFilter(request, resp, chain);

            verify(resp).setStatus(429);
            verify(chain, times(30)).doFilter(any(), any());
        }
    }

    // ==================== dashboard 端點限流 ====================

    @Nested
    @DisplayName("dashboard 端點 — 60/min")
    class DashboardRateLimitTests {

        @Test
        @DisplayName("前 60 次放行")
        void first60Allowed() throws Exception {
            for (int i = 0; i < 60; i++) {
                HttpServletRequest request = mockRequest("/api/dashboard/overview", "15.0.0.1");
                filter.doFilter(request, response, chain);
            }

            verify(chain, times(60)).doFilter(any(), any());
            verify(response, never()).setStatus(429);
        }

        @Test
        @DisplayName("第 61 次 — 429")
        void sixtyFirstBlocked() throws Exception {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);

            for (int i = 0; i < 61; i++) {
                HttpServletRequest request = mockRequest("/api/dashboard/overview", "16.0.0.1");
                HttpServletResponse resp = mock(HttpServletResponse.class);
                when(resp.getWriter()).thenReturn(pw);
                filter.doFilter(request, resp, chain);

                if (i == 60) {
                    verify(resp).setStatus(429);
                }
            }

            verify(chain, times(60)).doFilter(any(), any());
        }
    }

    // ==================== broadcast 端點限流 ====================

    @Nested
    @DisplayName("broadcast 端點 — 10/min")
    class BroadcastRateLimitTests {

        @Test
        @DisplayName("第 11 次 — 429")
        void eleventhBlocked() throws Exception {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);

            for (int i = 0; i < 11; i++) {
                HttpServletRequest request = mockRequest("/api/broadcast-trade", "17.0.0.1");
                HttpServletResponse resp = mock(HttpServletResponse.class);
                when(resp.getWriter()).thenReturn(pw);
                filter.doFilter(request, resp, chain);

                if (i == 10) {
                    verify(resp).setStatus(429);
                }
            }

            verify(chain, times(10)).doFilter(any(), any());
        }
    }

    // ==================== 429 回傳格式 ====================

    @Nested
    @DisplayName("429 回傳格式")
    class ResponseFormatTests {

        @Test
        @DisplayName("JSON 格式包含 retryAfterSeconds")
        void return429JsonFormat() throws Exception {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);

            // 消耗 10 次
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
            verify(resp).setContentType("application/json");
        }
    }

    // ==================== IP 隔離 ====================

    @Nested
    @DisplayName("IP 隔離")
    class IpIsolationTests {

        @Test
        @DisplayName("不同 IP 不互相影響")
        void differentIpsIndependent() throws Exception {
            // IP-A 消耗完 auth 10 次
            for (int i = 0; i < 10; i++) {
                HttpServletRequest request = mockRequest("/api/auth/login", "40.0.0.1");
                filter.doFilter(request, response, chain);
            }

            // IP-B 仍然可以通過
            HttpServletRequest request = mockRequest("/api/auth/login", "40.0.0.2");
            filter.doFilter(request, response, chain);
            verify(chain, times(11)).doFilter(any(), any());
        }

        @Test
        @DisplayName("不同 group 不互相影響")
        void differentGroupsIndependent() throws Exception {
            // 同一 IP，消耗完 auth 10 次
            for (int i = 0; i < 10; i++) {
                HttpServletRequest request = mockRequest("/api/auth/login", "41.0.0.1");
                filter.doFilter(request, response, chain);
            }

            // 同一 IP，dashboard 仍可通過
            HttpServletRequest request = mockRequest("/api/dashboard/overview", "41.0.0.1");
            filter.doFilter(request, response, chain);
            verify(chain, times(11)).doFilter(any(), any());
        }
    }

    // ==================== IP 解析 ====================

    @Nested
    @DisplayName("IP 解析")
    class IpParsingTests {

        @Test
        @DisplayName("X-Forwarded-For 取第一個 IP")
        void xForwardedForUsesFirstIp() throws Exception {
            HttpServletRequest request = mockRequest("/api/auth/login", "127.0.0.1");
            when(request.getHeader("X-Forwarded-For")).thenReturn("50.0.0.1, 60.0.0.1");

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }

        @Test
        @DisplayName("X-Real-IP 優先於 remoteAddr")
        void xRealIpUsed() throws Exception {
            HttpServletRequest request = mockRequest("/api/auth/login", "127.0.0.1");
            when(request.getHeader("X-Real-IP")).thenReturn("70.0.0.1");

            filter.doFilter(request, response, chain);

            verify(chain).doFilter(request, response);
        }
    }

    // ==================== cleanup ====================

    @Nested
    @DisplayName("cleanup — 計數器清理")
    class CleanupTests {

        @Test
        @DisplayName("cleanup 不拋例外")
        void cleanupDoesNotThrow() {
            assertThatCode(() -> filter.cleanup()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("cleanup 後空計數器不影響運作")
        void cleanupThenContinue() throws Exception {
            HttpServletRequest request = mockRequest("/api/auth/login", "80.0.0.1");
            filter.doFilter(request, response, chain);

            filter.cleanup();

            HttpServletRequest request2 = mockRequest("/api/auth/login", "80.0.0.1");
            filter.doFilter(request2, response, chain);

            verify(chain, times(2)).doFilter(any(), any());
        }
    }
}
