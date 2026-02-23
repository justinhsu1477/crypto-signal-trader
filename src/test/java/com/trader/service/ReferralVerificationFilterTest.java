package com.trader.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.auth.filter.ReferralVerificationFilter;
import com.trader.referral.service.ReferralService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ReferralVerificationFilter 測試
 *
 * 驗證：ADMIN 放行、monitor 放行、白名單放行、未驗證攔截、已驗證放行
 */
class ReferralVerificationFilterTest {

    private ReferralService referralService;
    private ReferralVerificationFilter filter;
    private FilterChain filterChain;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        referralService = mock(ReferralService.class);
        objectMapper = new ObjectMapper();
        filter = new ReferralVerificationFilter(referralService, objectMapper);
        filterChain = mock(FilterChain.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setAuthentication(String principal, String role) {
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ==================== 放行條件 ====================

    @Nested
    @DisplayName("放行條件")
    class PassThrough {

        @Test
        @DisplayName("未認證（anonymous）→ 放行，讓 Spring Security 處理")
        void anonymousPassThrough() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/execute-trade");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(200);
        }

        @Test
        @DisplayName("ADMIN 角色 → 放行")
        void adminPassThrough() throws Exception {
            setAuthentication("admin-1", "ADMIN");
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/execute-trade");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verify(referralService, never()).isVerified(anyString());
        }

        @Test
        @DisplayName("monitor principal → 放行")
        void monitorPassThrough() throws Exception {
            var auth = new UsernamePasswordAuthenticationToken(
                    "monitor", null,
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/execute-trade");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verify(referralService, never()).isVerified(anyString());
        }

        @Test
        @DisplayName("白名單路徑 /api/referral/status → 放行")
        void whitelistPassThrough() throws Exception {
            setAuthentication("user-1", "USER");
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/referral/status");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
            verify(referralService, never()).isVerified(anyString());
        }

        @Test
        @DisplayName("已驗證用戶 → 放行")
        void verifiedUserPassThrough() throws Exception {
            setAuthentication("user-1", "USER");
            when(referralService.isVerified("user-1")).thenReturn(true);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/execute-trade");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain).doFilter(request, response);
        }
    }

    // ==================== 攔截條件 ====================

    @Nested
    @DisplayName("攔截條件")
    class Blocked {

        @Test
        @DisplayName("未驗證用戶打交易 API → 403 REFERRAL_NOT_VERIFIED")
        void unverifiedUserBlocked() throws Exception {
            setAuthentication("user-1", "USER");
            when(referralService.isVerified("user-1")).thenReturn(false);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/execute-trade");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain, never()).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
            assertThat(response.getContentAsString()).contains("REFERRAL_NOT_VERIFIED");
        }

        @Test
        @DisplayName("未驗證用戶打 dashboard API → 403")
        void unverifiedUserBlockedDashboard() throws Exception {
            setAuthentication("user-1", "USER");
            when(referralService.isVerified("user-1")).thenReturn(false);
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dashboard/overview");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);

            verify(filterChain, never()).doFilter(request, response);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        }
    }
}
