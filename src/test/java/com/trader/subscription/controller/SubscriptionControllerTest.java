package com.trader.subscription.controller;

import com.trader.subscription.dto.*;
import com.trader.subscription.service.SubscriptionService;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * SubscriptionController 單元測試
 *
 * 測試策略：
 * - mock SubscriptionService，驗證每個端點正確委派
 * - 在 @BeforeEach 設定 SecurityContext，確保 SecurityUtil.getCurrentUserId() 可正常取值
 * - 11 tests across 6 @Nested groups
 */
class SubscriptionControllerTest {

    private static final String USER_ID = "test-user-123";

    private SubscriptionService subscriptionService;
    private SubscriptionController controller;

    @BeforeEach
    void setUp() {
        subscriptionService = mock(SubscriptionService.class);
        controller = new SubscriptionController(subscriptionService);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        USER_ID, null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ==================== GET /plans ====================

    @Nested
    @DisplayName("GET /plans")
    class GetPlans {

        @Test
        @DisplayName("returns plan list from service")
        void returnsPlanList() {
            List<PlanResponse> plans = List.of(
                    PlanResponse.builder().planId("free").name("Free").priceMonthly(BigDecimal.ZERO).current(true).build(),
                    PlanResponse.builder().planId("basic").name("Basic").priceMonthly(BigDecimal.valueOf(19.0)).current(false).build());
            when(subscriptionService.getPlans(USER_ID)).thenReturn(plans);

            ResponseEntity<List<PlanResponse>> response = controller.getPlans();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).hasSize(2);
            assertThat(response.getBody().get(0).getPlanId()).isEqualTo("free");
            verify(subscriptionService).getPlans(USER_ID);
        }

        @Test
        @DisplayName("returns empty list when no plans available")
        void returnsEmptyList() {
            when(subscriptionService.getPlans(USER_ID)).thenReturn(List.of());

            ResponseEntity<List<PlanResponse>> response = controller.getPlans();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isEmpty();
        }
    }

    // ==================== POST /checkout ====================

    @Nested
    @DisplayName("POST /checkout")
    class CreateCheckout {

        @Test
        @DisplayName("returns checkout info for valid plan")
        void returnsCheckoutInfo() {
            CryptoCheckoutResponse checkoutResponse = CryptoCheckoutResponse.builder()
                    .planId("basic")
                    .planName("Basic")
                    .amountUsdt(BigDecimal.valueOf(19.0))
                    .walletAddress("TTestWallet123")
                    .network("TRC20")
                    .build();
            when(subscriptionService.getCheckoutInfo(USER_ID, "basic")).thenReturn(checkoutResponse);

            CreateCheckoutRequest request = new CreateCheckoutRequest();
            request.setPlanId("basic");

            ResponseEntity<CryptoCheckoutResponse> response = controller.createCheckout(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().getWalletAddress()).isEqualTo("TTestWallet123");
            assertThat(response.getBody().getAmountUsdt()).isEqualByComparingTo(BigDecimal.valueOf(19.0));
            verify(subscriptionService).getCheckoutInfo(USER_ID, "basic");
        }

        @Test
        @DisplayName("propagates exception when plan not found")
        void propagatesExceptionForInvalidPlan() {
            when(subscriptionService.getCheckoutInfo(USER_ID, "invalid"))
                    .thenThrow(new IllegalArgumentException("方案不存在: invalid"));

            CreateCheckoutRequest request = new CreateCheckoutRequest();
            request.setPlanId("invalid");

            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> controller.createCheckout(request));
        }
    }

    // ==================== POST /submit-payment ====================

    @Nested
    @DisplayName("POST /submit-payment")
    class SubmitPayment {

        @Test
        @DisplayName("returns success message on valid payment")
        void returnsSuccessMessage() {
            when(subscriptionService.submitPayment(USER_ID, "basic", "txHash123"))
                    .thenReturn("付款驗證成功！Basic 方案已開通至 2025-06-01");

            SubmitPaymentRequest request = new SubmitPaymentRequest();
            request.setPlanId("basic");
            request.setTxHash("txHash123");

            ResponseEntity<MessageResponse> response = controller.submitPayment(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().getStatus()).isEqualTo("success");
            assertThat(response.getBody().getMessage()).contains("付款驗證成功");
            verify(subscriptionService).submitPayment(USER_ID, "basic", "txHash123");
        }

        @Test
        @DisplayName("propagates exception when txHash already used")
        void propagatesExceptionForDuplicateTxHash() {
            when(subscriptionService.submitPayment(USER_ID, "basic", "usedHash"))
                    .thenThrow(new IllegalArgumentException("此交易 Hash 已經使用過"));

            SubmitPaymentRequest request = new SubmitPaymentRequest();
            request.setPlanId("basic");
            request.setTxHash("usedHash");

            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> controller.submitPayment(request));
        }
    }

    // ==================== GET /status ====================

    @Nested
    @DisplayName("GET /status")
    class GetStatus {

        @Test
        @DisplayName("returns subscription status from service")
        void returnsStatus() {
            SubscriptionStatusResponse statusResponse = SubscriptionStatusResponse.builder()
                    .planId("basic")
                    .planName("Basic")
                    .status("ACTIVE")
                    .active(true)
                    .currentPeriodEnd(LocalDateTime.of(2025, 6, 1, 0, 0))
                    .build();
            when(subscriptionService.getStatus(USER_ID)).thenReturn(statusResponse);

            ResponseEntity<SubscriptionStatusResponse> response = controller.getStatus();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().getPlanId()).isEqualTo("basic");
            assertThat(response.getBody().isActive()).isTrue();
            verify(subscriptionService).getStatus(USER_ID);
        }
    }

    // ==================== POST /cancel ====================

    @Nested
    @DisplayName("POST /cancel")
    class Cancel {

        @Test
        @DisplayName("returns success message after cancellation")
        void returnsSuccessMessage() {
            doNothing().when(subscriptionService).cancel(USER_ID);

            ResponseEntity<MessageResponse> response = controller.cancel();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().getStatus()).isEqualTo("success");
            assertThat(response.getBody().getMessage()).isEqualTo("訂閱已立即取消");
            verify(subscriptionService).cancel(USER_ID);
        }

        @Test
        @DisplayName("propagates exception when no active subscription")
        void propagatesExceptionWhenNoSubscription() {
            doThrow(new IllegalStateException("用戶沒有有效訂閱"))
                    .when(subscriptionService).cancel(USER_ID);

            Assertions.assertThrows(IllegalStateException.class,
                    () -> controller.cancel());
        }
    }

    // ==================== POST /upgrade ====================

    @Nested
    @DisplayName("POST /upgrade")
    class Upgrade {

        @Test
        @DisplayName("returns success message with plan id after upgrade")
        void returnsSuccessMessage() {
            doNothing().when(subscriptionService).upgrade(USER_ID, "pro");

            UpgradePlanRequest request = new UpgradePlanRequest();
            request.setPlanId("pro");

            ResponseEntity<MessageResponse> response = controller.upgrade(request);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().getStatus()).isEqualTo("success");
            assertThat(response.getBody().getMessage()).isEqualTo("方案已更新為 pro");
            verify(subscriptionService).upgrade(USER_ID, "pro");
        }

        @Test
        @DisplayName("propagates exception when already on same plan")
        void propagatesExceptionForSamePlan() {
            doThrow(new IllegalArgumentException("已經是此方案，無需變更"))
                    .when(subscriptionService).upgrade(USER_ID, "basic");

            UpgradePlanRequest request = new UpgradePlanRequest();
            request.setPlanId("basic");

            Assertions.assertThrows(IllegalArgumentException.class,
                    () -> controller.upgrade(request));
        }
    }
}
