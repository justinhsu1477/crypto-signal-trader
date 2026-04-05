package com.trader.integration;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Subscription Integration Test — 訂閱 API 鏈路
 *
 * 驗證 Controller → Service → Repository → PostgreSQL 完整鏈路
 */
@DisplayName("Subscription Integration Test — 訂閱 API")
class SubscriptionIntegrationTest extends BaseIntegrationTest {

    @Nested
    @DisplayName("方案查詢")
    class Plans {

        @Test
        @DisplayName("GET /api/subscription/plans → 200 + 回傳方案列表")
        void getPlans() throws Exception {
            Cookie cookie = registerAndLogin();

            mockMvc.perform(get("/api/subscription/plans")
                            .cookie(cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }

        @Test
        @DisplayName("未登入查方案 → 401")
        void getPlansUnauthorized() throws Exception {
            mockMvc.perform(get("/api/subscription/plans"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("訂閱狀態")
    class Status {

        @Test
        @DisplayName("GET /api/subscription/status → 200 + 回傳訂閱狀態")
        void getStatus() throws Exception {
            Cookie cookie = registerAndLogin();

            mockMvc.perform(get("/api/subscription/status")
                            .cookie(cookie))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("新用戶無訂閱 → status 回傳但無 active plan")
        void newUserNoSubscription() throws Exception {
            Cookie cookie = registerAndLogin();

            mockMvc.perform(get("/api/subscription/status")
                            .cookie(cookie))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
        }
    }
}
