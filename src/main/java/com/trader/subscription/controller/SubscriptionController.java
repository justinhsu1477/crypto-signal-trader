package com.trader.subscription.controller;

import com.trader.shared.dto.ErrorResponse;
import com.trader.shared.util.SecurityUtil;
import com.trader.subscription.dto.*;
import com.trader.subscription.service.SubscriptionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 訂閱管理 API
 *
 * 路徑：/api/subscription
 *
 * 端點：
 * - GET  /plans    → 查詢可用方案（含 Payment Link URL）
 * - POST /checkout → 取得 Payment Link URL（前端開新分頁付款）
 * - GET  /status   → 查詢當前訂閱狀態
 * - POST /cancel   → 立即取消訂閱
 * - POST /upgrade  → 升級/降級方案
 * - POST /webhook  → Stripe Webhook 回調（公開端點，Stripe 伺服器直接呼叫）
 */
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
     * @return 所有啟用的方案列表，含 Payment Link URL 和 current 標記
     */
    @GetMapping("/plans")
    public ResponseEntity<List<PlanResponse>> getPlans() {
        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(subscriptionService.getPlans(userId));
    }

    /**
     * 取得 Payment Link URL（前端開新分頁到此 URL 付款）
     * POST /api/subscription/checkout
     * Body: {@link CreateCheckoutRequest}
     *
     * @return 含 checkoutUrl 的 Map
     */
    @PostMapping("/checkout")
    public ResponseEntity<Map<String, String>> createCheckout(
            @Valid @RequestBody CreateCheckoutRequest request) {
        String userId = SecurityUtil.getCurrentUserId();
        String checkoutUrl = subscriptionService.getPaymentLinkUrl(userId, request.getPlanId());
        return ResponseEntity.ok(Map.of("checkoutUrl", checkoutUrl));
    }

    /**
     * 查詢當前訂閱狀態
     * GET /api/subscription/status
     *
     * @return {@link SubscriptionStatusResponse}
     */
    @GetMapping("/status")
    public ResponseEntity<SubscriptionStatusResponse> getStatus() {
        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(subscriptionService.getStatus(userId));
    }

    /**
     * 立即取消訂閱
     * POST /api/subscription/cancel
     *
     * @return 成功訊息
     */
    @PostMapping("/cancel")
    public ResponseEntity<MessageResponse> cancel() {
        String userId = SecurityUtil.getCurrentUserId();
        subscriptionService.cancel(userId);
        return ResponseEntity.ok(MessageResponse.builder()
                .status("success")
                .message("訂閱已立即取消")
                .build());
    }

    /**
     * 升級/降級方案
     * POST /api/subscription/upgrade
     * Body: {@link UpgradePlanRequest}
     *
     * @return 成功訊息
     */
    @PostMapping("/upgrade")
    public ResponseEntity<MessageResponse> upgrade(
            @Valid @RequestBody UpgradePlanRequest request) {
        String userId = SecurityUtil.getCurrentUserId();
        subscriptionService.upgrade(userId, request.getPlanId());
        return ResponseEntity.ok(MessageResponse.builder()
                .status("success")
                .message("方案已更新為 " + request.getPlanId())
                .build());
    }

    /**
     * Stripe Webhook 回調（公開端點，Stripe 伺服器直接呼叫）
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
