package com.trader.subscription.controller;

import com.trader.subscription.dto.AdminPaymentHistoryResponse;
import com.trader.subscription.dto.AdminSubscriptionListResponse;
import com.trader.subscription.entity.PaymentHistory;
import com.trader.subscription.entity.Plan;
import com.trader.subscription.entity.Subscription;
import com.trader.subscription.repository.PaymentHistoryRepository;
import com.trader.subscription.repository.PlanRepository;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AdminSubscriptionController 單元測試
 */
class AdminSubscriptionControllerTest {

    private UserRepository userRepository;
    private SubscriptionRepository subscriptionRepository;
    private PaymentHistoryRepository paymentHistoryRepository;
    private PlanRepository planRepository;
    private AdminSubscriptionController controller;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        subscriptionRepository = mock(SubscriptionRepository.class);
        paymentHistoryRepository = mock(PaymentHistoryRepository.class);
        planRepository = mock(PlanRepository.class);
        controller = new AdminSubscriptionController(
                userRepository, subscriptionRepository, paymentHistoryRepository, planRepository);

        // 預設 Plan 資料
        Plan freePlan = Plan.builder().planId("free").name("Free").build();
        Plan basicPlan = Plan.builder().planId("basic").name("Basic").build();
        Plan proPlan = Plan.builder().planId("pro").name("Pro").build();
        when(planRepository.findAll()).thenReturn(List.of(freePlan, basicPlan, proPlan));
    }

    // ==================== listSubscriptions ====================

    @Nested
    @DisplayName("GET /api/admin/subscriptions")
    class ListSubscriptions {

        @Test
        @DisplayName("包含 ACTIVE / TRIALING / NONE 用戶")
        void listAllUsersWithMixedStatus() {
            // 3 個用戶
            User activeUser = User.builder().userId("u1").email("a@e.com").name("Alice").enabled(true).build();
            User trialingUser = User.builder().userId("u2").email("b@e.com").name("Bob").enabled(true).build();
            User noneUser = User.builder().userId("u3").email("c@e.com").name("Charlie").enabled(true).build();
            when(userRepository.findAll()).thenReturn(List.of(activeUser, trialingUser, noneUser));

            // 訂閱
            Subscription activeSub = Subscription.builder()
                    .id(1L).userId("u1").planId("basic").status(Subscription.Status.ACTIVE)
                    .currentPeriodEnd(LocalDateTime.of(2026, 3, 28, 0, 0))
                    .createdAt(LocalDateTime.of(2026, 2, 28, 0, 0))
                    .build();
            Subscription trialingSub = Subscription.builder()
                    .id(2L).userId("u2").planId("free").status(Subscription.Status.TRIALING)
                    .createdAt(LocalDateTime.of(2026, 2, 20, 0, 0))
                    .build();
            when(subscriptionRepository.findAll()).thenReturn(List.of(activeSub, trialingSub));

            // 付款紀錄
            PaymentHistory payment = PaymentHistory.builder()
                    .id(1L).userId("u1").amount(BigDecimal.valueOf(99)).status("succeeded").build();
            when(paymentHistoryRepository.findAll()).thenReturn(List.of(payment));

            ResponseEntity<AdminSubscriptionListResponse> response = controller.listSubscriptions();

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            AdminSubscriptionListResponse body = response.getBody();
            assertThat(body.getTotalUsers()).isEqualTo(3);
            assertThat(body.getActiveSubscriptions()).isEqualTo(1);
            assertThat(body.getTrialingSubscriptions()).isEqualTo(1);
            assertThat(body.getSubscriptions()).hasSize(3);

            // 驗證 ACTIVE 用戶
            var activeSummary = body.getSubscriptions().stream()
                    .filter(s -> "u1".equals(s.getUserId())).findFirst().orElseThrow();
            assertThat(activeSummary.getStatus()).isEqualTo("ACTIVE");
            assertThat(activeSummary.getPlanId()).isEqualTo("basic");
            assertThat(activeSummary.getPlanName()).isEqualTo("Basic");
            assertThat(activeSummary.getTotalPayments()).isEqualTo(1);
            assertThat(activeSummary.getTotalAmountPaid()).isEqualByComparingTo(BigDecimal.valueOf(99));

            // 驗證 NONE 用戶
            var noneSummary = body.getSubscriptions().stream()
                    .filter(s -> "u3".equals(s.getUserId())).findFirst().orElseThrow();
            assertThat(noneSummary.getStatus()).isEqualTo("NONE");
            assertThat(noneSummary.getPlanId()).isNull();
            assertThat(noneSummary.getTotalPayments()).isEqualTo(0);
        }

        @Test
        @DisplayName("無用戶 → 空列表")
        void emptyUsers() {
            when(userRepository.findAll()).thenReturn(List.of());
            when(subscriptionRepository.findAll()).thenReturn(List.of());
            when(paymentHistoryRepository.findAll()).thenReturn(List.of());

            ResponseEntity<AdminSubscriptionListResponse> response = controller.listSubscriptions();

            assertThat(response.getBody().getTotalUsers()).isEqualTo(0);
            assertThat(response.getBody().getSubscriptions()).isEmpty();
        }

        @Test
        @DisplayName("N+1 防護 — findAll 只呼叫一次")
        void batchLoadVerification() {
            when(userRepository.findAll()).thenReturn(List.of(
                    User.builder().userId("u1").email("a@e.com").build(),
                    User.builder().userId("u2").email("b@e.com").build()));
            when(subscriptionRepository.findAll()).thenReturn(List.of());
            when(paymentHistoryRepository.findAll()).thenReturn(List.of());

            controller.listSubscriptions();

            verify(userRepository, times(1)).findAll();
            verify(subscriptionRepository, times(1)).findAll();
            verify(paymentHistoryRepository, times(1)).findAll();
            verify(planRepository, times(1)).findAll();
        }
    }

    // ==================== getUserPayments ====================

    @Nested
    @DisplayName("GET /api/admin/subscriptions/{userId}/payments")
    class GetUserPayments {

        @Test
        @DisplayName("有付款紀錄 → 回傳明細 + 彙總")
        void withPayments() {
            User user = User.builder().userId("u1").email("a@e.com").name("Alice").build();
            when(userRepository.findById("u1")).thenReturn(Optional.of(user));

            Subscription sub = Subscription.builder().id(1L).userId("u1").planId("basic").build();
            when(subscriptionRepository.findByUserId("u1")).thenReturn(List.of(sub));

            PaymentHistory p1 = PaymentHistory.builder()
                    .id(1L).userId("u1").subscriptionId(1L)
                    .txHash("T123").network("TRC20").amount(BigDecimal.valueOf(99))
                    .currency("USDT").status("succeeded")
                    .paidAt(LocalDateTime.of(2026, 2, 1, 12, 0))
                    .createdAt(LocalDateTime.of(2026, 2, 1, 12, 0))
                    .build();
            PaymentHistory p2 = PaymentHistory.builder()
                    .id(2L).userId("u1").subscriptionId(1L)
                    .txHash("T456").network("TRC20").amount(BigDecimal.valueOf(99))
                    .currency("USDT").status("succeeded")
                    .paidAt(LocalDateTime.of(2026, 1, 1, 12, 0))
                    .createdAt(LocalDateTime.of(2026, 1, 1, 12, 0))
                    .build();
            when(paymentHistoryRepository.findByUserIdOrderByCreatedAtDesc("u1"))
                    .thenReturn(List.of(p1, p2));

            ResponseEntity<AdminPaymentHistoryResponse> response = controller.getUserPayments("u1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            AdminPaymentHistoryResponse body = response.getBody();
            assertThat(body.getUserId()).isEqualTo("u1");
            assertThat(body.getEmail()).isEqualTo("a@e.com");
            assertThat(body.getPayments()).hasSize(2);
            assertThat(body.getTotalPayments()).isEqualTo(2);
            assertThat(body.getTotalAmountPaid()).isEqualByComparingTo(BigDecimal.valueOf(198));

            // 驗證 planId 從 subscription 反查
            assertThat(body.getPayments().get(0).getPlanId()).isEqualTo("basic");
            assertThat(body.getPayments().get(0).getTxHash()).isEqualTo("T123");
        }

        @Test
        @DisplayName("無付款紀錄 → 空列表")
        void noPayments() {
            User user = User.builder().userId("u1").email("a@e.com").name("Alice").build();
            when(userRepository.findById("u1")).thenReturn(Optional.of(user));
            when(subscriptionRepository.findByUserId("u1")).thenReturn(List.of());
            when(paymentHistoryRepository.findByUserIdOrderByCreatedAtDesc("u1"))
                    .thenReturn(List.of());

            ResponseEntity<AdminPaymentHistoryResponse> response = controller.getUserPayments("u1");

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody().getPayments()).isEmpty();
            assertThat(response.getBody().getTotalPayments()).isEqualTo(0);
            assertThat(response.getBody().getTotalAmountPaid()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("userId 不存在 → 404")
        void userNotFound() {
            when(userRepository.findById("nonexist")).thenReturn(Optional.empty());

            ResponseEntity<AdminPaymentHistoryResponse> response = controller.getUserPayments("nonexist");

            assertThat(response.getStatusCode().value()).isEqualTo(404);
        }

        @Test
        @DisplayName("含 failed 付款 → 彙總只算 succeeded")
        void mixedPaymentStatuses() {
            User user = User.builder().userId("u1").email("a@e.com").name("Alice").build();
            when(userRepository.findById("u1")).thenReturn(Optional.of(user));
            when(subscriptionRepository.findByUserId("u1")).thenReturn(List.of());

            PaymentHistory succeeded = PaymentHistory.builder()
                    .id(1L).userId("u1").amount(BigDecimal.valueOf(99)).status("succeeded").build();
            PaymentHistory failed = PaymentHistory.builder()
                    .id(2L).userId("u1").amount(BigDecimal.valueOf(99)).status("failed").build();
            when(paymentHistoryRepository.findByUserIdOrderByCreatedAtDesc("u1"))
                    .thenReturn(List.of(succeeded, failed));

            ResponseEntity<AdminPaymentHistoryResponse> response = controller.getUserPayments("u1");

            assertThat(response.getBody().getPayments()).hasSize(2); // 回傳全部紀錄
            assertThat(response.getBody().getTotalPayments()).isEqualTo(1); // 只算 succeeded
            assertThat(response.getBody().getTotalAmountPaid()).isEqualByComparingTo(BigDecimal.valueOf(99));
        }
    }
}
