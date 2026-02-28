package com.trader.auth.util;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * CookieUtil 單元測試
 *
 * 測試重點：
 * - Access / Refresh Token Cookie 的 HttpOnly + Secure + SameSite + Path + MaxAge
 * - 清除 Cookie（maxAge=0）
 * - 從 Request 提取 Token（正常/空/無 Cookie）
 */
class CookieUtilTest {

    private HttpServletResponse response;
    private HttpServletRequest request;
    private List<String> setCookieHeaders;

    @BeforeEach
    void setUp() {
        response = mock(HttpServletResponse.class);
        request = mock(HttpServletRequest.class);
        setCookieHeaders = new ArrayList<>();

        // 捕獲所有 Set-Cookie header
        doAnswer(inv -> {
            String name = inv.getArgument(0);
            String value = inv.getArgument(1);
            if ("Set-Cookie".equals(name)) {
                setCookieHeaders.add(value);
            }
            return null;
        }).when(response).addHeader(anyString(), anyString());
    }

    // ==================== addAccessTokenCookie ====================

    @Nested
    @DisplayName("addAccessTokenCookie")
    class AddAccessToken {

        @Test
        @DisplayName("設定 HttpOnly + Secure + SameSite=Strict + Path=/api")
        void setsCorrectAttributes() {
            CookieUtil.addAccessTokenCookie(response, "test-access-token", 1800);

            assertThat(setCookieHeaders).hasSize(1);
            String cookie = setCookieHeaders.get(0);
            assertThat(cookie).contains("ACCESS_TOKEN=test-access-token");
            assertThat(cookie).containsIgnoringCase("HttpOnly");
            assertThat(cookie).containsIgnoringCase("Secure");
            assertThat(cookie).contains("SameSite=Strict");
            assertThat(cookie).contains("Path=/api");
            assertThat(cookie).contains("Max-Age=1800");
        }

        @Test
        @DisplayName("不同的 maxAge 正確設定")
        void differentMaxAge() {
            CookieUtil.addAccessTokenCookie(response, "token", 3600);

            String cookie = setCookieHeaders.get(0);
            assertThat(cookie).contains("Max-Age=3600");
        }
    }

    // ==================== addRefreshTokenCookie ====================

    @Nested
    @DisplayName("addRefreshTokenCookie")
    class AddRefreshToken {

        @Test
        @DisplayName("設定 HttpOnly + Secure + SameSite=Strict + Path=/api/auth")
        void setsCorrectAttributes() {
            CookieUtil.addRefreshTokenCookie(response, "test-refresh-token", 259200);

            assertThat(setCookieHeaders).hasSize(1);
            String cookie = setCookieHeaders.get(0);
            assertThat(cookie).contains("REFRESH_TOKEN=test-refresh-token");
            assertThat(cookie).containsIgnoringCase("HttpOnly");
            assertThat(cookie).containsIgnoringCase("Secure");
            assertThat(cookie).contains("SameSite=Strict");
            assertThat(cookie).contains("Path=/api/auth");
            assertThat(cookie).contains("Max-Age=259200");
        }
    }

    // ==================== clearAuthCookies ====================

    @Nested
    @DisplayName("clearAuthCookies")
    class ClearCookies {

        @Test
        @DisplayName("清除兩個 Cookie（MaxAge=0）")
        void clearsBothCookies() {
            CookieUtil.clearAuthCookies(response);

            assertThat(setCookieHeaders).hasSize(2);

            // Access Token cookie
            String accessCookie = setCookieHeaders.stream()
                    .filter(c -> c.contains("ACCESS_TOKEN="))
                    .findFirst().orElseThrow();
            assertThat(accessCookie).contains("Max-Age=0");
            assertThat(accessCookie).contains("Path=/api");
            assertThat(accessCookie).containsIgnoringCase("HttpOnly");

            // Refresh Token cookie
            String refreshCookie = setCookieHeaders.stream()
                    .filter(c -> c.contains("REFRESH_TOKEN="))
                    .findFirst().orElseThrow();
            assertThat(refreshCookie).contains("Max-Age=0");
            assertThat(refreshCookie).contains("Path=/api/auth");
            assertThat(refreshCookie).containsIgnoringCase("HttpOnly");
        }
    }

    // ==================== extractAccessToken ====================

    @Nested
    @DisplayName("extractAccessToken")
    class ExtractAccessToken {

        @Test
        @DisplayName("正常提取 Access Token")
        void extractsToken() {
            Cookie[] cookies = {
                    new Cookie("ACCESS_TOKEN", "my-access-token"),
                    new Cookie("OTHER", "other-value")
            };
            when(request.getCookies()).thenReturn(cookies);

            String token = CookieUtil.extractAccessToken(request);
            assertThat(token).isEqualTo("my-access-token");
        }

        @Test
        @DisplayName("無 Cookie → 回傳 null")
        void noCookies_returnsNull() {
            when(request.getCookies()).thenReturn(null);

            String token = CookieUtil.extractAccessToken(request);
            assertThat(token).isNull();
        }

        @Test
        @DisplayName("Cookie 存在但值為空白 → 回傳 null")
        void blankValue_returnsNull() {
            Cookie[] cookies = { new Cookie("ACCESS_TOKEN", "   ") };
            when(request.getCookies()).thenReturn(cookies);

            String token = CookieUtil.extractAccessToken(request);
            assertThat(token).isNull();
        }

        @Test
        @DisplayName("Cookie 存在但值為空字串 → 回傳 null")
        void emptyValue_returnsNull() {
            Cookie[] cookies = { new Cookie("ACCESS_TOKEN", "") };
            when(request.getCookies()).thenReturn(cookies);

            String token = CookieUtil.extractAccessToken(request);
            assertThat(token).isNull();
        }

        @Test
        @DisplayName("Cookie 名稱不匹配 → 回傳 null")
        void wrongName_returnsNull() {
            Cookie[] cookies = { new Cookie("WRONG_NAME", "some-value") };
            when(request.getCookies()).thenReturn(cookies);

            String token = CookieUtil.extractAccessToken(request);
            assertThat(token).isNull();
        }
    }

    // ==================== extractRefreshToken ====================

    @Nested
    @DisplayName("extractRefreshToken")
    class ExtractRefreshToken {

        @Test
        @DisplayName("正常提取 Refresh Token")
        void extractsToken() {
            Cookie[] cookies = {
                    new Cookie("ACCESS_TOKEN", "access"),
                    new Cookie("REFRESH_TOKEN", "my-refresh-token")
            };
            when(request.getCookies()).thenReturn(cookies);

            String token = CookieUtil.extractRefreshToken(request);
            assertThat(token).isEqualTo("my-refresh-token");
        }

        @Test
        @DisplayName("無 Cookie → 回傳 null")
        void noCookies_returnsNull() {
            when(request.getCookies()).thenReturn(null);

            String token = CookieUtil.extractRefreshToken(request);
            assertThat(token).isNull();
        }

        @Test
        @DisplayName("只有 Access Token、無 Refresh → 回傳 null")
        void onlyAccessToken_returnsNull() {
            Cookie[] cookies = { new Cookie("ACCESS_TOKEN", "access-value") };
            when(request.getCookies()).thenReturn(cookies);

            String token = CookieUtil.extractRefreshToken(request);
            assertThat(token).isNull();
        }
    }

    // ==================== 常數 ====================

    @Test
    @DisplayName("常數名稱正確")
    void constantsCorrect() {
        assertThat(CookieUtil.ACCESS_TOKEN_COOKIE).isEqualTo("ACCESS_TOKEN");
        assertThat(CookieUtil.REFRESH_TOKEN_COOKIE).isEqualTo("REFRESH_TOKEN");
    }
}
