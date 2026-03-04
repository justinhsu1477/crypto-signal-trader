package com.trader.subscription.controller;

import com.trader.shared.util.SortHelper;
import com.trader.subscription.dto.AdminActivateRequest;
import com.trader.subscription.dto.AdminPaymentHistoryResponse;
import com.trader.subscription.dto.AdminSubscriptionActionResponse;
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
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.trader.shared.config.AppConstants;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理員訂閱管理 API
 *
 * 端點：
 * - GET    /api/admin/subscriptions                   — 所有用戶訂閱狀態 + 付款摘要
 * - GET    /api/admin/subscriptions/{userId}/payments  — 指定用戶付款歷史
 * - POST   /api/admin/subscriptions/{userId}/activate  — 手動開通/延長訂閱
 * - PUT    /api/admin/subscriptions/{userId}/cancel     — 手動取消訂閱
 * - PUT    /api/admin/subscriptions/{userId}/lifetime   — 設定終生免費
 *
 * 安全：路徑 /api/admin/** 已被 AuthConfig hasRole("ADMIN") 保護
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/subscriptions")
@RequiredArgsConstructor
public class AdminSubscriptionController {

    /** 訂閱列表排序欄位定義 */
    private static final Map<String, Function<Boolean, Comparator<UserSubscriptionSummary>>> SUBSCRIPTION_SORT_FIELDS =
            Map.ofEntries(
                    SortHelper.stringField("email", UserSubscriptionSummary::getEmail),
                    SortHelper.stringField("name", UserSubscriptionSummary::getName),
                    SortHelper.stringField("status", UserSubscriptionSummary::getStatus),
                    SortHelper.stringField("planName", UserSubscriptionSummary::getPlanName),
                    SortHelper.comparableField("totalAmountPaid", UserSubscriptionSummary::getTotalAmountPaid),
                    SortHelper.comparableField("currentPeriodEnd", UserSubscriptionSummary::getCurrentPeriodEnd)
            );

    private final UserRepository userRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final PlanRepository planRepository;

    // ==================== 查詢端點 ====================

    /**
     * 列出所有用戶的訂閱狀態 + 付款摘要
     * GET /api/admin/subscriptions
     */
    @GetMapping
    public ResponseEntity<AdminSubscriptionListResponse> listSubscriptions(
            @RequestParam(defaultValue = "email") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDir) {
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

        List<UserSubscriptionSummary> sorted = SortHelper.sort(
                summaries, sortBy, sortDir, SUBSCRIPTION_SORT_FIELDS, "email");

        long activeSubs = summaries.stream()
                .filter(s -> "ACTIVE".equals(s.getStatus())).count();
        long lifetimeSubs = summaries.stream()
                .filter(s -> "LIFETIME".equals(s.getStatus())).count();

        return ResponseEntity.ok(AdminSubscriptionListResponse.builder()
                .subscriptions(sorted)
                .totalUsers(allUsers.size())
                .activeSubscriptions(activeSubs)
                .lifetimeSubscriptions(lifetimeSubs)
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

    // ==================== 管理端點 ====================

    /**
     * 手動開通或延長訂閱
     * POST /api/admin/subscriptions/{userId}/activate
     */
    @PostMapping("/{userId}/activate")
    public ResponseEntity<AdminSubscriptionActionResponse> activateSubscription(
            @PathVariable String userId,
            @Valid @RequestBody AdminActivateRequest request) {

        // 驗證用戶存在
        if (userRepository.findById(userId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        // 驗證方案存在
        Optional<Plan> planOpt = planRepository.findByPlanIdAndActiveTrue(request.getPlanId());
        if (planOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(AdminSubscriptionActionResponse.builder()
                    .userId(userId)
                    .message("方案不存在: " + request.getPlanId())
                    .build());
        }

        int days = request.getDays() != null ? request.getDays() : 30;
        LocalDateTime now = LocalDateTime.now(AppConstants.ZONE_ID);

        // 查找現有有效訂閱
        Optional<Subscription> existingOpt = subscriptionRepository.findActiveByUserId(userId);

        Subscription sub;
        String action;
        if (existingOpt.isPresent()) {
            sub = existingOpt.get();
            // LIFETIME 用戶只更新方案，不動日期
            if (sub.getStatus() == Subscription.Status.LIFETIME) {
                sub.setPlanId(request.getPlanId());
                action = "方案已更新（終生免費用戶）";
            } else {
                // 延長：從現有到期日或今天開始
                LocalDateTime base = sub.getCurrentPeriodEnd() != null
                        && sub.getCurrentPeriodEnd().isAfter(now)
                        ? sub.getCurrentPeriodEnd() : now;
                sub.setPlanId(request.getPlanId());
                sub.setStatus(Subscription.Status.ACTIVE);
                sub.setCurrentPeriodEnd(base.plusDays(days));
                action = String.format("訂閱已延長 %d 天，到期日: %s",
                        days, sub.getCurrentPeriodEnd().toLocalDate());
            }
        } else {
            // 新建訂閱
            sub = Subscription.builder()
                    .userId(userId)
                    .planId(request.getPlanId())
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodStart(now)
                    .currentPeriodEnd(now.plusDays(days))
                    .build();
            action = String.format("訂閱已開通 %d 天，到期日: %s",
                    days, sub.getCurrentPeriodEnd().toLocalDate());
        }

        subscriptionRepository.save(sub);
        log.info("Admin 手動操作訂閱: userId={}, planId={}, action={}",
                userId, request.getPlanId(), action);

        return ResponseEntity.ok(AdminSubscriptionActionResponse.builder()
                .userId(userId)
                .planId(sub.getPlanId())
                .status(sub.getStatus().name())
                .currentPeriodEnd(sub.getCurrentPeriodEnd())
                .message(action)
                .build());
    }

    /**
     * 手動取消訂閱
     * PUT /api/admin/subscriptions/{userId}/cancel
     */
    @PutMapping("/{userId}/cancel")
    public ResponseEntity<AdminSubscriptionActionResponse> cancelSubscription(
            @PathVariable String userId) {

        Optional<Subscription> subOpt = subscriptionRepository.findActiveByUserId(userId);
        if (subOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Subscription sub = subOpt.get();
        LocalDateTime now = LocalDateTime.now(AppConstants.ZONE_ID);

        sub.setStatus(Subscription.Status.CANCELLED);
        sub.setCurrentPeriodEnd(now);
        subscriptionRepository.save(sub);

        log.info("Admin 手動取消訂閱: userId={}, 原方案={}", userId, sub.getPlanId());

        return ResponseEntity.ok(AdminSubscriptionActionResponse.builder()
                .userId(userId)
                .planId(sub.getPlanId())
                .status("CANCELLED")
                .currentPeriodEnd(now)
                .message("訂閱已取消")
                .build());
    }

    /**
     * 設定終生免費
     * PUT /api/admin/subscriptions/{userId}/lifetime
     *
     * 自動給予 Pro 方案權限，currentPeriodEnd = null（永不過期）
     */
    @PutMapping("/{userId}/lifetime")
    public ResponseEntity<AdminSubscriptionActionResponse> setLifetime(
            @PathVariable String userId) {

        // 驗證用戶存在
        if (userRepository.findById(userId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        LocalDateTime now = LocalDateTime.now(AppConstants.ZONE_ID);

        // 查找現有訂閱（包括已取消的）
        Optional<Subscription> existingOpt = subscriptionRepository.findByUserId(userId)
                .stream()
                .findFirst();

        Subscription sub;
        if (existingOpt.isPresent()) {
            sub = existingOpt.get();
            sub.setStatus(Subscription.Status.LIFETIME);
            sub.setPlanId("pro");
            sub.setCurrentPeriodEnd(null);  // 永不過期
        } else {
            sub = Subscription.builder()
                    .userId(userId)
                    .planId("pro")
                    .status(Subscription.Status.LIFETIME)
                    .currentPeriodStart(now)
                    .currentPeriodEnd(null)  // 永不過期
                    .build();
        }

        subscriptionRepository.save(sub);
        log.info("Admin 設定終生免費: userId={}", userId);

        return ResponseEntity.ok(AdminSubscriptionActionResponse.builder()
                .userId(userId)
                .planId("pro")
                .status("LIFETIME")
                .currentPeriodEnd(null)
                .message("已設定為終生免費（Pro 方案）")
                .build());
    }

    // ==================== private helpers ====================

    /**
     * Build map: userId → 最新的 ACTIVE/LIFETIME 訂閱
     */
    private Map<String, Subscription> buildActiveSubscriptionMap() {
        return subscriptionRepository.findAll().stream()
                .filter(s -> s.getStatus() == Subscription.Status.ACTIVE
                        || s.getStatus() == Subscription.Status.LIFETIME)
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
