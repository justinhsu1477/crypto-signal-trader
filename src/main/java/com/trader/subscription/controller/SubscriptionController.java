package com.trader.subscription.controller;

import com.trader.subscription.dto.CreateCheckoutRequest;
import com.trader.subscription.dto.SubscriptionStatusResponse;
import com.trader.subscription.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    /**
     * 查詢可用方案
     * GET /api/subscription/plans
     */
    @GetMapping("/plans")
    public ResponseEntity<?> getPlans() {
        // TODO: 回傳方案列表（從設定檔或 DB 讀取）
        return ResponseEntity.ok(Map.of("status", "TODO", "message", "plans 尚未實作"));
    }

    /**
     * 建立 Stripe Checkout Session（導向付款頁）
     * POST /api/subscription/checkout
     * Body: { "planId": "pro" }
     *
     * TODO: 從 SecurityContext 取得 userId
     */
    @PostMapping("/checkout")
    public ResponseEntity<?> createCheckout(@Valid @RequestBody CreateCheckoutRequest request) {
        // TODO: String userId = 從 JWT 取得;
        // String checkoutUrl = subscriptionService.createCheckoutSession(userId, request.getPlanId());
        // return ResponseEntity.ok(Map.of("checkoutUrl", checkoutUrl));
        return ResponseEntity.ok(Map.of("status", "TODO", "message", "checkout 尚未實作"));
    }

    /**
     * 查詢當前訂閱狀態
     * GET /api/subscription/status
     *
     * TODO: 從 SecurityContext 取得 userId
     */
    @GetMapping("/status")
    public ResponseEntity<SubscriptionStatusResponse> getStatus() {
        // TODO: String userId = 從 JWT 取得;
        // return ResponseEntity.ok(subscriptionService.getStatus(userId));
        return ResponseEntity.ok(SubscriptionStatusResponse.builder()
                .status("TODO")
                .active(false)
                .build());
    }

    /**
     * 取消訂閱
     * POST /api/subscription/cancel
     *
     * TODO: 從 SecurityContext 取得 userId
     */
    @PostMapping("/cancel")
    public ResponseEntity<?> cancel() {
        // TODO: String userId = 從 JWT 取得;
        // subscriptionService.cancel(userId);
        return ResponseEntity.ok(Map.of("status", "TODO", "message", "cancel 尚未實作"));
    }

    /**
     * Stripe Webhook 回調（Stripe 伺服器直接呼叫，不需要認證）
     * POST /api/subscription/webhook
     */
    @PostMapping("/webhook")
    public ResponseEntity<?> stripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {
        try {
            subscriptionService.handleStripeWebhook(payload, sigHeader);
            return ResponseEntity.ok(Map.of("received", true));
        } catch (Exception e) {
            log.error("Stripe Webhook 處理失敗: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}
