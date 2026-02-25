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
 * 訂閱管理 API — USDT TRC20 付款
 *
 * 路徑：/api/subscription
 *
 * 端點：
 * - GET  /plans           → 查詢可用方案
 * - POST /checkout        → 取得付款資訊（錢包地址 + USDT 金額）
 * - POST /submit-payment  → 提交 txHash 驗證付款
 * - GET  /status          → 查詢當前訂閱狀態
 * - POST /cancel          → 立即取消訂閱
 * - POST /upgrade         → 升級方案
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
     */
    @GetMapping("/plans")
    public ResponseEntity<List<PlanResponse>> getPlans() {
        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(subscriptionService.getPlans(userId));
    }

    /**
     * 取得付款資訊（錢包地址 + USDT 金額）
     * POST /api/subscription/checkout
     * Body: { planId: "basic" }
     *
     * @return 錢包地址、金額、網路
     */
    @PostMapping("/checkout")
    public ResponseEntity<CryptoCheckoutResponse> createCheckout(
            @Valid @RequestBody CreateCheckoutRequest request) {
        String userId = SecurityUtil.getCurrentUserId();
        CryptoCheckoutResponse info = subscriptionService.getCheckoutInfo(userId, request.getPlanId());
        return ResponseEntity.ok(info);
    }

    /**
     * 提交 txHash 驗證付款
     * POST /api/subscription/submit-payment
     * Body: { planId: "basic", txHash: "abc123..." }
     */
    @PostMapping("/submit-payment")
    public ResponseEntity<MessageResponse> submitPayment(
            @Valid @RequestBody SubmitPaymentRequest request) {
        String userId = SecurityUtil.getCurrentUserId();
        String message = subscriptionService.submitPayment(
                userId, request.getPlanId(), request.getTxHash());
        return ResponseEntity.ok(MessageResponse.builder()
                .status("success")
                .message(message)
                .build());
    }

    /**
     * 查詢當前訂閱狀態
     * GET /api/subscription/status
     */
    @GetMapping("/status")
    public ResponseEntity<SubscriptionStatusResponse> getStatus() {
        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(subscriptionService.getStatus(userId));
    }

    /**
     * 立即取消訂閱
     * POST /api/subscription/cancel
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
     * 升級方案
     * POST /api/subscription/upgrade
     * Body: { planId: "pro" }
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
}
