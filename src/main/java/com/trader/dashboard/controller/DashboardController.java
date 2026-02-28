package com.trader.dashboard.controller;

import com.trader.dashboard.dto.DashboardOverview;
import com.trader.dashboard.dto.PerformanceStats;
import com.trader.dashboard.dto.TradeHistoryResponse;
import com.trader.dashboard.service.DashboardService;
import com.trader.notification.service.NotificationService;
import com.trader.shared.util.SecurityUtil;
import com.trader.subscription.entity.Plan;
import com.trader.subscription.repository.PlanRepository;
import com.trader.subscription.service.SubscriptionService;
import com.trader.user.dto.TradeSettingsDefaultsResponse;
import com.trader.user.dto.TradeSettingsResponse;
import com.trader.user.dto.UpdateTradeSettingsRequest;
import com.trader.user.entity.UserTradeSettings;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserDiscordWebhookService;
import com.trader.user.service.UserTradeSettingsService;
import com.trader.user.entity.UserDiscordWebhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dashboard API
 *
 * 提供前端儀表板需要的所有數據：
 * - /overview — 首頁總覽（帳戶、風控、訂閱、持倉）
 * - /performance — 績效統計（勝率、PF、訊號排名、盈虧曲線）
 * - /trades — 交易歷史（分頁）
 */
@Slf4j
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboardService;
    private final UserRepository userRepository;
    private final UserDiscordWebhookService webhookService;
    private final UserTradeSettingsService tradeSettingsService;
    private final NotificationService discordWebhookService;
    private final SubscriptionService subscriptionService;
    private final PlanRepository planRepository;

    /**
     * 首頁總覽
     * GET /api/dashboard/overview
     *
     * 包含：帳戶餘額、持倉、今日盈虧、風控預算、訂閱狀態
     */
    @GetMapping("/overview")
    public ResponseEntity<DashboardOverview> getOverview() {
        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(dashboardService.getOverview(userId));
    }

    /**
     * 績效統計
     * GET /api/dashboard/performance?days=30
     *
     * 包含：摘要指標、出場原因分布、訊號來源排名、盈虧曲線
     */
    @GetMapping("/performance")
    public ResponseEntity<PerformanceStats> getPerformance(
            @RequestParam(defaultValue = "30") int days) {
        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(dashboardService.getPerformance(userId, days));
    }

    /**
     * 交易歷史（分頁）
     * GET /api/dashboard/trades?page=0&size=20
     */
    @GetMapping("/trades")
    public ResponseEntity<TradeHistoryResponse> getTradeHistory(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        String userId = SecurityUtil.getCurrentUserId();
        return ResponseEntity.ok(dashboardService.getTradeHistory(userId, page, size));
    }

    /**
     * 查詢自動跟單狀態
     * GET /api/dashboard/auto-trade-status
     *
     * 回傳：{ "autoTradeEnabled": true/false }
     */
    @GetMapping("/auto-trade-status")
    public ResponseEntity<Map<String, Object>> getAutoTradeStatus() {
        String userId = SecurityUtil.getCurrentUserId();
        var user = userRepository.findById(userId);
        if (user.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "autoTradeEnabled", user.get().isAutoTradeEnabled()));
    }

    /**
     * 更新自動跟單開關
     * POST /api/dashboard/auto-trade-status
     * Body: { "enabled": true/false }
     *
     * 回傳：{ "autoTradeEnabled": true/false, "message": "已更新" }
     */
    @PostMapping("/auto-trade-status")
    public ResponseEntity<Map<String, Object>> updateAutoTradeStatus(
            @RequestBody Map<String, Boolean> body) {
        String userId = SecurityUtil.getCurrentUserId();
        Boolean enabled = body.get("enabled");

        if (enabled == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "enabled 欄位不可為空"));
        }

        var user = userRepository.findById(userId);
        if (user.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        var userEntity = user.get();
        userEntity.setAutoTradeEnabled(enabled);
        userRepository.save(userEntity);

        log.info("用戶 {} 自動跟單設定已更新: {}", userId, enabled);

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "autoTradeEnabled", enabled,
                "message", enabled ? "已啟用自動跟單" : "已關閉自動跟單"));
    }

    // ==================== Discord 通知開關 ====================

    /**
     * 查詢 Discord 通知開關狀態
     * GET /api/dashboard/discord-notification-status
     *
     * 回傳：{ "userId": "...", "discordNotificationEnabled": true/false }
     */
    @GetMapping("/discord-notification-status")
    public ResponseEntity<Map<String, Object>> getDiscordNotificationStatus() {
        String userId = SecurityUtil.getCurrentUserId();
        var user = userRepository.findById(userId);
        if (user.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "discordNotificationEnabled", user.get().isDiscordNotificationEnabled()));
    }

    /**
     * 更新 Discord 通知開關
     * POST /api/dashboard/discord-notification-status
     * Body: { "enabled": true/false }
     *
     * 回傳：{ "discordNotificationEnabled": true/false, "message": "已更新" }
     */
    @PostMapping("/discord-notification-status")
    public ResponseEntity<Map<String, Object>> updateDiscordNotificationStatus(
            @RequestBody Map<String, Boolean> body) {
        String userId = SecurityUtil.getCurrentUserId();
        Boolean enabled = body.get("enabled");

        if (enabled == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "enabled 欄位不可為空"));
        }

        var user = userRepository.findById(userId);
        if (user.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        var userEntity = user.get();
        userEntity.setDiscordNotificationEnabled(enabled);
        userRepository.save(userEntity);

        // 清除通知快取，讓設定即時生效
        discordWebhookService.evictUserCache(userId);

        log.info("用戶 {} Discord 通知設定已更新: {}", userId, enabled);

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "discordNotificationEnabled", enabled,
                "message", enabled ? "已啟用 Discord 通知" : "已關閉 Discord 通知"));
    }

    // ==================== 交易參數管理 ====================

    /**
     * 查詢用戶交易參數
     * GET /api/dashboard/trade-settings
     */
    @GetMapping("/trade-settings")
    public ResponseEntity<TradeSettingsResponse> getTradeSettings() {
        String userId = SecurityUtil.getCurrentUserId();
        UserTradeSettings settings = tradeSettingsService.getOrCreateSettings(userId);
        return ResponseEntity.ok(tradeSettingsService.toResponse(settings));
    }

    /**
     * 更新用戶交易參數（部分更新）
     * PUT /api/dashboard/trade-settings
     *
     * 先檢查方案限制，再呼叫 service 做值域驗證 + 儲存。
     */
    @PutMapping("/trade-settings")
    public ResponseEntity<?> updateTradeSettings(
            @RequestBody UpdateTradeSettingsRequest request) {
        String userId = SecurityUtil.getCurrentUserId();
        try {
            // 檢查訂閱方案限制
            String planId = subscriptionService.getCurrentPlanId(userId);
            Plan plan = planRepository.findById(planId).orElse(null);
            if (plan != null) {
                validatePlanLimits(request, plan);
            }

            UserTradeSettings updated = tradeSettingsService.updateSettings(userId, request);
            return ResponseEntity.ok(tradeSettingsService.toResponse(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 查詢用戶方案限制值（前端用於顯示參數上限）
     * GET /api/dashboard/trade-settings/defaults
     */
    @GetMapping("/trade-settings/defaults")
    public ResponseEntity<TradeSettingsDefaultsResponse> getTradeSettingsDefaults() {
        String userId = SecurityUtil.getCurrentUserId();
        String planId = subscriptionService.getCurrentPlanId(userId);
        Plan plan = planRepository.findById(planId).orElse(null);

        return ResponseEntity.ok(TradeSettingsDefaultsResponse.builder()
                .planId(planId)
                .maxRiskPercent(plan != null && plan.getMaxRiskPercent() != null
                        ? plan.getMaxRiskPercent() : 0.10)
                .maxPositions(plan != null && plan.getMaxPositions() != null
                        ? plan.getMaxPositions() : 1)
                .maxSymbols(plan != null && plan.getMaxSymbols() != null
                        ? plan.getMaxSymbols() : 3)
                .dcaLayersAllowed(plan != null && plan.getDcaLayersAllowed() != null
                        ? plan.getDcaLayersAllowed() : 0)
                .build());
    }

    // ==================== 方案限制驗證 ====================

    /**
     * 驗證修改請求是否超出用戶方案限制
     */
    private void validatePlanLimits(UpdateTradeSettingsRequest req, Plan plan) {
        if (req.getRiskPercent() != null && plan.getMaxRiskPercent() != null
                && req.getRiskPercent() > plan.getMaxRiskPercent()) {
            throw new IllegalArgumentException(
                    String.format("您的 %s 方案風險上限為 %.0f%%，無法設定 %.0f%%",
                            plan.getName(),
                            plan.getMaxRiskPercent() * 100,
                            req.getRiskPercent() * 100));
        }
        if (req.getMaxDcaLayers() != null && plan.getDcaLayersAllowed() != null
                && req.getMaxDcaLayers() > plan.getDcaLayersAllowed()) {
            throw new IllegalArgumentException(
                    String.format("您的 %s 方案 DCA 層數上限為 %d",
                            plan.getName(),
                            plan.getDcaLayersAllowed()));
        }
        if (req.getAllowedSymbols() != null && plan.getMaxSymbols() != null
                && req.getAllowedSymbols().size() > plan.getMaxSymbols()) {
            throw new IllegalArgumentException(
                    String.format("您的 %s 方案交易對上限為 %d 個",
                            plan.getName(),
                            plan.getMaxSymbols()));
        }
    }

    // ==================== Discord Webhook 管理 ====================

    /**
     * 查詢用戶所有 webhook
     * GET /api/dashboard/discord-webhooks
     */
    @GetMapping("/discord-webhooks")
    public ResponseEntity<Map<String, Object>> getWebhooks() {
        String userId = SecurityUtil.getCurrentUserId();
        List<UserDiscordWebhook> webhooks = webhookService.getAllWebhooks(userId);
        Optional<UserDiscordWebhook> primary = webhookService.getPrimaryWebhook(userId);

        Map<String, Object> response = new java.util.HashMap<>();
        response.put("userId", userId);
        response.put("webhooks", webhooks);
        response.put("primaryWebhookId", primary.map(UserDiscordWebhook::getWebhookId).orElse(null));

        return ResponseEntity.ok(response);
    }

    /**
     * 新增或更新 webhook
     * POST /api/dashboard/discord-webhooks
     * Body: { "webhookUrl": "https://discord.com/api/webhooks/...", "name": "我的交易通知" }
     *
     * 回傳新建立的 webhook
     */
    @PostMapping("/discord-webhooks")
    public ResponseEntity<Map<String, Object>> createWebhook(
            @RequestBody Map<String, String> body) {
        String userId = SecurityUtil.getCurrentUserId();
        String webhookUrl = body.get("webhookUrl");
        String name = body.get("name");

        if (webhookUrl == null || webhookUrl.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "webhookUrl 不可為空"));
        }

        // 驗證 URL 格式
        if (!webhookUrl.startsWith("https://discord.com/api/webhooks/")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "無效的 Discord Webhook URL"));
        }

        UserDiscordWebhook webhook = webhookService.createOrUpdateWebhook(userId, webhookUrl, name);
        discordWebhookService.evictUserCache(userId);

        log.info("用戶 {} 建立/更新 webhook: {}", userId, webhook.getWebhookId());

        return ResponseEntity.ok(Map.of(
                "webhookId", webhook.getWebhookId(),
                "userId", userId,
                "name", webhook.getName(),
                "enabled", webhook.isEnabled(),
                "message", "Webhook 已設定成功"));
    }

    /**
     * 停用 webhook
     * POST /api/dashboard/discord-webhooks/{webhookId}/disable
     */
    @PostMapping("/discord-webhooks/{webhookId}/disable")
    public ResponseEntity<Map<String, Object>> disableWebhook(
            @PathVariable String webhookId) {
        String userId = SecurityUtil.getCurrentUserId();

        try {
            webhookService.disableWebhook(userId, webhookId);
            discordWebhookService.evictUserCache(userId);
        } catch (IllegalArgumentException e) {
            log.warn("用戶 {} 停用 webhook 失敗: {}", userId, e.getMessage());
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Webhook 不存在或無權操作"));
        }

        return ResponseEntity.ok(Map.of(
                "webhookId", webhookId,
                "message", "Webhook 已停用"));
    }

    /**
     * 刪除 webhook
     * DELETE /api/dashboard/discord-webhooks/{webhookId}
     */
    @DeleteMapping("/discord-webhooks/{webhookId}")
    public ResponseEntity<Map<String, Object>> deleteWebhook(
            @PathVariable String webhookId) {
        String userId = SecurityUtil.getCurrentUserId();

        try {
            webhookService.deleteWebhook(userId, webhookId);
            discordWebhookService.evictUserCache(userId);
        } catch (IllegalArgumentException e) {
            log.warn("用戶 {} 刪除 webhook 失敗: {}", userId, e.getMessage());
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Webhook 不存在或無權操作"));
        }

        return ResponseEntity.ok(Map.of(
                "webhookId", webhookId,
                "message", "Webhook 已刪除"));
    }
}
