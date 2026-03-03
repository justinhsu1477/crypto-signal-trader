package com.trader.auth.util;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ClientIpResolver 單元測試
 *
 * 覆蓋：trusted proxy 判斷、CIDR 比對、header 優先順序、防偽造
 */
class ClientIpResolverTest {

    private ClientIpResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new ClientIpResolver();
        ReflectionTestUtils.setField(resolver, "ipHeader", "");
        ReflectionTestUtils.setField(resolver, "trustedProxies", "127.0.0.1,::1,0:0:0:0:0:0:0:1");
    }

    private HttpServletRequest mockRequest(String remoteAddr) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        when(request.getHeader(anyString())).thenReturn(null);
        return request;
    }

    // ─── resolve — 基本行為 ───

    @Nested
    @DisplayName("resolve — IP 解析")
    class ResolveTests {

        @Test
        @DisplayName("非 trusted proxy → 直接回傳 remoteAddr（防偽造）")
        void untrustedProxy_returnsRemoteAddr() {
            HttpServletRequest request = mockRequest("203.0.113.50");
            when(request.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");

            String result = resolver.resolve(request);

            assertThat(result).isEqualTo("203.0.113.50");
        }

        @Test
        @DisplayName("trusted(127.0.0.1) + CF-Connecting-IP → 回傳 CF header")
        void trustedWithCfHeader_returnsCfIp() {
            ReflectionTestUtils.setField(resolver, "ipHeader", "CF-Connecting-IP");
            HttpServletRequest request = mockRequest("127.0.0.1");
            when(request.getHeader("CF-Connecting-IP")).thenReturn("203.0.113.100");

            String result = resolver.resolve(request);

            assertThat(result).isEqualTo("203.0.113.100");
        }

        @Test
        @DisplayName("trusted + 無 CF header + X-Forwarded-For → 回傳 XFF 第一個")
        void trustedWithXff_returnsFirstXff() {
            HttpServletRequest request = mockRequest("127.0.0.1");
            when(request.getHeader("X-Forwarded-For")).thenReturn("50.0.0.1, 60.0.0.1");

            String result = resolver.resolve(request);

            assertThat(result).isEqualTo("50.0.0.1");
        }

        @Test
        @DisplayName("trusted + 無 CF/XFF + X-Real-IP → 回傳 X-Real-IP")
        void trustedWithXRealIp_returnsXRealIp() {
            HttpServletRequest request = mockRequest("127.0.0.1");
            when(request.getHeader("X-Real-IP")).thenReturn("70.0.0.1");

            String result = resolver.resolve(request);

            assertThat(result).isEqualTo("70.0.0.1");
        }

        @Test
        @DisplayName("trusted + 無任何 header → 回傳 remoteAddr")
        void trustedWithNoHeaders_returnsRemoteAddr() {
            HttpServletRequest request = mockRequest("127.0.0.1");

            String result = resolver.resolve(request);

            assertThat(result).isEqualTo("127.0.0.1");
        }

        @Test
        @DisplayName("ipHeader 為空 → 跳過自訂 header")
        void emptyIpHeader_skipsCustomHeader() {
            ReflectionTestUtils.setField(resolver, "ipHeader", "");
            HttpServletRequest request = mockRequest("127.0.0.1");
            when(request.getHeader("CF-Connecting-IP")).thenReturn("1.2.3.4");
            when(request.getHeader("X-Forwarded-For")).thenReturn("50.0.0.1");

            String result = resolver.resolve(request);

            // CF-Connecting-IP 被忽略（ipHeader 為空），回傳 XFF
            assertThat(result).isEqualTo("50.0.0.1");
        }

        @Test
        @DisplayName("XFF 含多個 IP → 取第一個（最左）")
        void xffMultipleIps_returnsFirst() {
            HttpServletRequest request = mockRequest("127.0.0.1");
            when(request.getHeader("X-Forwarded-For")).thenReturn("  1.1.1.1  , 2.2.2.2, 3.3.3.3");

            String result = resolver.resolve(request);

            assertThat(result).isEqualTo("1.1.1.1");
        }
    }

    // ─── isTrustedProxy — CIDR 支援 ───

    @Nested
    @DisplayName("isTrustedProxy — CIDR 比對")
    class TrustedProxyTests {

        @Test
        @DisplayName("CIDR 172.16.0.0/12 匹配 Docker IP 172.18.0.4")
        void cidr_matchesDockerIp() {
            ReflectionTestUtils.setField(resolver, "trustedProxies", "172.16.0.0/12");

            assertThat(resolver.isTrustedProxy("172.18.0.4")).isTrue();
            assertThat(resolver.isTrustedProxy("172.31.255.255")).isTrue();
        }

        @Test
        @DisplayName("CIDR 172.16.0.0/12 不匹配非 Docker IP")
        void cidr_doesNotMatchNonDockerIp() {
            ReflectionTestUtils.setField(resolver, "trustedProxies", "172.16.0.0/12");

            assertThat(resolver.isTrustedProxy("10.0.0.1")).isFalse();
            assertThat(resolver.isTrustedProxy("192.168.1.1")).isFalse();
            assertThat(resolver.isTrustedProxy("203.0.113.50")).isFalse();
        }

        @Test
        @DisplayName("精確 IP 匹配")
        void exactIp_matches() {
            ReflectionTestUtils.setField(resolver, "trustedProxies", "10.0.0.5,192.168.1.100");

            assertThat(resolver.isTrustedProxy("10.0.0.5")).isTrue();
            assertThat(resolver.isTrustedProxy("192.168.1.100")).isTrue();
            assertThat(resolver.isTrustedProxy("10.0.0.6")).isFalse();
        }

        @Test
        @DisplayName("localhost 快速路徑 → trusted")
        void localhost_alwaysTrusted() {
            ReflectionTestUtils.setField(resolver, "trustedProxies", "");

            assertThat(resolver.isTrustedProxy("127.0.0.1")).isTrue();
            assertThat(resolver.isTrustedProxy("::1")).isTrue();
            assertThat(resolver.isTrustedProxy("0:0:0:0:0:0:0:1")).isTrue();
        }

        @Test
        @DisplayName("null / 空白 remoteAddr → not trusted")
        void nullOrBlank_notTrusted() {
            assertThat(resolver.isTrustedProxy(null)).isFalse();
            assertThat(resolver.isTrustedProxy("")).isFalse();
            assertThat(resolver.isTrustedProxy("  ")).isFalse();
        }

        @Test
        @DisplayName("混合 CIDR + 精確 IP 設定")
        void mixedCidrAndExact() {
            ReflectionTestUtils.setField(resolver, "trustedProxies",
                    "172.16.0.0/12,10.0.0.1,127.0.0.1");

            assertThat(resolver.isTrustedProxy("172.20.0.3")).isTrue();    // CIDR
            assertThat(resolver.isTrustedProxy("10.0.0.1")).isTrue();      // 精確
            assertThat(resolver.isTrustedProxy("10.0.0.2")).isFalse();     // 不匹配
        }
    }

    // ─── 端到端整合場景 ───

    @Nested
    @DisplayName("端到端場景")
    class EndToEndTests {

        @Test
        @DisplayName("Cloudflare + Caddy(Docker) → 正確取到用戶真實 IP")
        void cloudflareViaCaddy_returnsRealIp() {
            ReflectionTestUtils.setField(resolver, "ipHeader", "CF-Connecting-IP");
            ReflectionTestUtils.setField(resolver, "trustedProxies",
                    "172.16.0.0/12,127.0.0.1,::1,0:0:0:0:0:0:0:1");

            // Caddy (172.18.0.4) 作為反向代理，Cloudflare 設定 CF-Connecting-IP
            HttpServletRequest request = mockRequest("172.18.0.4");
            when(request.getHeader("CF-Connecting-IP")).thenReturn("203.0.113.42");
            when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.42, 162.158.0.1");

            String result = resolver.resolve(request);

            assertThat(result).isEqualTo("203.0.113.42");
        }
    }
}
