package com.trader.service;

import com.trader.referral.controller.AdminReferralController;
import com.trader.referral.dto.AdminPendingResponse;
import com.trader.referral.dto.AdminVerifyRequest;
import com.trader.referral.service.ReferralService;
import com.trader.shared.util.SecurityUtil;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AdminReferralController 單元測試
 */
class AdminReferralControllerTest {

    private ReferralService referralService;
    private AdminReferralController controller;

    @BeforeEach
    void setUp() {
        referralService = mock(ReferralService.class);
        controller = new AdminReferralController(referralService);
    }

    @Nested
    @DisplayName("POST /api/admin/referral/verify")
    class Verify {

        @Test
        @DisplayName("approve → 200 + 驗證通過")
        void approveSuccess() {
            try (MockedStatic<SecurityUtil> sec = mockStatic(SecurityUtil.class)) {
                sec.when(SecurityUtil::getCurrentUserId).thenReturn("admin-1");
                doNothing().when(referralService).adminVerify(eq("admin-1"), any());

                ResponseEntity<?> result = controller.verify(
                        new AdminVerifyRequest("user-1", true, "確認推薦關係"));

                assertThat(result.getStatusCode().value()).isEqualTo(200);
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) result.getBody();
                assertThat(body.get("message")).isEqualTo("驗證通過");
            }
        }

        @Test
        @DisplayName("reject → 200 + 已拒絕")
        void rejectSuccess() {
            try (MockedStatic<SecurityUtil> sec = mockStatic(SecurityUtil.class)) {
                sec.when(SecurityUtil::getCurrentUserId).thenReturn("admin-1");
                doNothing().when(referralService).adminVerify(eq("admin-1"), any());

                ResponseEntity<?> result = controller.verify(
                        new AdminVerifyRequest("user-1", false, "UID 不在推薦名單"));

                assertThat(result.getStatusCode().value()).isEqualTo(200);
                @SuppressWarnings("unchecked")
                Map<String, Object> body = (Map<String, Object>) result.getBody();
                assertThat(body.get("message")).isEqualTo("已拒絕");
            }
        }

        @Test
        @DisplayName("用戶不存在 → 400")
        void userNotFound() {
            try (MockedStatic<SecurityUtil> sec = mockStatic(SecurityUtil.class)) {
                sec.when(SecurityUtil::getCurrentUserId).thenReturn("admin-1");
                doThrow(new IllegalArgumentException("找不到用戶 unknown 的推薦記錄"))
                        .when(referralService).adminVerify(eq("admin-1"), any());

                ResponseEntity<?> result = controller.verify(
                        new AdminVerifyRequest("unknown", true, null));

                assertThat(result.getStatusCode().value()).isEqualTo(400);
            }
        }
    }

    @Nested
    @DisplayName("GET /api/admin/referral/pending")
    class Pending {

        @Test
        @DisplayName("有待驗證記錄 → 200 + 列表")
        void pendingList() {
            when(referralService.getPendingList()).thenReturn(List.of(
                    AdminPendingResponse.builder()
                            .userId("user-1")
                            .email("test@example.com")
                            .exchangeUid("uid-123")
                            .submittedAt(LocalDateTime.now())
                            .build()));

            ResponseEntity<List<AdminPendingResponse>> result = controller.getPending();

            assertThat(result.getStatusCode().value()).isEqualTo(200);
            assertThat(result.getBody()).hasSize(1);
            assertThat(result.getBody().get(0).getUserId()).isEqualTo("user-1");
        }
    }
}
