package com.trader.papertrade.service;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.papertrade.dto.PromotionRecommendation;
import com.trader.papertrade.dto.PromotionRecommendation.Decision;
import com.trader.papertrade.dto.SourcePerformanceMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 自動評估 paper sources 是否達 AUTO 升級門檻 — 每日跑一次、發 Discord 通知。
 *
 * <h3>運作流程</h3>
 * <ol>
 *   <li>每日 09:00 TW (cron `0 0 9 * * *`, zone Asia/Taipei) 由 @Scheduled 觸發</li>
 *   <li>{@link PaperPerformanceService#getAllSourceMetrics()} 取每 source metrics</li>
 *   <li>依配置門檻分類 PROMOTE / MONITOR / REJECT</li>
 *   <li>把 PROMOTE candidates 整理成 Discord embed 通知 admin</li>
 * </ol>
 *
 * <h3>門檻 (paper-trading.promotion.* in application.yml)</h3>
 * <ul>
 *   <li>min-trades: ≥ 30 筆才有統計顯著性</li>
 *   <li>min-win-rate: ≥ 55% 才視為 edge</li>
 *   <li>min-profit-factor: ≥ 1.5（毛利至少 1.5 倍於毛損）</li>
 *   <li>max-drawdown-pct: ≤ 20%（控制回撤風險）</li>
 *   <li>min-period-days: ≥ 30（至少 1 個月才避免 lucky streak）</li>
 * </ul>
 *
 * <h3>失敗策略</h3>
 * 純 read-only。任何步驟失敗（Discord 掛 / DB 慢）一律 swallow log，不影響其他 cron。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaperPromotionEvaluator {

    private final PaperPerformanceService performanceService;
    private final DiscordWebhookService discordWebhookService;

    @Value("${paper-trading.promotion.min-trades:30}")
    private int minTrades;

    @Value("${paper-trading.promotion.min-win-rate:0.55}")
    private double minWinRate;

    @Value("${paper-trading.promotion.min-profit-factor:1.5}")
    private double minProfitFactor;

    @Value("${paper-trading.promotion.max-drawdown-pct:0.20}")
    private double maxDrawdownPct;

    @Value("${paper-trading.promotion.min-period-days:30}")
    private long minPeriodDays;

    @Value("${paper-trading.promotion.enabled:true}")
    private boolean enabled;

    /**
     * 每日 09:00 TW 跑。zone Asia/Taipei 在 application 內已設好（AppConstants.ZONE_ID）。
     * cron 5 段：sec min hour day month weekday
     */
    @Scheduled(cron = "${paper-trading.promotion.cron:0 0 9 * * *}", zone = "Asia/Taipei")
    public void runDailyEvaluation() {
        if (!enabled) {
            log.debug("PaperPromotionEvaluator disabled, skipping");
            return;
        }
        try {
            evaluateAndNotify();
        } catch (Exception e) {
            log.warn("paper promotion evaluation failed (swallowed): {}", e.getMessage());
        }
    }

    /**
     * 跑評估 + 發通知。Visible for testing（不能 trigger cron 但可直接 call）。
     */
    void evaluateAndNotify() {
        List<SourcePerformanceMetrics> allMetrics = performanceService.getAllSourceMetrics();
        if (allMetrics.isEmpty()) {
            log.info("No paper trade metrics yet, skipping promotion eval");
            return;
        }

        List<PromotionRecommendation> recommendations = allMetrics.stream()
                .map(this::evaluateOne)
                .toList();

        List<PromotionRecommendation> promoteCandidates = recommendations.stream()
                .filter(r -> r.getDecision() == Decision.PROMOTE)
                .toList();

        log.info("Paper promotion evaluation: total={} PROMOTE={} MONITOR={} REJECT={}",
                recommendations.size(),
                promoteCandidates.size(),
                recommendations.stream().filter(r -> r.getDecision() == Decision.MONITOR).count(),
                recommendations.stream().filter(r -> r.getDecision() == Decision.REJECT).count());

        // 只通知 PROMOTE candidates（admin 手動 review 後決定升不升）
        if (!promoteCandidates.isEmpty()) {
            sendPromoteNotification(promoteCandidates);
        }
    }

    /**
     * 單一 source 評估邏輯 — 給 controller / cron / test 共用。
     */
    public PromotionRecommendation evaluateOne(SourcePerformanceMetrics m) {
        List<String> reasons = new ArrayList<>();
        boolean allPass = true;
        boolean hardReject = false;

        // Trades count
        if (m.getClosedTrades() < minTrades) {
            reasons.add(String.format("⏳ trades %d < %d (尚未足夠統計顯著)", m.getClosedTrades(), minTrades));
            allPass = false;
        } else {
            reasons.add(String.format("✅ trades %d ≥ %d", m.getClosedTrades(), minTrades));
        }

        // Period days
        if (m.getPeriodDays() < minPeriodDays) {
            reasons.add(String.format("⏳ 觀察期 %d 天 < %d 天 (避免 lucky streak)",
                    m.getPeriodDays(), minPeriodDays));
            allPass = false;
        } else {
            reasons.add(String.format("✅ 觀察期 %d 天 ≥ %d 天", m.getPeriodDays(), minPeriodDays));
        }

        // 負 PnL → hard reject
        if (m.getTotalPnl() < 0) {
            reasons.add(String.format("❌ totalPnl %.2f < 0 (虧損中，明確 REJECT)", m.getTotalPnl()));
            hardReject = true;
            allPass = false;
        }

        // Win rate
        if (m.getWinRate() < minWinRate) {
            reasons.add(String.format("❌ winRate %.1f%% < %.1f%%",
                    m.getWinRate() * 100, minWinRate * 100));
            allPass = false;
            if (m.getWinRate() < 0.35) hardReject = true;
        } else {
            reasons.add(String.format("✅ winRate %.1f%% ≥ %.1f%%",
                    m.getWinRate() * 100, minWinRate * 100));
        }

        // Profit Factor
        if (m.getProfitFactor() < minProfitFactor && m.getProfitFactor() != Double.POSITIVE_INFINITY) {
            reasons.add(String.format("❌ profitFactor %.2f < %.2f",
                    m.getProfitFactor(), minProfitFactor));
            allPass = false;
        } else {
            String pfStr = m.getProfitFactor() == Double.POSITIVE_INFINITY ? "∞" : String.format("%.2f", m.getProfitFactor());
            reasons.add(String.format("✅ profitFactor %s ≥ %.2f", pfStr, minProfitFactor));
        }

        // Max DD
        if (m.getMaxDrawdownPct() > maxDrawdownPct) {
            reasons.add(String.format("❌ maxDD %.1f%% > %.1f%%",
                    m.getMaxDrawdownPct() * 100, maxDrawdownPct * 100));
            allPass = false;
        } else {
            reasons.add(String.format("✅ maxDD %.1f%% ≤ %.1f%%",
                    m.getMaxDrawdownPct() * 100, maxDrawdownPct * 100));
        }

        Decision decision;
        if (hardReject) {
            decision = Decision.REJECT;
        } else if (allPass) {
            decision = Decision.PROMOTE;
        } else {
            decision = Decision.MONITOR;
        }

        return PromotionRecommendation.builder()
                .metrics(m)
                .decision(decision)
                .reasons(reasons)
                .build();
    }

    private void sendPromoteNotification(List<PromotionRecommendation> candidates) {
        StringBuilder body = new StringBuilder();
        body.append(String.format("發現 %d 個 source 達標可考慮升 AUTO：\n\n", candidates.size()));

        for (PromotionRecommendation r : candidates) {
            SourcePerformanceMetrics m = r.getMetrics();
            body.append(String.format("**%s** (id=%s, ch=%s)\n",
                    m.getDisplayName(),
                    m.getSourceId() != null ? m.getSourceId() : "?",
                    m.getChannelId()));
            body.append(String.format("  trades=%d win=%.1f%% PF=%.2f DD=%.1f%% totalPnL=$%.2f period=%dd Sharpe=%.2f\n",
                    m.getClosedTrades(), m.getWinRate() * 100,
                    Double.isFinite(m.getProfitFactor()) ? m.getProfitFactor() : 999.0,
                    m.getMaxDrawdownPct() * 100,
                    m.getTotalPnl(), m.getPeriodDays(), m.getSharpeRatio()));
            body.append("\n");
        }
        body.append("\n→ 進 Admin UI signal_sources 頁，把該 source 的 trade_mode 從 SHADOW 改成 AUTO\n");
        body.append("→ 注意：升 AUTO 後該 source 訊號會在 23 用戶實際下單");

        try {
            discordWebhookService.sendNotification(
                    "🚀 Paper Trading — Promotion Candidates",
                    body.toString(),
                    DiscordWebhookService.COLOR_GREEN);
        } catch (Exception e) {
            log.warn("Promotion Discord notify failed (swallowed): {}", e.getMessage());
        }
    }
}
