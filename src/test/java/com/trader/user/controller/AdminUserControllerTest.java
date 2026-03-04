package com.trader.user.controller;

import com.trader.shared.service.AuditService;
import com.trader.shared.util.SecurityUtil;
import com.trader.user.dto.AdminUpdateUserRequest;
import com.trader.user.dto.AdminUserListResponse;
import com.trader.user.dto.AdminUserListResponse.AdminUserSummary;
import com.trader.user.entity.User;
import com.trader.user.repository.UserLineBindingRepository;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserTradeSettingsService;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
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
    private UserTradeSettingsService tradeSettingsService;
    private AuditService auditService;
    private AdminUserController controller;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        lineBindingRepository = mock(UserLineBindingRepository.class);
        tradeSettingsService = mock(UserTradeSettingsService.class);
        auditService = mock(AuditService.class);
        controller = new AdminUserController(userRepository, lineBindingRepository, tradeSettingsService, auditService);

        // 預設無 LINE 綁定
        when(lineBindingRepository.findUserIdsWithEnabledBinding()).thenReturn(List.of());
        when(lineBindingRepository.findByUserIdAndEnabledTrue(anyString())).thenReturn(Optional.empty());
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
}
