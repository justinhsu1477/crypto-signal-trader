package com.trader.dashboard.controller;

import com.trader.dashboard.dto.DashboardOverview;
import com.trader.dashboard.dto.PerformanceStats;
import com.trader.dashboard.dto.TradeHistoryResponse;
import com.trader.dashboard.service.DashboardService;
import com.trader.shared.util.SecurityUtil;
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
     */
    @PutMapping("/trade-settings")
    public ResponseEntity<?> updateTradeSettings(
            @RequestBody UpdateTradeSettingsRequest request) {
        String userId = SecurityUtil.getCurrentUserId();
        try {
            UserTradeSettings updated = tradeSettingsService.updateSettings(userId, request);
            return ResponseEntity.ok(tradeSettingsService.toResponse(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 查詢方案預設值（用於前端顯示限制）
     * GET /api/dashboard/trade-settings/defaults
     */
    @GetMapping("/trade-settings/defaults")
    public ResponseEntity<TradeSettingsDefaultsResponse> getTradeSettingsDefaults() {
        // MVP: 回傳 free 方案的預設值
        // TODO: 未來從 subscription + plans 表查詢用戶實際方案
        return ResponseEntity.ok(TradeSettingsDefaultsResponse.builder()
                .planId("free")
                .maxRiskPercent(0.10)
                .maxPositions(1)
                .maxSymbols(3)
                .dcaLayersAllowed(0)
                .build());
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

        webhookService.disableWebhook(webhookId);

        log.info("用戶 {} 停用 webhook: {}", userId, webhookId);

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

        webhookService.deleteWebhook(webhookId);

        log.info("用戶 {} 刪除 webhook: {}", userId, webhookId);

        return ResponseEntity.ok(Map.of(
                "webhookId", webhookId,
                "message", "Webhook 已刪除"));
    }
}
