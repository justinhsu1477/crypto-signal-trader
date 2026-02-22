package com.trader.dashboard.controller;

import com.trader.dashboard.dto.DashboardOverview;
import com.trader.dashboard.dto.PerformanceStats;
import com.trader.dashboard.dto.TradeHistoryResponse;
import com.trader.dashboard.service.DashboardService;
import com.trader.shared.util.SecurityUtil;
import com.trader.user.dto.TradeSettingsDefaultsResponse;
import com.trader.user.dto.TradeSettingsResponse;
import com.trader.user.dto.UpdateTradeSettingsRequest;
import com.trader.user.entity.User;
import com.trader.user.entity.UserDiscordWebhook;
import com.trader.user.entity.UserTradeSettings;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserDiscordWebhookService;
import com.trader.user.service.UserTradeSettingsService;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DashboardController 單元測試
 *
 * 覆蓋：overview, performance, trades, auto-trade-status, trade-settings, discord-webhooks
 */
class DashboardControllerTest {

    private DashboardService dashboardService;
    private UserRepository userRepository;
    private UserDiscordWebhookService webhookService;
    private UserTradeSettingsService tradeSettingsService;
    private DashboardController controller;
    private MockedStatic<SecurityUtil> securityUtil;

    @BeforeEach
    void setUp() {
        dashboardService = mock(DashboardService.class);
        userRepository = mock(UserRepository.class);
        webhookService = mock(UserDiscordWebhookService.class);
        tradeSettingsService = mock(UserTradeSettingsService.class);
        controller = new DashboardController(dashboardService, userRepository, webhookService, tradeSettingsService);
        securityUtil = mockStatic(SecurityUtil.class);
        securityUtil.when(SecurityUtil::getCurrentUserId).thenReturn("user-123");
    }

    @AfterEach
    void tearDown() {
        securityUtil.close();
    }

    // ==================== Overview / Performance / Trades ====================

    @Nested
    @DisplayName("基本查詢 API")
    class BasicQueryTests {

        @Test
        @DisplayName("getOverview — 回傳 DashboardOverview")
        void getOverview() {
            DashboardOverview overview = DashboardOverview.builder()
                    .autoTradeEnabled(true)
                    .positions(List.of())
                    .build();
            when(dashboardService.getOverview("user-123")).thenReturn(overview);

            ResponseEntity<DashboardOverview> response = controller.getOverview();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(overview);
        }

        @Test
        @DisplayName("getPerformance — 傳遞 days 參數")
        void getPerformance() {
            PerformanceStats stats = PerformanceStats.builder().build();
            when(dashboardService.getPerformance("user-123", 30)).thenReturn(stats);

            ResponseEntity<PerformanceStats> response = controller.getPerformance(30);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(stats);
        }

        @Test
        @DisplayName("getTradeHistory — 傳遞分頁參數")
        void getTradeHistory() {
            TradeHistoryResponse history = TradeHistoryResponse.builder()
                    .trades(List.of())
                    .build();
            when(dashboardService.getTradeHistory("user-123", 0, 20)).thenReturn(history);

            ResponseEntity<TradeHistoryResponse> response = controller.getTradeHistory(0, 20);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEqualTo(history);
        }
    }

    // ==================== Auto Trade Status ====================

    @Nested
    @DisplayName("auto-trade-status — 自動跟單開關")
    class AutoTradeStatusTests {

        @Test
        @DisplayName("GET — 用戶存在 — 回傳狀態")
        void getAutoTradeStatusFound() {
            User user = User.builder().userId("user-123").autoTradeEnabled(true).build();
            when(userRepository.findById("user-123")).thenReturn(Optional.of(user));

            ResponseEntity<Map<String, Object>> response = controller.getAutoTradeStatus();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().get("autoTradeEnabled")).isEqualTo(true);
            assertThat(response.getBody().get("userId")).isEqualTo("user-123");
        }

        @Test
        @DisplayName("GET — 用戶不存在 — 404")
        void getAutoTradeStatusNotFound() {
            when(userRepository.findById("user-123")).thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> response = controller.getAutoTradeStatus();

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("POST — 啟用 — 儲存並回傳")
        void postEnableAutoTrade() {
            User user = User.builder().userId("user-123").autoTradeEnabled(false).build();
            when(userRepository.findById("user-123")).thenReturn(Optional.of(user));

            ResponseEntity<Map<String, Object>> response =
                    controller.updateAutoTradeStatus(Map.of("enabled", true));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().get("autoTradeEnabled")).isEqualTo(true);
            verify(userRepository).save(user);
            assertThat(user.isAutoTradeEnabled()).isTrue();
        }

        @Test
        @DisplayName("POST — 停用 — 儲存並回傳")
        void postDisableAutoTrade() {
            User user = User.builder().userId("user-123").autoTradeEnabled(true).build();
            when(userRepository.findById("user-123")).thenReturn(Optional.of(user));

            ResponseEntity<Map<String, Object>> response =
                    controller.updateAutoTradeStatus(Map.of("enabled", false));

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().get("autoTradeEnabled")).isEqualTo(false);
            assertThat(response.getBody().get("message").toString()).contains("關閉");
        }

        @Test
        @DisplayName("POST — enabled 為 null — 400")
        void postNullEnabled() {
            Map<String, Boolean> body = new HashMap<>();
            body.put("enabled", null);

            ResponseEntity<Map<String, Object>> response = controller.updateAutoTradeStatus(body);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().get("error").toString()).contains("enabled");
        }

        @Test
        @DisplayName("POST — 用戶不存在 — 404")
        void postUserNotFound() {
            when(userRepository.findById("user-123")).thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> response =
                    controller.updateAutoTradeStatus(Map.of("enabled", true));

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }
    }

    // ==================== Trade Settings ====================

    @Nested
    @DisplayName("trade-settings — 交易參數管理")
    class TradeSettingsTests {

        @Test
        @DisplayName("GET — 回傳用戶設定")
        void getTradeSettings() {
            UserTradeSettings settings = UserTradeSettings.builder()
                    .userId("user-123")
                    .riskPercent(0.2)
                    .maxLeverage(20)
                    .build();
            TradeSettingsResponse dto = TradeSettingsResponse.builder()
                    .userId("user-123")
                    .riskPercent(0.2)
                    .maxLeverage(20)
                    .build();
            when(tradeSettingsService.getOrCreateSettings("user-123")).thenReturn(settings);
            when(tradeSettingsService.toResponse(settings)).thenReturn(dto);

            ResponseEntity<TradeSettingsResponse> response = controller.getTradeSettings();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().getRiskPercent()).isEqualTo(0.2);
        }

        @Test
        @DisplayName("PUT — 更新成功 — 回傳新設定")
        void updateTradeSettings() {
            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setRiskPercent(0.15);

            UserTradeSettings updated = UserTradeSettings.builder()
                    .userId("user-123").riskPercent(0.15).build();
            TradeSettingsResponse dto = TradeSettingsResponse.builder()
                    .userId("user-123").riskPercent(0.15).build();
            when(tradeSettingsService.updateSettings("user-123", request)).thenReturn(updated);
            when(tradeSettingsService.toResponse(updated)).thenReturn(dto);

            ResponseEntity<?> response = controller.updateTradeSettings(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
        }

        @Test
        @DisplayName("PUT — 驗證失敗 — 400")
        void updateTradeSettingsValidationError() {
            UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
            request.setRiskPercent(5.0); // 超出範圍

            when(tradeSettingsService.updateSettings("user-123", request))
                    .thenThrow(new IllegalArgumentException("riskPercent 必須在 0.01 ~ 1.00 之間"));

            ResponseEntity<?> response = controller.updateTradeSettings(request);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("GET defaults — 回傳 free 方案預設值")
        void getTradeSettingsDefaults() {
            ResponseEntity<TradeSettingsDefaultsResponse> response = controller.getTradeSettingsDefaults();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            TradeSettingsDefaultsResponse body = response.getBody();
            assertThat(body.getPlanId()).isEqualTo("free");
            assertThat(body.getMaxRiskPercent()).isEqualTo(0.10);
            assertThat(body.getMaxPositions()).isEqualTo(1);
            assertThat(body.getMaxSymbols()).isEqualTo(3);
            assertThat(body.getDcaLayersAllowed()).isEqualTo(0);
        }
    }

    // ==================== Discord Webhooks ====================

    @Nested
    @DisplayName("discord-webhooks — Webhook 管理")
    class DiscordWebhookTests {

        @Test
        @DisplayName("GET — 回傳所有 webhook 和 primary")
        void getWebhooks() {
            UserDiscordWebhook wh = new UserDiscordWebhook();
            wh.setWebhookId("wh-1");
            wh.setUserId("user-123");
            wh.setEnabled(true);

            when(webhookService.getAllWebhooks("user-123")).thenReturn(List.of(wh));
            when(webhookService.getPrimaryWebhook("user-123")).thenReturn(Optional.of(wh));

            ResponseEntity<Map<String, Object>> response = controller.getWebhooks();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().get("userId")).isEqualTo("user-123");
            assertThat(response.getBody().get("primaryWebhookId")).isEqualTo("wh-1");
            assertThat((List<?>) response.getBody().get("webhooks")).hasSize(1);
        }

        @Test
        @DisplayName("GET — 無 primary webhook — primaryWebhookId 為 null")
        void getWebhooksNoPrimary() {
            when(webhookService.getAllWebhooks("user-123")).thenReturn(List.of());
            when(webhookService.getPrimaryWebhook("user-123")).thenReturn(Optional.empty());

            ResponseEntity<Map<String, Object>> response = controller.getWebhooks();

            assertThat(response.getBody().get("primaryWebhookId")).isNull();
        }

        @Test
        @DisplayName("POST — 建立 webhook 成功")
        void createWebhookSuccess() {
            UserDiscordWebhook wh = new UserDiscordWebhook();
            wh.setWebhookId("wh-new");
            wh.setUserId("user-123");
            wh.setName("我的通知");
            wh.setEnabled(true);

            when(webhookService.createOrUpdateWebhook("user-123",
                    "https://discord.com/api/webhooks/123/abc", "我的通知"))
                    .thenReturn(wh);

            Map<String, String> body = Map.of(
                    "webhookUrl", "https://discord.com/api/webhooks/123/abc",
                    "name", "我的通知");

            ResponseEntity<Map<String, Object>> response = controller.createWebhook(body);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().get("webhookId")).isEqualTo("wh-new");
            assertThat(response.getBody().get("message").toString()).contains("成功");
        }

        @Test
        @DisplayName("POST — webhookUrl 為空 — 400")
        void createWebhookEmptyUrl() {
            Map<String, String> body = new HashMap<>();
            body.put("webhookUrl", "");

            ResponseEntity<Map<String, Object>> response = controller.createWebhook(body);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().get("error").toString()).contains("webhookUrl");
        }

        @Test
        @DisplayName("POST — webhookUrl 為 null — 400")
        void createWebhookNullUrl() {
            Map<String, String> body = new HashMap<>();
            body.put("webhookUrl", null);

            ResponseEntity<Map<String, Object>> response = controller.createWebhook(body);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("POST — 無效 URL 格式 — 400")
        void createWebhookInvalidUrl() {
            Map<String, String> body = Map.of("webhookUrl", "https://example.com/hook");

            ResponseEntity<Map<String, Object>> response = controller.createWebhook(body);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(response.getBody().get("error").toString()).contains("無效");
        }

        @Test
        @DisplayName("POST disable — 停用 webhook")
        void disableWebhook() {
            ResponseEntity<Map<String, Object>> response = controller.disableWebhook("wh-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(webhookService).disableWebhook("wh-1");
            assertThat(response.getBody().get("message").toString()).contains("停用");
        }

        @Test
        @DisplayName("DELETE — 刪除 webhook")
        void deleteWebhook() {
            ResponseEntity<Map<String, Object>> response = controller.deleteWebhook("wh-1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(webhookService).deleteWebhook("wh-1");
            assertThat(response.getBody().get("message").toString()).contains("刪除");
        }
    }
}
