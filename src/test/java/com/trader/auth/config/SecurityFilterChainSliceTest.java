package com.trader.auth.config;

import com.trader.auth.filter.JwtAuthenticationFilter;
import com.trader.auth.filter.MonitorApiKeyFilter;
import com.trader.auth.handler.CustomAccessDeniedHandler;
import com.trader.auth.handler.CustomAuthenticationEntryPoint;
import com.trader.auth.service.JwtService;
import com.trader.auth.util.ClientIpResolver;
import com.trader.chatbot.service.DiscordBotService;
import com.trader.shared.controller.HealthController;
import com.trader.shared.service.AuditService;
import com.trader.shared.util.BinanceApiRateLimiter;
import com.trader.trading.service.MonitorHeartbeatService;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Security Filter Chain Slice Test
 *
 * 使用 @WebMvcTest 載入真實的 Spring Security 設定，
 * 驗證路徑權限規則、Filter 順序、認證/授權行為。
 *
 * 與純 unit test 的差異：
 * - unit test mock 了 FilterChain，無法測 filter 互動
 * - 此測試透過 MockMvc 走完整 Security filter chain
 */
@WebMvcTest(controllers = HealthController.class)
@Import({AuthConfig.class, MonitorApiKeyFilter.class, JwtAuthenticationFilter.class,
        CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
@TestPropertySource(properties = {
        "monitor.api-key=test-monitor-key",
        "jwt.secret=test-secret-key-for-slice-test-minimum-256-bits-long-enough",
        "jwt.expiration-ms=1800000",
        "jwt.refresh-expiration-ms=259200000"
})
@DisplayName("SecurityFilterChain — Slice Test")
class SecurityFilterChainSliceTest {

    @Autowired
    private MockMvc mockMvc;

    // HealthController 依賴
    @MockBean
    private DataSource dataSource;
    @MockBean
    private BinanceApiRateLimiter binanceApiRateLimiter;
    @MockBean
    private MonitorHeartbeatService monitorHeartbeatService;
    @MockBean
    private DiscordBotService discordBotService;

    // Filter / Handler 依賴
    @MockBean
    private JwtService jwtService;
    @MockBean
    private UserRepository userRepository;
    @MockBean
    private AuditService auditService;
    @MockBean
    private ClientIpResolver clientIpResolver;

    @Nested
    @DisplayName("公開端點 — 無需認證")
    class PublicEndpoints {

        @Test
        @DisplayName("GET /api/health → 200（permitAll）")
        void healthEndpointIsPublic() throws Exception {
            mockMvc.perform(get("/api/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("UP"));
        }

        @Test
        @DisplayName("GET /api/health/deep → 200（permitAll，即使無認證）")
        void deepHealthIsPublic() throws Exception {
            // deep health 需要 DataSource mock，但重點是不需要認證
            // DataSource mock 預設回傳 null → 會走 catch 分支，但 HTTP 層面不是 401
            mockMvc.perform(get("/api/health/deep"))
                    .andExpect(status().isServiceUnavailable()); // 503 因為 DB mock 失敗，但不是 401
        }
    }

    @Nested
    @DisplayName("受保護端點 — 需要認證")
    class ProtectedEndpoints {

        @Test
        @DisplayName("GET /api/dashboard/overview 無 token → 401")
        void dashboardWithoutAuth() throws Exception {
            mockMvc.perform(get("/api/dashboard/overview"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("未授權 (401)"));
        }

        @Test
        @DisplayName("GET /api/user/profile 無 token → 401")
        void userProfileWithoutAuth() throws Exception {
            mockMvc.perform(get("/api/user/profile"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /api/positions 無 token → 401")
        void positionsWithoutAuth() throws Exception {
            mockMvc.perform(get("/api/positions"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Admin 端點 — 需要 ROLE_ADMIN")
    class AdminEndpoints {

        @Test
        @DisplayName("GET /api/admin/dashboard/overview 無 token → 401")
        void adminWithoutAuth() throws Exception {
            mockMvc.perform(get("/api/admin/dashboard/overview"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("GET /api/admin/dashboard/overview 帶 Monitor API Key → 200 或 404（有權限）")
        void adminWithMonitorApiKey() throws Exception {
            // Monitor API Key 通過 → ROLE_ADMIN → 不會回 401/403
            // controller 不存在此路由 → 預期不是 401/403
            mockMvc.perform(get("/api/admin/dashboard/overview")
                            .header("X-Api-Key", "test-monitor-key"))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        // 有權限通過 Security → 不應該是 401 或 403
                        assert status != 401 : "Expected non-401, got 401 — API Key auth failed";
                        assert status != 403 : "Expected non-403, got 403 — ROLE_ADMIN not granted";
                    });
        }

        @Test
        @DisplayName("GET /api/admin/dashboard/overview 帶錯誤 API Key → 401")
        void adminWithWrongApiKey() throws Exception {
            mockMvc.perform(get("/api/admin/dashboard/overview")
                            .header("X-Api-Key", "wrong-key"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("未定義路由 — denyAll")
    class UndefinedRoutes {

        @Test
        @DisplayName("GET /api/nonexistent → 401（anyRequest().denyAll()）")
        void undefinedRouteIsDenied() throws Exception {
            mockMvc.perform(get("/api/nonexistent"))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("Monitor API Key 認證")
    class MonitorApiKeyAuth {

        @Test
        @DisplayName("X-Api-Key header 正確 → 取得 ROLE_ADMIN 存取 /api/heartbeat")
        void correctApiKeyGrantsAccess() throws Exception {
            // /api/heartbeat 需要 authenticated()
            // Monitor API Key → authenticated + ROLE_ADMIN
            // controller 不在此 @WebMvcTest 範圍 → 不是 401/403 即代表認證成功
            mockMvc.perform(get("/api/heartbeat")
                            .header("X-Api-Key", "test-monitor-key"))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assert status != 401 : "API Key auth should succeed";
                        assert status != 403 : "API Key should have sufficient permissions";
                    });
        }

        @Test
        @DisplayName("無 header 存取 /api/heartbeat → 401")
        void noApiKeyDenied() throws Exception {
            mockMvc.perform(get("/api/heartbeat"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
