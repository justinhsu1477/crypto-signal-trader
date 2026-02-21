package com.trader.subscription.service;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.SubscriptionCancelParams;
import com.stripe.param.SubscriptionUpdateParams;
import com.trader.subscription.config.StripeConfig;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stripe 訂閱服務
 *
 * 負責：
 * 1. 查詢可用方案 + Payment Link URL
 * 2. 處理 Stripe Webhook 回調（付款成功/失敗/取消/更新）
 * 3. 查詢用戶訂閱狀態
 * 4. 取消訂閱（立即停止）
 * 5. 升級/降級方案
 *
 * 付款流程使用 Stripe Payment Links（不用 Checkout Session API）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final PaymentHistoryRepository paymentHistoryRepository;
    private final StripeConfig stripeConfig;

    private static final ZoneId ZONE = ZoneId.of("Asia/Taipei");

    // ===================== 查詢方法 =====================

    /**
     * 取得所有可用方案（含 Payment Link URL）
     *
     * @param userId 當前用戶 ID（用來標記 current 方案）
     * @return 方案列表
     */
    public List<PlanResponse> getPlans(String userId) {
        List<Plan> plans = planRepository.findByActiveTrue();
        String currentPlanId = getCurrentPlanId(userId);

        return plans.stream()
                .map(plan -> PlanResponse.builder()
                        .planId(plan.getPlanId())
                        .name(plan.getName())
                        .priceMonthly(plan.getPriceMonthly())
                        .maxPositions(plan.getMaxPositions())
                        .maxSymbols(plan.getMaxSymbols())
                        .dcaLayersAllowed(plan.getDcaLayersAllowed())
                        .maxRiskPercent(plan.getMaxRiskPercent())
                        .paymentLinkUrl(buildPaymentLinkUrl(plan, userId))
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
                .stripeSubscriptionId(sub.getStripeSubscriptionId())
                .stripeCustomerId(sub.getStripeCustomerId())
                .build();
    }

    /**
     * 檢查用戶是否有有效訂閱（ACTIVE 或 TRIALING）
     */
    public boolean isUserActive(String userId) {
        return subscriptionRepository.findActiveByUserId(userId).isPresent();
    }

    /**
     * 取得 Payment Link URL（含 client_reference_id）
     *
     * @param userId 用戶 ID
     * @param planId 方案 ID
     * @return Payment Link URL，前端開新分頁到此 URL 付款
     */
    public String getPaymentLinkUrl(String userId, String planId) {
        Plan plan = planRepository.findByPlanIdAndActiveTrue(planId)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在: " + planId));

        if (plan.getStripePaymentLinkUrl() == null || plan.getStripePaymentLinkUrl().isBlank()) {
            throw new IllegalStateException("方案 " + planId + " 尚未設定 Stripe Payment Link");
        }

        return buildPaymentLinkUrl(plan, userId);
    }

    // ===================== Webhook 處理 =====================

    /**
     * 處理 Stripe Webhook 事件
     *
     * 事件路由：
     * - checkout.session.completed → 建立 Subscription (ACTIVE) + PaymentHistory
     * - invoice.payment_succeeded  → 更新 currentPeriodEnd + PaymentHistory
     * - invoice.payment_failed     → status = PAST_DUE
     * - customer.subscription.deleted → status = CANCELLED
     * - customer.subscription.updated → 更新 planId
     */
    @Transactional
    public void handleStripeWebhook(String payload, String sigHeader) {
        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, stripeConfig.getWebhookSecret());
        } catch (SignatureVerificationException e) {
            log.error("Stripe Webhook 簽名驗證失敗: {}", e.getMessage());
            throw new IllegalArgumentException("Webhook 簽名驗證失敗", e);
        }

        String eventType = event.getType();
        log.info("收到 Stripe Webhook 事件: {} (id={})", eventType, event.getId());

        switch (eventType) {
            case "checkout.session.completed" -> handleCheckoutCompleted(event);
            case "invoice.payment_succeeded" -> handleInvoicePaymentSucceeded(event);
            case "invoice.payment_failed" -> handleInvoicePaymentFailed(event);
            case "customer.subscription.deleted" -> handleSubscriptionDeleted(event);
            case "customer.subscription.updated" -> handleSubscriptionUpdated(event);
            default -> log.debug("忽略未處理的事件類型: {}", eventType);
        }
    }

    // ===================== 取消 / 升級 =====================

    /**
     * 取消訂閱（立即停止）
     *
     * 呼叫 Stripe API 立即取消 + 更新 DB 狀態
     */
    @Transactional
    public void cancel(String userId) {
        Subscription sub = subscriptionRepository.findActiveByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("用戶沒有有效訂閱"));

        // 呼叫 Stripe API 立即取消
        try {
            com.stripe.model.Subscription stripeSub =
                    com.stripe.model.Subscription.retrieve(sub.getStripeSubscriptionId());

            stripeSub.cancel(SubscriptionCancelParams.builder().build());

            log.info("已透過 Stripe API 立即取消訂閱: userId={}, subId={}",
                    userId, sub.getStripeSubscriptionId());
        } catch (StripeException e) {
            log.error("Stripe 取消訂閱失敗: userId={}, error={}", userId, e.getMessage());
            throw new RuntimeException("取消訂閱失敗，請稍後再試", e);
        }

        // 更新 DB 狀態
        sub.setStatus(Subscription.Status.CANCELLED);
        sub.setCurrentPeriodEnd(LocalDateTime.now(ZONE));
        subscriptionRepository.save(sub);

        log.info("訂閱已立即取消: userId={}", userId);
    }

    /**
     * 升級/降級方案
     *
     * 使用 Stripe API 更新 Subscription 的 Price，
     * 升級立即生效（proration），降級下期生效。
     */
    @Transactional
    public void upgrade(String userId, String newPlanId) {
        Subscription sub = subscriptionRepository.findActiveByUserId(userId)
                .orElseThrow(() -> new IllegalStateException("用戶沒有有效訂閱，請先訂閱"));

        Plan newPlan = planRepository.findByPlanIdAndActiveTrue(newPlanId)
                .orElseThrow(() -> new IllegalArgumentException("方案不存在: " + newPlanId));

        if (newPlan.getStripePriceId() == null || newPlan.getStripePriceId().isBlank()) {
            throw new IllegalStateException("方案 " + newPlanId + " 尚未設定 Stripe Price ID");
        }

        if (sub.getPlanId().equals(newPlanId)) {
            throw new IllegalArgumentException("已經是此方案，無需變更");
        }

        try {
            com.stripe.model.Subscription stripeSub =
                    com.stripe.model.Subscription.retrieve(sub.getStripeSubscriptionId());

            // 取得目前 subscription 的第一個 item
            SubscriptionItem currentItem = stripeSub.getItems().getData().get(0);

            SubscriptionUpdateParams params = SubscriptionUpdateParams.builder()
                    .addItem(SubscriptionUpdateParams.Item.builder()
                            .setId(currentItem.getId())
                            .setPrice(newPlan.getStripePriceId())
                            .build())
                    .setProrationBehavior(SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS)
                    .build();

            stripeSub.update(params);

            log.info("已透過 Stripe API 更新方案: userId={}, {} → {}",
                    userId, sub.getPlanId(), newPlanId);
        } catch (StripeException e) {
            log.error("Stripe 升級方案失敗: userId={}, error={}", userId, e.getMessage());
            throw new RuntimeException("升級方案失敗，請稍後再試", e);
        }

        // 更新 DB
        sub.setPlanId(newPlanId);
        subscriptionRepository.save(sub);

        log.info("方案已更新: userId={}, newPlan={}", userId, newPlanId);
    }

    // ===================== Webhook 內部處理 =====================

    /**
     * checkout.session.completed
     * 用戶完成 Payment Link 付款 → 建立訂閱紀錄
     */
    private void handleCheckoutCompleted(Event event) {
        Session session = (Session) event.getDataObjectDeserializer()
                .getObject().orElse(null);
        if (session == null) {
            log.warn("checkout.session.completed: 無法解析 Session 物件");
            return;
        }

        String userId = session.getClientReferenceId();
        String stripeCustomerId = session.getCustomer();
        String stripeSubscriptionId = session.getSubscription();

        if (userId == null || userId.isBlank()) {
            log.warn("checkout.session.completed: client_reference_id 為空，無法對應用戶");
            return;
        }

        // 幂等保護：檢查是否已存在
        if (stripeSubscriptionId != null
                && subscriptionRepository.findByStripeSubscriptionId(stripeSubscriptionId).isPresent()) {
            log.info("checkout.session.completed: 訂閱已存在，跳過 (subId={})", stripeSubscriptionId);
            return;
        }

        // 從 Stripe Subscription 取得 Price ID → 對應 Plan
        String planId = resolvePlanIdFromStripeSubscription(stripeSubscriptionId);

        Subscription sub = Subscription.builder()
                .userId(userId)
                .stripeCustomerId(stripeCustomerId)
                .stripeSubscriptionId(stripeSubscriptionId)
                .planId(planId)
                .status(Subscription.Status.ACTIVE)
                .currentPeriodStart(LocalDateTime.now(ZONE))
                .build();

        // 從 Stripe 取得 period end
        setCurrentPeriodEndFromStripe(sub, stripeSubscriptionId);

        subscriptionRepository.save(sub);

        // 記錄付款歷史
        savePaymentHistory(userId, sub.getId(), session.getPaymentIntent(),
                session.getAmountTotal(), session.getCurrency(), "succeeded");

        log.info("新訂閱已建立: userId={}, plan={}, stripeSubId={}",
                userId, planId, stripeSubscriptionId);
    }

    /**
     * invoice.payment_succeeded
     * 續費成功 → 更新 currentPeriodEnd + 記錄 PaymentHistory
     */
    private void handleInvoicePaymentSucceeded(Event event) {
        Invoice invoice = (Invoice) event.getDataObjectDeserializer()
                .getObject().orElse(null);
        if (invoice == null) return;

        String stripeSubId = invoice.getSubscription();
        if (stripeSubId == null) return;

        subscriptionRepository.findByStripeSubscriptionId(stripeSubId).ifPresent(sub -> {
            sub.setStatus(Subscription.Status.ACTIVE);
            setCurrentPeriodEndFromStripe(sub, stripeSubId);
            subscriptionRepository.save(sub);

            savePaymentHistory(sub.getUserId(), sub.getId(),
                    invoice.getPaymentIntent(),
                    invoice.getAmountPaid(), invoice.getCurrency(), "succeeded");

            log.info("續費成功: userId={}, subId={}", sub.getUserId(), stripeSubId);
        });
    }

    /**
     * invoice.payment_failed
     * 扣款失敗 → 標記 PAST_DUE
     */
    private void handleInvoicePaymentFailed(Event event) {
        Invoice invoice = (Invoice) event.getDataObjectDeserializer()
                .getObject().orElse(null);
        if (invoice == null) return;

        String stripeSubId = invoice.getSubscription();
        if (stripeSubId == null) return;

        subscriptionRepository.findByStripeSubscriptionId(stripeSubId).ifPresent(sub -> {
            sub.setStatus(Subscription.Status.PAST_DUE);
            subscriptionRepository.save(sub);

            savePaymentHistory(sub.getUserId(), sub.getId(),
                    invoice.getPaymentIntent(),
                    invoice.getAmountDue(), invoice.getCurrency(), "failed");

            log.warn("扣款失敗，標記 PAST_DUE: userId={}, subId={}", sub.getUserId(), stripeSubId);
        });
    }

    /**
     * customer.subscription.deleted
     * Stripe 側取消 → 標記 CANCELLED
     */
    private void handleSubscriptionDeleted(Event event) {
        com.stripe.model.Subscription stripeSub =
                (com.stripe.model.Subscription) event.getDataObjectDeserializer()
                        .getObject().orElse(null);
        if (stripeSub == null) return;

        subscriptionRepository.findByStripeSubscriptionId(stripeSub.getId()).ifPresent(sub -> {
            sub.setStatus(Subscription.Status.CANCELLED);
            sub.setCurrentPeriodEnd(LocalDateTime.now(ZONE));
            subscriptionRepository.save(sub);
            log.info("訂閱已由 Stripe 側取消: userId={}, subId={}", sub.getUserId(), stripeSub.getId());
        });
    }

    /**
     * customer.subscription.updated
     * 方案變更（升降級）→ 更新 planId
     */
    private void handleSubscriptionUpdated(Event event) {
        com.stripe.model.Subscription stripeSub =
                (com.stripe.model.Subscription) event.getDataObjectDeserializer()
                        .getObject().orElse(null);
        if (stripeSub == null) return;

        subscriptionRepository.findByStripeSubscriptionId(stripeSub.getId()).ifPresent(sub -> {
            // 取得新的 Price ID → 對應 Plan
            if (!stripeSub.getItems().getData().isEmpty()) {
                String newPriceId = stripeSub.getItems().getData().get(0).getPrice().getId();
                planRepository.findByStripePriceId(newPriceId).ifPresent(plan -> {
                    String oldPlanId = sub.getPlanId();
                    sub.setPlanId(plan.getPlanId());
                    subscriptionRepository.save(sub);
                    log.info("方案已由 Stripe 更新: userId={}, {} → {}",
                            sub.getUserId(), oldPlanId, plan.getPlanId());
                });
            }

            // 更新期間
            setCurrentPeriodEndFromStripe(sub, stripeSub.getId());
            subscriptionRepository.save(sub);
        });
    }

    // ===================== 工具方法 =====================

    /**
     * 組裝 Payment Link URL + client_reference_id
     */
    private String buildPaymentLinkUrl(Plan plan, String userId) {
        if (plan.getStripePaymentLinkUrl() == null || plan.getStripePaymentLinkUrl().isBlank()) {
            return null;
        }
        String separator = plan.getStripePaymentLinkUrl().contains("?") ? "&" : "?";
        return plan.getStripePaymentLinkUrl() + separator + "client_reference_id=" + userId;
    }

    /**
     * 取得用戶目前的 planId
     */
    private String getCurrentPlanId(String userId) {
        return subscriptionRepository.findActiveByUserId(userId)
                .map(Subscription::getPlanId)
                .orElse("free");
    }

    /**
     * 從 Stripe Subscription 解析 Price ID → 對應本地 Plan
     */
    private String resolvePlanIdFromStripeSubscription(String stripeSubscriptionId) {
        if (stripeSubscriptionId == null) return "free";

        try {
            com.stripe.model.Subscription stripeSub =
                    com.stripe.model.Subscription.retrieve(stripeSubscriptionId);
            if (!stripeSub.getItems().getData().isEmpty()) {
                String priceId = stripeSub.getItems().getData().get(0).getPrice().getId();
                return planRepository.findByStripePriceId(priceId)
                        .map(Plan::getPlanId)
                        .orElse("basic"); // fallback
            }
        } catch (StripeException e) {
            log.warn("無法從 Stripe 解析 planId: {}", e.getMessage());
        }
        return "basic";
    }

    /**
     * 從 Stripe API 取得 currentPeriodEnd 並設定到 Subscription
     */
    private void setCurrentPeriodEndFromStripe(Subscription sub, String stripeSubscriptionId) {
        if (stripeSubscriptionId == null) return;

        try {
            com.stripe.model.Subscription stripeSub =
                    com.stripe.model.Subscription.retrieve(stripeSubscriptionId);
            if (stripeSub.getCurrentPeriodEnd() != null) {
                sub.setCurrentPeriodEnd(
                        LocalDateTime.ofInstant(
                                Instant.ofEpochSecond(stripeSub.getCurrentPeriodEnd()),
                                ZONE));
            }
        } catch (StripeException e) {
            log.warn("無法從 Stripe 取得 currentPeriodEnd: {}", e.getMessage());
        }
    }

    /**
     * 儲存付款歷史
     */
    private void savePaymentHistory(String userId, Long subscriptionId,
                                    String paymentIntentId, Long amountCents,
                                    String currency, String status) {
        PaymentHistory payment = PaymentHistory.builder()
                .userId(userId)
                .subscriptionId(subscriptionId)
                .stripePaymentIntentId(paymentIntentId)
                .amount(amountCents != null ? amountCents / 100.0 : null)
                .currency(currency != null ? currency.toUpperCase() : "USD")
                .status(status)
                .paidAt("succeeded".equals(status) ? LocalDateTime.now(ZONE) : null)
                .build();
        paymentHistoryRepository.save(payment);
    }
}
