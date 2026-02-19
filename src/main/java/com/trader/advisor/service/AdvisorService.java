package com.trader.advisor.service;

import com.trader.advisor.config.AdvisorConfig;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.shared.config.AppConstants;
import com.trader.shared.config.RiskConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.TradeRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI 交易顧問服務
 *
 * 定期收集帳戶狀態，透過 Gemini AI 分析風險並推送建議。
 * 純唯讀操作，不會影響任何交易流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdvisorService {

    /** AI 顧問專用紫色 — 與其他通知完全區分 */
    public static final int COLOR_PURPLE = 0x9B59B6;

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    private final GeminiService geminiService;
    private final BinanceFuturesService binanceFuturesService;
    private final TradeRecordService tradeRecordService;
    private final DiscordWebhookService webhookService;
    private final AdvisorConfig advisorConfig;
    private final RiskConfig riskConfig;

    // ─── System Prompt ───────────────────────────────────────────

    private static final String SYSTEM_PROMPT = """
            你是一位專業的加密貨幣合約交易顧問 AI。你會定期收到交易帳戶的即時狀態報告。

            你的職責：
            1. 分析當前持倉風險（未實現虧損是否過大、DCA 層數是否過多）
            2. 評估風控預算使用情況（是否接近每日虧損熔斷線）
            3. 從近期交易識別模式（連續止損、勝率下滑）
            4. 提供風險管理提醒

            回覆規則：
            - 使用繁體中文
            - 精簡扼要，不超過 200 字
            - 用 bullet points（•）條列重點
            - 風險警告放最前面，用 ⚠️ 標記
            - 不給具體交易建議（不給入場價、出場價、方向）
            - 不預測市場走勢
            - 如果帳戶狀態正常、無需關注，只回覆：「✅ 帳戶狀態正常，持倉風險可控，無需特別關注。」
            """;

    // ─── 主流程 ─────────────────────────────────────────────────

    /**
     * 執行 AI 顧問分析
     * 收集交易 context → 呼叫 Gemini → 發送 Discord 通知
     */
    public void runAdvisory() {
        log.info("開始收集交易數據...");

        // 1. 收集交易 context
        String context = buildTradingContext();
        log.debug("交易 context 已組裝，長度: {} 字元", context.length());

        // 2. 呼叫 Gemini
        Optional<String> aiResponse = geminiService.generateContent(SYSTEM_PROMPT, context);
        if (aiResponse.isEmpty()) {
            log.warn("AI Advisor: Gemini 回應為空，跳過本次通知");
            return;
        }

        // 3. 發送 Discord
        sendAdvisoryNotification(aiResponse.get());
        log.info("AI Advisor 分析完成並已發送通知");
    }

    // ─── Context 收集（每段獨立 try-catch）──────────────────────

    private String buildTradingContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 交易帳戶狀態報告\n");
        sb.append("時間: ").append(LocalDateTime.now(AppConstants.ZONE_ID).format(DT_FMT)).append("\n\n");

        appendBalance(sb);
        appendOpenPositions(sb);
        appendTodayStats(sb);
        appendRiskBudget(sb);
        appendRecentTrades(sb);
        appendOverallStats(sb);

        return sb.toString();
    }

    /** 第 1 段：帳戶餘額 */
    private void appendBalance(StringBuilder sb) {
        try {
            double balance = binanceFuturesService.getAvailableBalance();
            sb.append("## 帳戶餘額\n");
            sb.append(String.format("可用餘額: %.2f USDT\n\n", balance));
        } catch (Exception e) {
            sb.append("## 帳戶餘額\n查詢失敗\n\n");
            log.warn("Advisor 取餘額失敗: {}", e.getMessage());
        }
    }

    /** 第 2 段：當前持倉 + 未實現盈虧 */
    private void appendOpenPositions(StringBuilder sb) {
        try {
            List<Trade> openTrades = tradeRecordService.findAllOpenTrades();
            sb.append("## 當前持倉\n");

            if (openTrades.isEmpty()) {
                sb.append("無持倉\n\n");
                return;
            }

            for (Trade t : openTrades) {
                sb.append(String.format("- %s %s | 入場: %.2f | 數量: %.4f | SL: %.2f",
                        t.getSymbol(), t.getSide(),
                        t.getEntryPrice(), t.getEntryQuantity(),
                        t.getStopLoss() != null ? t.getStopLoss() : 0.0));

                // 計算未實現盈虧
                try {
                    double markPrice = binanceFuturesService.getMarkPrice(t.getSymbol());
                    int direction = "LONG".equals(t.getSide()) ? 1 : -1;
                    double effectiveQty = t.getRemainingQuantity() != null
                            ? t.getRemainingQuantity() : t.getEntryQuantity();
                    double unrealizedPnl = (markPrice - t.getEntryPrice()) * effectiveQty * direction;
                    sb.append(String.format(" | 市價: %.2f | 未實現: %.2f USDT", markPrice, unrealizedPnl));
                } catch (Exception e) {
                    sb.append(" | 市價: 查詢失敗");
                }

                if (t.getDcaCount() != null && t.getDcaCount() > 0) {
                    sb.append(String.format(" | DCA: %d次", t.getDcaCount()));
                }
                sb.append("\n");
            }
            sb.append("\n");
        } catch (Exception e) {
            sb.append("## 當前持倉\n查詢失敗\n\n");
            log.warn("Advisor 取持倉失敗: {}", e.getMessage());
        }
    }

    /** 第 3 段：今日交易統計 */
    private void appendTodayStats(StringBuilder sb) {
        try {
            Map<String, Object> todayStats = tradeRecordService.getTodayStats();
            sb.append("## 今日交易\n");
            sb.append(String.format("交易筆數: %s | 勝: %s | 負: %s\n\n",
                    todayStats.get("trades"), todayStats.get("wins"), todayStats.get("losses")));
        } catch (Exception e) {
            sb.append("## 今日交易\n查詢失敗\n\n");
            log.warn("Advisor 取今日統計失敗: {}", e.getMessage());
        }
    }

    /** 第 4 段：風控預算使用率 */
    private void appendRiskBudget(StringBuilder sb) {
        try {
            double todayLoss = tradeRecordService.getTodayRealizedLoss();
            double maxDaily = riskConfig.getMaxDailyLossUsdt();
            double usedPct = maxDaily > 0 ? Math.abs(todayLoss) / maxDaily * 100 : 0;

            sb.append("## 風控預算\n");
            sb.append(String.format("每日虧損限額: %.0f USDT\n", maxDaily));
            sb.append(String.format("已使用: %.2f USDT (%.0f%%)\n", Math.abs(todayLoss), usedPct));
            sb.append(String.format("風險比例: %.0f%% | 槓桿: %dx | 最大DCA: %d\n\n",
                    riskConfig.getRiskPercent() * 100,
                    riskConfig.getFixedLeverage(),
                    riskConfig.getMaxDcaPerSymbol()));
        } catch (Exception e) {
            sb.append("## 風控預算\n查詢失敗\n\n");
            log.warn("Advisor 取風控資料失敗: {}", e.getMessage());
        }
    }

    /** 第 5 段：近期已平倉交易 */
    private void appendRecentTrades(StringBuilder sb) {
        try {
            LocalDateTime sevenDaysAgo = LocalDate.now(AppConstants.ZONE_ID)
                    .minusDays(7).atStartOfDay();
            LocalDateTime now = LocalDateTime.now(AppConstants.ZONE_ID);
            List<Trade> recentTrades = tradeRecordService.getClosedTradesForRange(sevenDaysAgo, now);
            int limit = Math.min(recentTrades.size(), advisorConfig.getRecentTradesCount());

            sb.append("## 近期已平倉交易（7天內）\n");

            if (recentTrades.isEmpty()) {
                sb.append("無已平倉交易\n\n");
                return;
            }

            for (int i = 0; i < limit; i++) {
                Trade t = recentTrades.get(i);
                sb.append(String.format("- %s %s | 入場: %.2f | 出場: %.2f | 淨利: %.2f USDT | %s\n",
                        t.getSymbol(),
                        t.getSide(),
                        t.getEntryPrice() != null ? t.getEntryPrice() : 0,
                        t.getExitPrice() != null ? t.getExitPrice() : 0,
                        t.getNetProfit() != null ? t.getNetProfit() : 0,
                        t.getExitReason() != null ? t.getExitReason() : "N/A"));
            }

            if (recentTrades.size() > limit) {
                sb.append(String.format("...還有 %d 筆\n", recentTrades.size() - limit));
            }
            sb.append("\n");
        } catch (Exception e) {
            sb.append("## 近期已平倉交易\n查詢失敗\n\n");
            log.warn("Advisor 取近期交易失敗: {}", e.getMessage());
        }
    }

    /** 第 6 段：累計統計 */
    private void appendOverallStats(StringBuilder sb) {
        try {
            Map<String, Object> stats = tradeRecordService.getStatsSummary();
            sb.append("## 累計統計\n");
            sb.append(String.format("總淨利: %s USDT | 勝率: %s | PF: %s\n",
                    stats.get("totalNetProfit"),
                    stats.get("winRate"),
                    stats.get("profitFactor")));
        } catch (Exception e) {
            sb.append("## 累計統計\n查詢失敗\n\n");
            log.warn("Advisor 取累計統計失敗: {}", e.getMessage());
        }
    }

    // ─── Discord 通知 ───────────────────────────────────────────

    private void sendAdvisoryNotification(String aiResponse) {
        // Discord embed description 限制 4096 字元
        String content = aiResponse.length() > 3800
                ? aiResponse.substring(0, 3800) + "\n\n...（已截斷）"
                : aiResponse;

        webhookService.sendNotification(
                "\uD83E\uDD16 AI 交易顧問",
                content,
                COLOR_PURPLE);
    }
}
