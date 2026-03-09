package com.trader.user.controller;

import com.trader.shared.dto.OAuthProviderInfo;
import com.trader.shared.service.AuditService;
import com.trader.shared.util.SecurityUtil;
import com.trader.user.dto.*;
import com.trader.user.dto.AdminUserListResponse.AdminUserSummary;
import com.trader.user.entity.*;
import com.trader.user.event.AdminUserDetailRequestEvent;
import com.trader.user.repository.*;
import com.trader.user.service.UserService;
import com.trader.user.service.UserTradeSettingsService;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AdminUserController 單元測試（純 Mockito，不使用 Spring Context）
 */
class AdminUserControllerTest {

    private UserRepository userRepository;
    private UserLineBindingRepository lineBindingRepository;
    private UserApiKeyRepository apiKeyRepository;
    private UserDiscordWebhookRepository discordWebhookRepository;
    private UserNotificationPreferencesRepository notificationPreferencesRepository;
    private UserTradeSettingsService tradeSettingsService;
    private UserService userService;
    private AuditService auditService;
    private ApplicationEventPublisher eventPublisher;
    private AdminUserController controller;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        lineBindingRepository = mock(UserLineBindingRepository.class);
        apiKeyRepository = mock(UserApiKeyRepository.class);
        discordWebhookRepository = mock(UserDiscordWebhookRepository.class);
        notificationPreferencesRepository = mock(UserNotificationPreferencesRepository.class);
        tradeSettingsService = mock(UserTradeSettingsService.class);
        userService = mock(UserService.class);
        auditService = mock(AuditService.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        controller = new AdminUserController(
                userRepository, lineBindingRepository, apiKeyRepository,
                discordWebhookRepository, notificationPreferencesRepository,
                tradeSettingsService, userService, auditService, eventPublisher);

        // 預設無 LINE 綁定
        when(lineBindingRepository.findUserIdsWithEnabledBinding()).thenReturn(List.of());
        when(lineBindingRepository.findByUserIdAndEnabledTrue(anyString())).thenReturn(Optional.empty());
        // 預設空資料
        when(apiKeyRepository.findByUserId(anyString())).thenReturn(List.of());
        when(discordWebhookRepository.findByUserId(anyString())).thenReturn(List.of());
        when(notificationPreferencesRepository.findById(anyString())).thenReturn(Optional.empty());
    }

    // ==================== listUsers ====================

    @Nested
    @DisplayName("GET /api/admin/users — listUsers")
    class ListUsersTests {

        @Test
        @DisplayName("預設排序 createdAt desc")
        void defaultSortCreatedAtDesc() {
            User u1 = User.builder().userId("u1").email("a@test.com").name("A")
                    .enabled(true).autoTradeEnabled(true).role(User.Role.USER)
                    .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
            User u2 = User.builder().userId("u2").email("b@test.com").name("B")
                    .enabled(true).autoTradeEnabled(false).role(User.Role.ADMIN)
                    .createdAt(LocalDateTime.of(2026, 3, 1, 0, 0)).build();
            when(userRepository.findAll()).thenReturn(List.of(u1, u2));

            ResponseEntity<AdminUserListResponse> response = controller.listUsers("createdAt", "desc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            AdminUserListResponse body = response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getUsers()).hasSize(2);
            // desc: u2 (2026-03) 在前, u1 (2026-01) 在後
            assertThat(body.getUsers().get(0).getUserId()).isEqualTo("u2");
            assertThat(body.getUsers().get(1).getUserId()).isEqualTo("u1");
            assertThat(body.getTotalUsers()).isEqualTo(2);
            assertThat(body.getActiveUsers()).isEqualTo(2);
            assertThat(body.getAdminUsers()).isEqualTo(1);
        }

        @Test
        @DisplayName("依 email asc 排序")
        void sortByEmailAsc() {
            User u1 = User.builder().userId("u1").email("zebra@test.com").name("Z")
                    .enabled(true).role(User.Role.USER)
                    .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
            User u2 = User.builder().userId("u2").email("alpha@test.com").name("A")
                    .enabled(true).role(User.Role.USER)
                    .createdAt(LocalDateTime.of(2026, 2, 1, 0, 0)).build();
            when(userRepository.findAll()).thenReturn(List.of(u1, u2));

            ResponseEntity<AdminUserListResponse> response = controller.listUsers("email", "asc");

            AdminUserListResponse body = response.getBody();
            assertThat(body.getUsers().get(0).getEmail()).isEqualTo("alpha@test.com");
            assertThat(body.getUsers().get(1).getEmail()).isEqualTo("zebra@test.com");
        }

        @Test
        @DisplayName("依 name 排序 — null 排最後")
        void sortByNameNullLast() {
            User u1 = User.builder().userId("u1").email("a@test.com").name(null)
                    .enabled(true).role(User.Role.USER)
                    .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
            User u2 = User.builder().userId("u2").email("b@test.com").name("Bob")
                    .enabled(true).role(User.Role.USER)
                    .createdAt(LocalDateTime.of(2026, 2, 1, 0, 0)).build();
            User u3 = User.builder().userId("u3").email("c@test.com").name("Alice")
                    .enabled(true).role(User.Role.USER)
                    .createdAt(LocalDateTime.of(2026, 3, 1, 0, 0)).build();
            when(userRepository.findAll()).thenReturn(List.of(u1, u2, u3));

            ResponseEntity<AdminUserListResponse> response = controller.listUsers("name", "asc");

            List<AdminUserSummary> users = response.getBody().getUsers();
            // asc: Alice, Bob, null(last)
            assertThat(users.get(0).getName()).isEqualTo("Alice");
            assertThat(users.get(1).getName()).isEqualTo("Bob");
            assertThat(users.get(2).getName()).isNull();
        }

        @Test
        @DisplayName("未知 sortBy → fallback 到 createdAt")
        void unknownSortByFallbackToCreatedAt() {
            User u1 = User.builder().userId("u1").email("a@test.com").name("A")
                    .enabled(true).role(User.Role.USER)
                    .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
            User u2 = User.builder().userId("u2").email("b@test.com").name("B")
                    .enabled(true).role(User.Role.USER)
                    .createdAt(LocalDateTime.of(2026, 3, 1, 0, 0)).build();
            when(userRepository.findAll()).thenReturn(List.of(u1, u2));

            ResponseEntity<AdminUserListResponse> response = controller.listUsers("nonExistentField", "desc");

            // fallback 到 createdAt desc → u2 在前
            List<AdminUserSummary> users = response.getBody().getUsers();
            assertThat(users.get(0).getUserId()).isEqualTo("u2");
            assertThat(users.get(1).getUserId()).isEqualTo("u1");
        }

        @Test
        @DisplayName("無用戶 → 200 + 空列表")
        void emptyUsersReturns200WithEmptyList() {
            when(userRepository.findAll()).thenReturn(List.of());

            ResponseEntity<AdminUserListResponse> response = controller.listUsers("createdAt", "desc");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            AdminUserListResponse body = response.getBody();
            assertThat(body.getUsers()).isEmpty();
            assertThat(body.getTotalUsers()).isEqualTo(0);
            assertThat(body.getActiveUsers()).isEqualTo(0);
            assertThat(body.getAdminUsers()).isEqualTo(0);
        }
    }

    // ==================== updateUser ====================

    @Nested
    @DisplayName("PUT /api/admin/users/{userId} — updateUser")
    class UpdateUserTests {

        @Test
        @DisplayName("成功停用用戶")
        void successfulDisableUser() {
            User target = User.builder().userId("u2").email("b@test.com").name("B")
                    .enabled(true).role(User.Role.USER)
                    .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
            when(userRepository.findById("u2")).thenReturn(Optional.of(target));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            AdminUpdateUserRequest request = new AdminUpdateUserRequest();
            request.setEnabled(false);

            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                ResponseEntity<?> response = controller.updateUser("u2", request);

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(target.isEnabled()).isFalse();
                verify(userRepository).save(target);
                verify(auditService).log(eq("admin1"), eq("ADMIN_UPDATE_USER"),
                        eq("/api/admin/users/u2"), eq("SUCCESS"), eq(""), anyString());
            }
        }

        @Test
        @DisplayName("成功啟用自動交易")
        void successfulEnableAutoTrade() {
            User target = User.builder().userId("u2").email("b@test.com").name("B")
                    .enabled(true).autoTradeEnabled(false).role(User.Role.USER)
                    .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
            when(userRepository.findById("u2")).thenReturn(Optional.of(target));
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            AdminUpdateUserRequest request = new AdminUpdateUserRequest();
            request.setAutoTradeEnabled(true);

            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                ResponseEntity<?> response = controller.updateUser("u2", request);

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                assertThat(target.isAutoTradeEnabled()).isTrue();
                verify(userRepository).save(target);
            }
        }

        @Test
        @DisplayName("不可停用自己 → 400")
        @SuppressWarnings("unchecked")
        void cannotDisableSelf() {
            User self = User.builder().userId("admin1").email("admin@test.com").name("Admin")
                    .enabled(true).role(User.Role.ADMIN)
                    .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
            when(userRepository.findById("admin1")).thenReturn(Optional.of(self));

            AdminUpdateUserRequest request = new AdminUpdateUserRequest();
            request.setEnabled(false);

            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                ResponseEntity<?> response = controller.updateUser("admin1", request);

                assertThat(response.getStatusCode().value()).isEqualTo(400);
                Map<String, String> body = (Map<String, String>) response.getBody();
                assertThat(body.get("error")).contains("不可停用自己");
                verify(userRepository, never()).save(any());
            }
        }

        @Test
        @DisplayName("不可降級最後一個 ADMIN → 400")
        @SuppressWarnings("unchecked")
        void cannotDemoteLastAdmin() {
            User lastAdmin = User.builder().userId("u1").email("admin@test.com").name("Admin")
                    .enabled(true).role(User.Role.ADMIN)
                    .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
            when(userRepository.findById("u1")).thenReturn(Optional.of(lastAdmin));
            when(userRepository.countByRole(User.Role.ADMIN)).thenReturn(1L);

            AdminUpdateUserRequest request = new AdminUpdateUserRequest();
            request.setRole("USER");

            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("u1");

                ResponseEntity<?> response = controller.updateUser("u1", request);

                assertThat(response.getStatusCode().value()).isEqualTo(400);
                Map<String, String> body = (Map<String, String>) response.getBody();
                assertThat(body.get("error")).contains("至少需要一個管理員");
                verify(userRepository, never()).save(any());
            }
        }

        @Test
        @DisplayName("無效角色 → 400")
        @SuppressWarnings("unchecked")
        void invalidRole() {
            User target = User.builder().userId("u2").email("b@test.com").name("B")
                    .enabled(true).role(User.Role.USER)
                    .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build();
            when(userRepository.findById("u2")).thenReturn(Optional.of(target));

            AdminUpdateUserRequest request = new AdminUpdateUserRequest();
            request.setRole("SUPERADMIN");

            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                ResponseEntity<?> response = controller.updateUser("u2", request);

                assertThat(response.getStatusCode().value()).isEqualTo(400);
                Map<String, String> body = (Map<String, String>) response.getBody();
                assertThat(body.get("error")).contains("無效角色");
                verify(userRepository, never()).save(any());
            }
        }

        @Test
        @DisplayName("userId 不存在 → 404")
        void userNotFound() {
            when(userRepository.findById("nonexist")).thenReturn(Optional.empty());

            AdminUpdateUserRequest request = new AdminUpdateUserRequest();
            request.setEnabled(false);

            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                ResponseEntity<?> response = controller.updateUser("nonexist", request);

                assertThat(response.getStatusCode().value()).isEqualTo(404);
                verify(userRepository, never()).save(any());
            }
        }
    }

    // ==================== getUserDetail ====================

    @Nested
    @DisplayName("GET /api/admin/users/{userId} — getUserDetail")
    class GetUserDetailTests {

        private User fullUser;
        private UserTradeSettings dummySettings;
        private TradeSettingsResponse dummySettingsResponse;

        @BeforeEach
        void setUpDetail() {
            fullUser = User.builder()
                    .userId("u1").email("user@test.com").name("Test User")
                    .role(User.Role.USER).enabled(true).emailVerified(true)
                    .autoTradeEnabled(true).passwordHash("hashed")
                    .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                    .updatedAt(LocalDateTime.of(2026, 2, 1, 0, 0))
                    .passwordChangedAt(LocalDateTime.of(2026, 1, 15, 0, 0))
                    .build();

            dummySettings = UserTradeSettings.builder().userId("u1").build();
            dummySettingsResponse = TradeSettingsResponse.builder()
                    .userId("u1").riskPercent(1.0).maxLeverage(10).build();

            when(tradeSettingsService.getOrCreateSettings("u1")).thenReturn(dummySettings);
            when(tradeSettingsService.toResponse(dummySettings)).thenReturn(dummySettingsResponse);
        }

        @Test
        @DisplayName("完整用戶詳情 — 有 LINE、API Key、Webhook、通知偏好、OAuth")
        void fullUserDetail() {
            when(userRepository.findById("u1")).thenReturn(Optional.of(fullUser));
            when(lineBindingRepository.findByUserIdAndEnabledTrue("u1"))
                    .thenReturn(Optional.of(UserLineBinding.builder()
                            .userId("u1").lineUserId("L123").displayName("LINE User")
                            .enabled(true).linkedAt(LocalDateTime.of(2026, 1, 10, 0, 0)).build()));
            when(apiKeyRepository.findByUserId("u1"))
                    .thenReturn(List.of(UserApiKey.builder()
                            .id(1L).userId("u1").exchange("BINANCE")
                            .encryptedApiKey("enc_key").encryptedSecretKey("enc_secret")
                            .createdAt(LocalDateTime.of(2026, 1, 5, 0, 0))
                            .updatedAt(LocalDateTime.of(2026, 1, 6, 0, 0)).build()));
            when(discordWebhookRepository.findByUserId("u1"))
                    .thenReturn(List.of(UserDiscordWebhook.builder()
                            .webhookId("wh1").userId("u1").name("My Webhook")
                            .enabled(true).webhookUrl("https://discord.com/api/webhooks/123456789/abcdefghijklmnop")
                            .createdAt(LocalDateTime.of(2026, 1, 8, 0, 0)).build()));
            when(notificationPreferencesRepository.findById("u1"))
                    .thenReturn(Optional.of(UserNotificationPreferences.builder()
                            .userId("u1").tradeExecution(true).slTpTriggered(true)
                            .protectionLost(true).dailyReport(false)
                            .streamStatus(true).systemAlert(true).build()));

            doAnswer(inv -> {
                AdminUserDetailRequestEvent e = inv.getArgument(0);
                e.setOAuthProviders(List.of(OAuthProviderInfo.builder()
                        .provider("LINE").displayName("OAuth LINE User")
                        .email(null).createdAt("2026-01-10T00:00").build()));
                return null;
            }).when(eventPublisher).publishEvent(any(AdminUserDetailRequestEvent.class));

            ResponseEntity<?> response = controller.getUserDetail("u1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            AdminUserDetailResponse body = (AdminUserDetailResponse) response.getBody();
            assertThat(body).isNotNull();
            assertThat(body.getUserId()).isEqualTo("u1");
            assertThat(body.getEmail()).isEqualTo("user@test.com");
            assertThat(body.isHasPassword()).isTrue();
            assertThat(body.getLoginMethods()).containsExactly("EMAIL", "LINE");
            assertThat(body.getLineBinding()).isNotNull();
            assertThat(body.getLineBinding().getDisplayName()).isEqualTo("LINE User");
            assertThat(body.getApiKeys()).hasSize(1);
            assertThat(body.getApiKeys().get(0).getExchange()).isEqualTo("BINANCE");
            assertThat(body.getDiscordWebhooks()).hasSize(1);
            assertThat(body.getDiscordWebhooks().get(0).getName()).isEqualTo("My Webhook");
            assertThat(body.getNotificationPreferences()).isNotNull();
            assertThat(body.getNotificationPreferences().isDailyReport()).isFalse();
            assertThat(body.getTradeSettings()).isNotNull();
            assertThat(body.getOauthProviders()).hasSize(1);
        }

        @Test
        @DisplayName("最小用戶 — 全部為空/null")
        void minimalUserDetail() {
            User minUser = User.builder()
                    .userId("u2").email(null).name(null)
                    .role(User.Role.USER).enabled(false).emailVerified(false)
                    .autoTradeEnabled(false)
                    .build();

            UserTradeSettings minSettings = UserTradeSettings.builder().userId("u2").build();
            TradeSettingsResponse minSettingsResp = TradeSettingsResponse.builder().userId("u2").build();
            when(userRepository.findById("u2")).thenReturn(Optional.of(minUser));
            when(tradeSettingsService.getOrCreateSettings("u2")).thenReturn(minSettings);
            when(tradeSettingsService.toResponse(minSettings)).thenReturn(minSettingsResp);

            ResponseEntity<?> response = controller.getUserDetail("u2");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            AdminUserDetailResponse body = (AdminUserDetailResponse) response.getBody();
            assertThat(body.getLoginMethods()).isEmpty();
            assertThat(body.getLineBinding()).isNull();
            assertThat(body.getApiKeys()).isEmpty();
            assertThat(body.getDiscordWebhooks()).isEmpty();
            assertThat(body.getNotificationPreferences()).isNull();
            assertThat(body.isHasPassword()).isFalse();
        }

        @Test
        @DisplayName("userId 不存在 → 404")
        void detailUserNotFound() {
            when(userRepository.findById("nonexist")).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getUserDetail("nonexist");

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("Discord webhook URL 已截斷")
        void webhookUrlTruncated() {
            when(userRepository.findById("u1")).thenReturn(Optional.of(fullUser));
            when(discordWebhookRepository.findByUserId("u1"))
                    .thenReturn(List.of(UserDiscordWebhook.builder()
                            .webhookId("wh1").userId("u1").name("WH")
                            .enabled(true).webhookUrl("https://discord.com/api/webhooks/1234567890123456789/ABCDEFghijklmnopqrstuvwxyz0123456789")
                            .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0)).build()));

            ResponseEntity<?> response = controller.getUserDetail("u1");

            AdminUserDetailResponse body = (AdminUserDetailResponse) response.getBody();
            String preview = body.getDiscordWebhooks().get(0).getWebhookUrlPreview();
            assertThat(preview).contains("...");
            assertThat(preview).startsWith("https://discord.com/api/webhooks/");
            assertThat(preview.length()).isLessThan(
                    "https://discord.com/api/webhooks/1234567890123456789/ABCDEFghijklmnopqrstuvwxyz0123456789".length());
        }

        @Test
        @DisplayName("API key 不含加密欄位")
        void apiKeyNoEncryptedFields() {
            when(userRepository.findById("u1")).thenReturn(Optional.of(fullUser));
            when(apiKeyRepository.findByUserId("u1"))
                    .thenReturn(List.of(UserApiKey.builder()
                            .id(1L).userId("u1").exchange("BINANCE")
                            .encryptedApiKey("super_secret_key").encryptedSecretKey("super_secret")
                            .createdAt(LocalDateTime.of(2026, 1, 1, 0, 0))
                            .updatedAt(LocalDateTime.of(2026, 1, 2, 0, 0)).build()));

            ResponseEntity<?> response = controller.getUserDetail("u1");

            AdminUserDetailResponse body = (AdminUserDetailResponse) response.getBody();
            AdminUserDetailResponse.ApiKeyInfo keyInfo = body.getApiKeys().get(0);
            assertThat(keyInfo.getExchange()).isEqualTo("BINANCE");
            assertThat(keyInfo.getCreatedAt()).isNotNull();
            assertThat(AdminUserDetailResponse.ApiKeyInfo.class.getDeclaredFields())
                    .noneMatch(f -> f.getName().toLowerCase().contains("encrypt")
                            || f.getName().toLowerCase().contains("secret"));
        }

        @Test
        @DisplayName("通知偏好為 null 時回傳 null")
        void notificationPreferencesNull() {
            when(userRepository.findById("u1")).thenReturn(Optional.of(fullUser));
            when(notificationPreferencesRepository.findById("u1")).thenReturn(Optional.empty());

            ResponseEntity<?> response = controller.getUserDetail("u1");

            AdminUserDetailResponse body = (AdminUserDetailResponse) response.getBody();
            assertThat(body.getNotificationPreferences()).isNull();
        }

        @Test
        @DisplayName("OAuth providers 從 event 正確回傳")
        void oauthProvidersFromEvent() {
            when(userRepository.findById("u1")).thenReturn(Optional.of(fullUser));
            doAnswer(inv -> {
                AdminUserDetailRequestEvent e = inv.getArgument(0);
                e.setOAuthProviders(List.of(
                        OAuthProviderInfo.builder().provider("LINE").displayName("Line User").build(),
                        OAuthProviderInfo.builder().provider("GOOGLE").displayName("Google User").email("g@test.com").build()
                ));
                return null;
            }).when(eventPublisher).publishEvent(any(AdminUserDetailRequestEvent.class));

            ResponseEntity<?> response = controller.getUserDetail("u1");

            AdminUserDetailResponse body = (AdminUserDetailResponse) response.getBody();
            assertThat(body.getOauthProviders()).hasSize(2);
            assertThat(body.getOauthProviders().get(0).getProvider()).isEqualTo("LINE");
            assertThat(body.getOauthProviders().get(1).getProvider()).isEqualTo("GOOGLE");
            assertThat(body.getLoginMethods()).contains("GOOGLE");
        }
    }

    // ==================== truncateWebhookUrl ====================

    @Nested
    @DisplayName("truncateWebhookUrl")
    class TruncateWebhookUrlTests {

        @Test
        void nullUrl() {
            assertThat(AdminUserController.truncateWebhookUrl(null)).isNull();
        }

        @Test
        void shortUrl() {
            String shortUrl = "https://discord.com/api/webhooks/12";
            assertThat(AdminUserController.truncateWebhookUrl(shortUrl)).isEqualTo(shortUrl);
        }

        @Test
        void longUrlTruncated() {
            String longUrl = "https://discord.com/api/webhooks/1234567890123456789/ABCDEFghijklmnopqrstuvwxyz0123456789";
            String result = AdminUserController.truncateWebhookUrl(longUrl);
            assertThat(result).startsWith("https://discord.com/api/webhooks/");
            assertThat(result).contains("...");
            assertThat(result).endsWith("56789");
            assertThat(result.length()).isEqualTo(44); // 35 + 3 + 6
        }
    }

    // ==================== setUserApiKey ====================

    @Nested
    @DisplayName("PUT /api/admin/users/{userId}/api-keys — setUserApiKey")
    class SetApiKeyTests {

        @Test
        @DisplayName("成功設定 API Key → 200 + audit log")
        @SuppressWarnings("unchecked")
        void successfulSetApiKey() {
            when(userRepository.existsById("u1")).thenReturn(true);

            UserApiKey saved = UserApiKey.builder()
                    .userId("u1").exchange("BINANCE")
                    .updatedAt(LocalDateTime.of(2026, 3, 1, 12, 0)).build();
            when(userService.saveApiKey("u1", "BINANCE", "my-api-key", "my-secret"))
                    .thenReturn(saved);

            SaveApiKeyRequest request = new SaveApiKeyRequest();
            request.setExchange("BINANCE");
            request.setApiKey("my-api-key");
            request.setSecretKey("my-secret");

            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                ResponseEntity<?> response = controller.setUserApiKey("u1", request);

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                Map<String, Object> body = (Map<String, Object>) response.getBody();
                assertThat(body.get("message")).isEqualTo("API Key 已設定");
                assertThat(body.get("exchange")).isEqualTo("BINANCE");

                verify(userService).saveApiKey("u1", "BINANCE", "my-api-key", "my-secret");
                verify(auditService).log(eq("admin1"), eq("ADMIN_SET_API_KEY"),
                        eq("/api/admin/users/u1/api-keys"),
                        eq("SUCCESS"), eq(""), eq("exchange=BINANCE"));
            }
        }

        @Test
        @DisplayName("userId 不存在 → 404")
        void userNotFound() {
            when(userRepository.existsById("nonexist")).thenReturn(false);

            SaveApiKeyRequest request = new SaveApiKeyRequest();
            request.setExchange("BINANCE");
            request.setApiKey("key");
            request.setSecretKey("secret");

            ResponseEntity<?> response = controller.setUserApiKey("nonexist", request);

            assertThat(response.getStatusCode().value()).isEqualTo(404);
            verify(userService, never()).saveApiKey(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("updatedAt 為 null 時回傳空字串")
        @SuppressWarnings("unchecked")
        void updatedAtNull() {
            when(userRepository.existsById("u1")).thenReturn(true);

            UserApiKey saved = UserApiKey.builder()
                    .userId("u1").exchange("BINANCE")
                    .updatedAt(null).build();
            when(userService.saveApiKey(eq("u1"), eq("BINANCE"), anyString(), anyString()))
                    .thenReturn(saved);

            SaveApiKeyRequest request = new SaveApiKeyRequest();
            request.setExchange("BINANCE");
            request.setApiKey("key");
            request.setSecretKey("secret");

            try (MockedStatic<SecurityUtil> mocked = mockStatic(SecurityUtil.class)) {
                mocked.when(SecurityUtil::getCurrentUserId).thenReturn("admin1");

                ResponseEntity<?> response = controller.setUserApiKey("u1", request);

                assertThat(response.getStatusCode().value()).isEqualTo(200);
                Map<String, Object> body = (Map<String, Object>) response.getBody();
                assertThat(body.get("updatedAt")).isEqualTo("");
            }
        }
    }
}
