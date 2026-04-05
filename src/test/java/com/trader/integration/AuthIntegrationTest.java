package com.trader.integration;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Auth Integration Test — 完整 JWT 認證鏈路
 *
 * 測試：Register → Login → JWT Cookie → 存取保護 API → 401/403
 * 走真正的 Spring Security Filter Chain + PostgreSQL
 */
@DisplayName("Auth Integration Test — JWT 認證鏈路")
class AuthIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("註冊")
    class Register {

        @Test
        @DisplayName("正常註冊 → 200 + userId")
        void registerSuccess() throws Exception {
            String body = """
                    {"email":"new@hookfi.com","password":"NewPass123","name":"New User","termsAccepted":true}
                    """;

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.userId").isNotEmpty())
                    .andExpect(jsonPath("$.email").value("new@hookfi.com"));
        }

        @Test
        @DisplayName("密碼太短 → 400")
        void registerWeakPassword() throws Exception {
            String body = """
                    {"email":"weak@hookfi.com","password":"short","name":"Weak","termsAccepted":true}
                    """;

            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
    }

    @Nested
    @DisplayName("登入")
    class Login {

        @Test
        @DisplayName("正常登入 → 200 + HttpOnly Cookie")
        void loginSuccess() throws Exception {
            Cookie cookie = registerAndLogin();

            org.assertj.core.api.Assertions.assertThat(cookie.getName()).isEqualTo("accessToken");
            org.assertj.core.api.Assertions.assertThat(cookie.isHttpOnly()).isTrue();
        }

        @Test
        @DisplayName("錯誤密碼 → 401")
        void loginWrongPassword() throws Exception {
            // 先註冊
            String registerBody = """
                    {"email":"wrong@hookfi.com","password":"CorrectPass123","name":"Wrong","termsAccepted":true}
                    """;
            mockMvc.perform(post("/api/auth/register")
                    .contentType(MediaType.APPLICATION_JSON).content(registerBody));

            // 用錯密碼登入
            String loginBody = """
                    {"email":"wrong@hookfi.com","password":"WrongPass123"}
                    """;
            mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginBody))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("受保護 API 存取")
    class ProtectedAccess {

        @Test
        @DisplayName("帶 JWT Cookie → 200")
        void withValidJwt() throws Exception {
            Cookie cookie = registerAndLogin();

            mockMvc.perform(get("/api/subscription/status")
                            .cookie(cookie))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("無 JWT → 401")
        void withoutJwt() throws Exception {
            mockMvc.perform(get("/api/subscription/status"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("Health endpoint 不需要 JWT → 200")
        void healthPublic() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk());
        }
    }
}
