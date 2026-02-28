package com.trader.subscription.controller;

import com.trader.subscription.dto.AdminPaymentHistoryResponse;
import com.trader.subscription.dto.AdminSubscriptionListResponse;
import com.trader.subscription.dto.AdminSubscriptionListResponse.UserSubscriptionSummary;
import com.trader.subscription.entity.PaymentHistory;
import com.trader.subscription.entity.Plan;
import com.trader.subscription.entity.Subscription;
import com.trader.subscription.repository.PaymentHistoryRepository;
import com.trader.subscription.repository.PlanRepository;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理員訂閱管理 API
 *
 * 端點：
 * - GET /api/admin/subscriptions             — 所有用戶訂閱狀態 + 付款摘要
 * - GET /api/admin/subscriptions/{userId}/payments — 指定用戶付款歷史
 *
 * 安全：路徑 /api/admin/** 已被 AuthConfig hasRole("ADMIN") 保護
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/subscriptions")
@RequiredArgsConstructor
public class AdminSubscriptionController {

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final PlanRepository planRepository;

    /**
     * 列出所有用戶的訂閱狀態 + 付款摘要
     * GET /api/admin/subscriptions
     */
    @GetMapping
    public ResponseEntity<AdminSubscriptionListResponse> listSubscriptions() {
        List<User> allUsers = userRepository.findAll();

        // Batch load: 避免 N+1（一次撈全部，記憶體 join）
        Map<String, Subscription> activeSubByUserId = buildActiveSubscriptionMap();
        Map<String, Plan> planMap = buildPlanMap();
        Map<String, PaymentAggregation> paymentAggByUserId = buildPaymentAggregationMap();

        List<UserSubscriptionSummary> summaries = allUsers.stream()
                .map(user -> {
                    Subscription sub = activeSubByUserId.get(user.getUserId());
                    PaymentAggregation agg = paymentAggByUserId
                            .getOrDefault(user.getUserId(), PaymentAggregation.EMPTY);

                    String planName = null;
                    if (sub != null && sub.getPlanId() != null) {
                        Plan plan = planMap.get(sub.getPlanId());
                        planName = plan != null ? plan.getName() : sub.getPlanId();
                    }

                    return UserSubscriptionSummary.builder()
                            .userId(user.getUserId())
                            .email(user.getEmail())
                            .name(user.getName())
                            .enabled(user.isEnabled())
                            .planId(sub != null ? sub.getPlanId() : null)
                            .planName(planName)
                            .status(sub != null ? sub.getStatus().name() : "NONE")
                            .currentPeriodStart(sub != null ? sub.getCurrentPeriodStart() : null)
                            .currentPeriodEnd(sub != null ? sub.getCurrentPeriodEnd() : null)
                            .subscriptionCreatedAt(sub != null ? sub.getCreatedAt() : null)
                            .totalPayments(agg.count())
                            .totalAmountPaid(agg.total())
                            .build();
                })
                .toList();

        long activeSubs = summaries.stream()
                .filter(s -> "ACTIVE".equals(s.getStatus())).count();
        long trialingSubs = summaries.stream()
                .filter(s -> "TRIALING".equals(s.getStatus())).count();

        return ResponseEntity.ok(AdminSubscriptionListResponse.builder()
                .subscriptions(summaries)
                .totalUsers(allUsers.size())
                .activeSubscriptions(activeSubs)
                .trialingSubscriptions(trialingSubs)
                .build());
    }

    /**
     * 查看用戶付款歷史
     * GET /api/admin/subscriptions/{userId}/payments
     */
    @GetMapping("/{userId}/payments")
    public ResponseEntity<AdminPaymentHistoryResponse> getUserPayments(
            @PathVariable String userId) {

        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        User user = userOpt.get();

        List<PaymentHistory> payments = paymentHistoryRepository
                .findByUserIdOrderByCreatedAtDesc(userId);

        // 用 subscriptionId 反查 planId
        Map<Long, Subscription> subMap = subscriptionRepository.findByUserId(userId)
                .stream()
                .collect(Collectors.toMap(Subscription::getId, Function.identity()));

        List<AdminPaymentHistoryResponse.PaymentRecord> records = payments.stream()
                .map(ph -> {
                    String planId = null;
                    if (ph.getSubscriptionId() != null) {
                        Subscription sub = subMap.get(ph.getSubscriptionId());
                        if (sub != null) planId = sub.getPlanId();
                    }
                    return AdminPaymentHistoryResponse.PaymentRecord.builder()
                            .id(ph.getId())
                            .txHash(ph.getTxHash())
                            .network(ph.getNetwork())
                            .walletAddress(ph.getWalletAddress())
                            .amount(ph.getAmount())
                            .currency(ph.getCurrency())
                            .status(ph.getStatus())
                            .paidAt(ph.getPaidAt())
                            .createdAt(ph.getCreatedAt())
                            .subscriptionId(ph.getSubscriptionId())
                            .planId(planId)
                            .build();
                })
                .toList();

        // 彙總：只算 succeeded 的付款
        long succeededCount = payments.stream()
                .filter(p -> "succeeded".equals(p.getStatus())).count();
        BigDecimal totalPaid = payments.stream()
                .filter(p -> "succeeded".equals(p.getStatus()) && p.getAmount() != null)
                .map(PaymentHistory::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return ResponseEntity.ok(AdminPaymentHistoryResponse.builder()
                .userId(userId)
                .email(user.getEmail())
                .name(user.getName())
                .payments(records)
                .totalPayments((int) succeededCount)
                .totalAmountPaid(totalPaid)
                .build());
    }

    // ==================== private helpers ====================

    /**
     * Build map: userId → 最新的 ACTIVE/TRIALING 訂閱
     */
    private Map<String, Subscription> buildActiveSubscriptionMap() {
        return subscriptionRepository.findAll().stream()
                .filter(s -> s.getStatus() == Subscription.Status.ACTIVE
                        || s.getStatus() == Subscription.Status.TRIALING)
                .collect(Collectors.toMap(
                        Subscription::getUserId,
                        Function.identity(),
                        (a, b) -> a.getCreatedAt() != null && b.getCreatedAt() != null
                                && a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b
                ));
    }

    private Map<String, Plan> buildPlanMap() {
        return planRepository.findAll().stream()
                .collect(Collectors.toMap(Plan::getPlanId, Function.identity()));
    }

    /**
     * 彙總每位用戶的 succeeded 付款次數 + 總金額
     */
    private Map<String, PaymentAggregation> buildPaymentAggregationMap() {
        return paymentHistoryRepository.findAll().stream()
                .filter(p -> "succeeded".equals(p.getStatus()))
                .collect(Collectors.groupingBy(
                        PaymentHistory::getUserId,
                        Collectors.collectingAndThen(
                                Collectors.toList(),
                                list -> new PaymentAggregation(
                                        list.size(),
                                        list.stream()
                                                .filter(p -> p.getAmount() != null)
                                                .map(PaymentHistory::getAmount)
                                                .reduce(BigDecimal.ZERO, BigDecimal::add)
                                )
                        )
                ));
    }

    private record PaymentAggregation(int count, BigDecimal total) {
        static final PaymentAggregation EMPTY = new PaymentAggregation(0, BigDecimal.ZERO);
    }
}
