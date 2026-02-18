package com.trader.subscription.service;

import com.trader.subscription.dto.SubscriptionStatusResponse;
import com.trader.subscription.entity.Subscription;
import com.trader.subscription.repository.SubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Stripe 訂閱服務
 *
 * 負責：
 * 1. 建立 Stripe Checkout Session（導向 Stripe 付款頁）
 * 2. 處理 Stripe Webhook 回調（付款成功/失敗/取消）
 * 3. 查詢用戶訂閱狀態
 *
 * TODO: 整合 Stripe Java SDK
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final SubscriptionRepository subscriptionRepository;

    /**
     * 建立 Stripe Checkout Session
     *
     * @param userId 用戶 ID
     * @param planId 方案 ID ("basic", "pro")
     * @return Stripe Checkout URL，前端導向此 URL 讓用戶付款
     */
    public String createCheckoutSession(String userId, String planId) {
        // TODO: 使用 Stripe SDK 建立 Checkout Session
        //   Stripe.apiKey = stripeConfig.getSecretKey();
        //   Session session = Session.create(params);
        //   return session.getUrl();
        throw new UnsupportedOperationException("createCheckoutSession 尚未實作");
    }

    /**
     * 處理 Stripe Webhook 事件
     *
     * 主要處理的事件：
     * - checkout.session.completed → 建立訂閱紀錄
     * - invoice.payment_succeeded → 續費成功
     * - invoice.payment_failed → 標記 PAST_DUE
     * - customer.subscription.deleted → 標記 CANCELLED
     *
     * @param payload  Webhook 原始 body
     * @param sigHeader Stripe-Signature header
     */
    @Transactional
    public void handleStripeWebhook(String payload, String sigHeader) {
        // TODO: 驗證 Webhook 簽名 + 處理事件
        throw new UnsupportedOperationException("handleStripeWebhook 尚未實作");
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
        return SubscriptionStatusResponse.builder()
                .planId(sub.getPlanId())
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

    /**
     * 取消訂閱（期滿後生效）
     */
    @Transactional
    public void cancel(String userId) {
        // TODO: 呼叫 Stripe API 取消 + 更新 DB 狀態
        throw new UnsupportedOperationException("cancel 尚未實作");
    }
}
