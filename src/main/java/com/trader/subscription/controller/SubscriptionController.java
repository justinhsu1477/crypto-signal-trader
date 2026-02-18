package com.trader.subscription.controller;

import com.trader.shared.dto.ErrorResponse;
import com.trader.subscription.dto.CreateCheckoutRequest;
import com.trader.subscription.dto.MessageResponse;
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
     *
     * @return {@link MessageResponse}（TODO：改為 PlansResponse）
     */
    @GetMapping("/plans")
    public ResponseEntity<MessageResponse> getPlans() {
        // TODO: 回傳方案列表（從設定檔或 DB 讀取）
        return ResponseEntity.ok(MessageResponse.builder()
                .status("TODO")
                .message("plans 尚未實作")
                .build());
    }

    /**
     * 建立 Stripe Checkout Session（導向付款頁）
     * POST /api/subscription/checkout
     * Body: {@link CreateCheckoutRequest}
     *
     * @return {@link MessageResponse}（TODO：改為含 checkoutUrl 的 DTO）
     */
    @PostMapping("/checkout")
    public ResponseEntity<MessageResponse> createCheckout(
            @Valid @RequestBody CreateCheckoutRequest request) {
        // TODO: String userId = SecurityUtil.getCurrentUserId();
        // String checkoutUrl = subscriptionService.createCheckoutSession(userId, request.getPlanId());
        return ResponseEntity.ok(MessageResponse.builder()
                .status("TODO")
                .message("checkout 尚未實作")
                .build());
    }

    /**
     * 查詢當前訂閱狀態
     * GET /api/subscription/status
     *
     * @return {@link SubscriptionStatusResponse}
     */
    @GetMapping("/status")
    public ResponseEntity<SubscriptionStatusResponse> getStatus() {
        // TODO: String userId = SecurityUtil.getCurrentUserId();
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
     * @return {@link MessageResponse}
     */
    @PostMapping("/cancel")
    public ResponseEntity<MessageResponse> cancel() {
        // TODO: String userId = SecurityUtil.getCurrentUserId();
        // subscriptionService.cancel(userId);
        return ResponseEntity.ok(MessageResponse.builder()
                .status("TODO")
                .message("cancel 尚未實作")
                .build());
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
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.builder()
                            .error("Webhook 處理失敗")
                            .message(e.getMessage())
                            .build());
        }
    }
}
