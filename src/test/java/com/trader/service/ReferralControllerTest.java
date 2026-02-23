package com.trader.service;

import com.trader.referral.controller.ReferralController;
import com.trader.referral.dto.ReferralStatusResponse;
import com.trader.referral.dto.SubmitUidRequest;
import com.trader.referral.entity.ReferralStatus;
import com.trader.referral.service.ReferralService;
import com.trader.shared.util.SecurityUtil;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ReferralController 單元測試
 */
class ReferralControllerTest {

    private ReferralService referralService;
    private ReferralController controller;

    @BeforeEach
    void setUp() {
        referralService = mock(ReferralService.class);
        controller = new ReferralController(referralService);
    }

    @Nested
    @DisplayName("GET /api/referral/status")
    class GetStatus {

        @Test
        @DisplayName("已登入 → 回傳 200 + 狀態")
        void statusSuccess() {
            try (MockedStatic<SecurityUtil> sec = mockStatic(SecurityUtil.class)) {
                sec.when(SecurityUtil::getCurrentUserId).thenReturn("user-1");
                when(referralService.getStatus("user-1")).thenReturn(
                        ReferralStatusResponse.builder()
                                .status(ReferralStatus.NOT_STARTED)
                                .referralLink("https://binance.com/ref")
                                .referralCode("CODE")
                                .build());

                ResponseEntity<ReferralStatusResponse> result = controller.getStatus();

                assertThat(result.getStatusCode().value()).isEqualTo(200);
                assertThat(result.getBody().getStatus()).isEqualTo(ReferralStatus.NOT_STARTED);
            }
        }
    }

    @Nested
    @DisplayName("POST /api/referral/submit-uid")
    class SubmitUid {

        @Test
        @DisplayName("提交成功 → 200")
        void submitSuccess() {
            try (MockedStatic<SecurityUtil> sec = mockStatic(SecurityUtil.class)) {
                sec.when(SecurityUtil::getCurrentUserId).thenReturn("user-1");
                when(referralService.submitUid("user-1", "99887766"))
                        .thenReturn(ReferralStatusResponse.builder()
                                .status(ReferralStatus.PENDING)
                                .exchangeUid("99887766")
                                .build());

                ResponseEntity<?> result = controller.submitUid(new SubmitUidRequest("99887766"));

                assertThat(result.getStatusCode().value()).isEqualTo(200);
            }
        }

        @Test
        @DisplayName("UID 已被佔用 → 409")
        void uidTaken() {
            try (MockedStatic<SecurityUtil> sec = mockStatic(SecurityUtil.class)) {
                sec.when(SecurityUtil::getCurrentUserId).thenReturn("user-1");
                when(referralService.submitUid("user-1", "taken-uid"))
                        .thenThrow(new IllegalArgumentException("此交易所 UID 已被其他帳號綁定"));

                ResponseEntity<?> result = controller.submitUid(new SubmitUidRequest("taken-uid"));

                assertThat(result.getStatusCode().value()).isEqualTo(409);
            }
        }

        @Test
        @DisplayName("已驗證不可重複提交 → 400")
        void alreadyVerified() {
            try (MockedStatic<SecurityUtil> sec = mockStatic(SecurityUtil.class)) {
                sec.when(SecurityUtil::getCurrentUserId).thenReturn("user-1");
                when(referralService.submitUid("user-1", "uid-123"))
                        .thenThrow(new IllegalStateException("已通過驗證，不可重複提交"));

                ResponseEntity<?> result = controller.submitUid(new SubmitUidRequest("uid-123"));

                assertThat(result.getStatusCode().value()).isEqualTo(400);
            }
        }
    }

    @Nested
    @DisplayName("GET /api/referral/program")
    class GetProgram {

        @Test
        @DisplayName("查詢推薦計畫 → 200")
        void programSuccess() {
            try (MockedStatic<SecurityUtil> sec = mockStatic(SecurityUtil.class)) {
                sec.when(SecurityUtil::getCurrentUserId).thenReturn("user-1");
                when(referralService.getStatus("user-1")).thenReturn(
                        ReferralStatusResponse.builder()
                                .status(ReferralStatus.NOT_STARTED)
                                .referralLink("https://binance.com/ref")
                                .referralCode("CODE")
                                .build());

                ResponseEntity<ReferralStatusResponse> result = controller.getProgram();

                assertThat(result.getStatusCode().value()).isEqualTo(200);
                assertThat(result.getBody().getReferralLink()).isEqualTo("https://binance.com/ref");
            }
        }
    }
}
