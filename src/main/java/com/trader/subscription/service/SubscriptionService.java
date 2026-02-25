package com.trader.subscription.service;

import com.trader.subscription.config.CryptoPaymentConfig;
import com.trader.subscription.dto.CryptoCheckoutResponse;
import com.trader.subscription.dto.PlanResponse;
import com.trader.subscription.dto.SubscriptionStatusResponse;
import com.trader.subscription.entity.PaymentHistory;
import com.trader.subscription.entity.Plan;
import com.trader.subscription.entity.Subscription;
import com.trader.subscription.repository.PaymentHistoryRepository;
import com.trader.subscription.repository.PlanRepository;
import com.trader.subscription.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * 訂閱服務 — USDT TRC20 加密貨幣付款
 *
 * 負責：
 * 1. 查詢可用方案
 * 2. 提供收款錢包地址 + USDT 金額
 * 3. 驗證用戶提交的 txHash（透過 TronService）
 * 4. 查詢/取消訂閱
 * 5. 升級方案
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final CryptoPaymentConfig cryptoConfig;
    private final TronService tronService;

    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    // ===================== 查詢方法 =====================

    /**
     * 取得所有可用方案
     */
    public List<PlanResponse> getPlans(String userId) {
        List<Plan> plans = planRepository.findByActiveTrue();
        String currentPlanId = getCurrentPlanId(userId);

        return plans.stream()
                .map(plan -> PlanResponse.builder()
                        .planId(plan.getPlanId())
                        .name(plan.getName())
                        .priceMonthly(plan.getPriceMonthly())
                        .priceUsdt(plan.getPriceUsdt())
                        .maxPositions(plan.getMaxPositions())
                        .maxSymbols(plan.getMaxSymbols())
                        .dcaLayersAllowed(plan.getDcaLayersAllowed())
                        .maxRiskPercent(plan.getMaxRiskPercent())
                        .current(plan.getPlanId().equals(currentPlanId))
                        .build())
                .toList();
    }

    /**
     * 查詢用戶訂閱狀態
     */
    public SubscriptionStatusResponse getStatus(String userId) {
        Optional<Subscription> subOpt = subscriptionRepository.findActiveByUserId(userId);
        if (subOpt.isEmpty()) {
            return SubscriptionStatusResponse.builder()
                    .status("NONE")
                    .active(false)
                    .build();
        }

        Subscription sub = subOpt.get();
        String planName = planRepository.findById(sub.getPlanId())
                .map(Plan::getName)
                .orElse(sub.getPlanId());

        return SubscriptionStatusResponse.builder()
                .planId(sub.getPlanId())
                .planName(planName)
                .status(sub.getStatus().name())
                .currentPeriodEnd(sub.getCurrentPeriodEnd())
                .active(true)
                .build();
    }

    /**
     * 檢查用戶是否有有效訂閱（ACTIVE 或 TRIALING）
     */
    public boolean isUserActive(String userId) {
        return subscriptionRepository.findActiveByUserId(userId).isPresent();
    }

    // ===================== USDT 付款流程 =====================

    /**
     * 取得付款資訊（錢包地址 + USDT 金額）
     *
     * 前端顯示此資訊讓用戶轉帳
     */
    public CryptoCheckoutResponse getCheckoutInfo(String userId, String planId) {
        Plan plan = planRepository.findByPlanIdAndActiveTrue(planId)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在: " + planId));

        if (plan.getPriceUsdt() == null || plan.getPriceUsdt() <= 0) {
            throw new IllegalStateException("方案 " + planId + " 無需付款");
        }

        return CryptoCheckoutResponse.builder()
                .planId(plan.getPlanId())
                .planName(plan.getName())
                .amountUsdt(plan.getPriceUsdt())
                .walletAddress(cryptoConfig.getWalletAddress())
                .network(cryptoConfig.getNetwork())
                .build();
    }

    /**
     * 用戶提交 txHash 進行付款驗證
     *
     * 流程：
     * 1. 檢查 txHash 是否已使用（防重複）
     * 2. 呼叫 TronService 驗證鏈上交易
     * 3. 驗證通過 → 建立/延長訂閱 + 記錄付款歷史
     */
    @Transactional
    public String submitPayment(String userId, String planId, String txHash) {
        // 1. 驗證方案
        Plan plan = planRepository.findByPlanIdAndActiveTrue(planId)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在: " + planId));

        if (plan.getPriceUsdt() == null || plan.getPriceUsdt() <= 0) {
            throw new IllegalStateException("此方案為免費方案，無需付款");
        }

        // 2. 檢查 txHash 是否已使用
        if (paymentHistoryRepository.findByTxHash(txHash).isPresent()) {
            throw new IllegalArgumentException("此交易 Hash 已經使用過");
        }

        // 3. TronGrid 鏈上驗證
        BigDecimal expectedAmount = BigDecimal.valueOf(plan.getPriceUsdt());
        TronService.VerificationResult result = tronService.verifyTransaction(txHash, expectedAmount);

        if (!result.success()) {
            // 記錄失敗的付款嘗試
            savePaymentHistory(userId, null, txHash, plan.getPriceUsdt(), "USDT", "failed");
            throw new IllegalStateException(result.message());
        }

        // 4. 驗證通過 → 建立/延長訂閱
        LocalDateTime now = LocalDateTime.now(ZONE);
        int days = cryptoConfig.getSubscriptionDays();

        Subscription sub = subscriptionRepository.findActiveByUserId(userId)
                .orElse(null);

        if (sub != null) {
            // 已有訂閱 → 延長或升級
            LocalDateTime currentEnd = sub.getCurrentPeriodEnd();
            LocalDateTime newEnd;

            if (currentEnd != null && currentEnd.isAfter(now)) {
                // 尚未到期 → 從到期日延長
                newEnd = currentEnd.plusDays(days);
            } else {
                // 已過期 → 從今天開始
                newEnd = now.plusDays(days);
            }

            sub.setPlanId(planId);
            sub.setStatus(Subscription.Status.ACTIVE);
            sub.setCurrentPeriodEnd(newEnd);
            subscriptionRepository.save(sub);

            log.info("訂閱已延長: userId={}, plan={}, newEnd={}", userId, planId, newEnd);
        } else {
            // 新訂閱
            sub = Subscription.builder()
                    .userId(userId)
                    .planId(planId)
                    .status(Subscription.Status.ACTIVE)
                    .currentPeriodStart(now)
                    .currentPeriodEnd(now.plusDays(days))
                    .build();
            subscriptionRepository.save(sub);

            log.info("新訂閱已建立: userId={}, plan={}, end={}", userId, planId, sub.getCurrentPeriodEnd());
        }

        // 5. 記錄成功的付款歷史
        savePaymentHistory(userId, sub.getId(), txHash,
                result.amount().doubleValue(), "USDT", "succeeded");

        return String.format("付款驗證成功！%s 方案已開通至 %s",
                plan.getName(), sub.getCurrentPeriodEnd().toLocalDate());
    }

    // ===================== 取消 / 升級 =====================

    /**
     * 取消訂閱（立即停止）
     */
    @Transactional
    public void cancel(String userId) {
        Subscription sub = subscriptionRepository.findActiveByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("用戶沒有有效訂閱"));

        sub.setStatus(Subscription.Status.CANCELLED);
        sub.setCurrentPeriodEnd(LocalDateTime.now(ZONE));
        subscriptionRepository.save(sub);

        log.info("訂閱已取消: userId={}", userId);
    }

    /**
     * 升級方案
     *
     * 直接更新方案等級，剩餘天數保留。
     * 如需付差價，後續版本再實作。
     */
    @Transactional
    public void upgrade(String userId, String newPlanId) {
        Subscription sub = subscriptionRepository.findActiveByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("用戶沒有有效訂閱，請先訂閱"));

        Plan newPlan = planRepository.findByPlanIdAndActiveTrue(newPlanId)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在: " + newPlanId));

        if (sub.getPlanId().equals(newPlanId)) {
            throw new IllegalArgumentException("已經是此方案，無需變更");
        }

        String oldPlanId = sub.getPlanId();
        sub.setPlanId(newPlanId);
        subscriptionRepository.save(sub);

        log.info("方案已升級: userId={}, {} → {}", userId, oldPlanId, newPlanId);
    }

    // ===================== 排程用方法 =====================

    /**
     * 檢查並標記到期訂閱
     */
    @Transactional
    public List<Subscription> expireOverdueSubscriptions() {
        LocalDateTime now = LocalDateTime.now(ZONE);
        List<Subscription> activeSubs = subscriptionRepository.findAll().stream()
                .filter(s -> s.getStatus() == Subscription.Status.ACTIVE)
                .filter(s -> s.getCurrentPeriodEnd() != null && s.getCurrentPeriodEnd().isBefore(now))
                .toList();

        for (Subscription sub : activeSubs) {
            sub.setStatus(Subscription.Status.CANCELLED);
            subscriptionRepository.save(sub);
            log.info("訂閱已到期: userId={}, plan={}, endDate={}",
                    sub.getUserId(), sub.getPlanId(), sub.getCurrentPeriodEnd());
        }

        return activeSubs;
    }

    /**
     * 查詢即將到期的訂閱（N 天內）
     */
    public List<Subscription> findExpiringSubscriptions(int withinDays) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        LocalDateTime deadline = now.plusDays(withinDays);

        return subscriptionRepository.findAll().stream()
                .filter(s -> s.getStatus() == Subscription.Status.ACTIVE)
                .filter(s -> s.getCurrentPeriodEnd() != null)
                .filter(s -> s.getCurrentPeriodEnd().isAfter(now)
                        && s.getCurrentPeriodEnd().isBefore(deadline))
                .toList();
    }

    // ===================== 工具方法 =====================

    private String getCurrentPlanId(String userId) {
        return subscriptionRepository.findActiveByUserId(userId)
                .map(Subscription::getPlanId)
                .orElse("free");
    }

    private void savePaymentHistory(String userId, Long subscriptionId,
                                    String txHash, Double amount,
                                    String currency, String status) {
        PaymentHistory payment = PaymentHistory.builder()
                .userId(userId)
                .subscriptionId(subscriptionId)
                .txHash(txHash)
                .network(cryptoConfig.getNetwork())
                .walletAddress(cryptoConfig.getWalletAddress())
                .amount(amount)
                .currency(currency)
                .status(status)
                .paidAt("succeeded".equals(status) ? LocalDateTime.now(ZONE) : null)
                .build();
        paymentHistoryRepository.save(payment);
    }
}
