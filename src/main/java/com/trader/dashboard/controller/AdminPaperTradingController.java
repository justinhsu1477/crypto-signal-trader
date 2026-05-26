package com.trader.dashboard.controller;

import com.trader.papertrade.dto.PromotionRecommendation;
import com.trader.papertrade.dto.SourcePerformanceMetrics;
import com.trader.papertrade.service.PaperPerformanceService;
import com.trader.papertrade.service.PaperPromotionEvaluator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin API — 查詢 paper trading 績效 + 自動升 AUTO 推薦。
 *
 * <p>所有 endpoint 純 read-only。資料源是 {@code trades} 表（simulated=true 過濾）。
 *
 * <p>給 /admin/paper-trading 前端頁面用，也可給 chatbot 詢問「最賺的源是哪個」。
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/paper-trading")
@RequiredArgsConstructor
public class AdminPaperTradingController {

    private final PaperPerformanceService performanceService;
    private final PaperPromotionEvaluator promotionEvaluator;

    /**
     * GET /api/admin/paper-trading/metrics
     *
     * <p>取每個 paper source 的績效指標 — 按 totalPnl 由高到低排。
     */
    @GetMapping("/metrics")
    public ResponseEntity<List<SourcePerformanceMetrics>> getAllMetrics() {
        return ResponseEntity.ok(performanceService.getAllSourceMetrics());
    }

    /**
     * GET /api/admin/paper-trading/metrics/{channelId}
     *
     * <p>查單一 source 的詳細績效。404 if no paper trade for that channel.
     */
    @GetMapping("/metrics/{channelId}")
    public ResponseEntity<SourcePerformanceMetrics> getOneMetrics(@PathVariable String channelId) {
        SourcePerformanceMetrics m = performanceService.getSourceMetrics(channelId);
        if (m == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(m);
    }

    /**
     * GET /api/admin/paper-trading/promotion-evaluation
     *
     * <p>跑一次當前 promotion evaluation（不發 Discord，只回 JSON 給 UI 看決策結果）。
     *
     * <p>跟 daily cron 同邏輯 — admin 想隨時查當下狀態用。
     */
    @GetMapping("/promotion-evaluation")
    public ResponseEntity<List<PromotionRecommendation>> evaluatePromotion() {
        List<SourcePerformanceMetrics> all = performanceService.getAllSourceMetrics();
        List<PromotionRecommendation> recs = all.stream()
                .map(promotionEvaluator::evaluateOne)
                .toList();
        return ResponseEntity.ok(recs);
    }
}
