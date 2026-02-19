package com.trader.trading.service;

import com.trader.shared.config.AppConstants;
import com.trader.shared.config.RiskConfig;
import com.trader.trading.entity.Trade;
import com.trader.notification.service.DiscordWebhookService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 每日排程服務
 *
 * 排程任務：
 * 1. 07:55 — 殭屍 Trade 清理（比對幣安實際持倉）
 * 2. 08:00 — 每日交易摘要（Discord 通知）
 *
 * 報告包含 6 大區塊：
 * 1. 💰 帳戶餘額（Binance API）
 * 2. 📊 昨日交易（DB 已平倉明細 + 最差交易）
 * 3. 📍 當前持倉（DB OPEN 交易）
 * 4. 🛡️ 今日風控（DB 已實現虧損 + config 每日限額）
 * 5. 📈 累計統計（DB 聚合查詢）
 * 6. ⚙️ 系統狀態（Memory：Monitor 心跳 + WebSocket 連線）
 *
 * 特性：
 * - 獨立排程線程，不影響 HTTP 請求處理
 * - 全包 try-catch，任何失敗只 log 不拋出
 * - 清理在報告之前跑，確保報告中的持倉數是乾淨的
 */
@Slf4j
@Service
public class DailyReportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TradeRecordService tradeRecordService;
    private final DiscordWebhookService webhookService;
    private final BinanceFuturesService binanceFuturesService;
    private final BinanceUserDataStreamService userDataStreamService;
    private final MonitorHeartbeatService monitorHeartbeatService;
    private final RiskConfig riskConfig;

    public DailyReportService(TradeRecordService tradeRecordService,
                              DiscordWebhookService webhookService,
                              BinanceFuturesService binanceFuturesService,
                              BinanceUserDataStreamService userDataStreamService,
                              MonitorHeartbeatService monitorHeartbeatService,
                              RiskConfig riskConfig) {
        this.tradeRecordService = tradeRecordService;
        this.webhookService = webhookService;
        this.binanceFuturesService = binanceFuturesService;
        this.userDataStreamService = userDataStreamService;
        this.monitorHeartbeatService = monitorHeartbeatService;
        this.riskConfig = riskConfig;
    }

    // ==================== 排程 1: 殭屍 Trade 清理 ====================

    /**
     * 每日 07:55 台灣時間自動清理殭屍 OPEN 紀錄
     *
     * 在每日報告（08:00）前 5 分鐘執行，確保報告中的持倉數是乾淨的。
     * 比對 DB 中 OPEN 的 Trade 與幣安實際持倉，無持倉的標記為 CANCELLED。
     */
    @Scheduled(cron = "0 55 7 * * *", zone = "${app.timezone}")
    public void scheduledCleanup() {
        try {
            log.info("排程殭屍 Trade 清理開始...");
            Map<String, Object> result = tradeRecordService.cleanupStaleTrades(
                    symbol -> binanceFuturesService.getCurrentPositionAmount(symbol));

            int cleaned = (int) result.get("cleaned");
            int skipped = (int) result.get("skipped");
            log.info("排程清理完成: 清理 {} 筆, 跳過 {} 筆", cleaned, skipped);

            if (cleaned > 0) {
                webhookService.sendNotification(
                        "🧹 殭屍 Trade 自動清理",
                        String.format("清理: %d 筆 | 跳過: %d 筆\n來源: 每日排程 (07:55)", cleaned, skipped),
                        DiscordWebhookService.COLOR_BLUE);
            }
        } catch (Exception e) {
            log.error("排程清理失敗: {}", e.getMessage(), e);
            // 不拋出 — 不影響後續的每日報告排程
        }
    }

    // ==================== 排程 2: 每日交易摘要 ====================

    /**
     * 每日 08:00 台灣時間自動發送每日交易摘要
     *
     * cron = "0 0 8 * * *" → 每天 08:00:00
     * zone = "${app.timezone}" → 台灣時區
     *
     * 時間範圍：昨天 00:00:00 ~ 今天 00:00:00（台灣時間）
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "${app.timezone}")
    public void sendDailyReport() {
        try {
            log.info("開始產生每日交易摘要...");

            // 1. 計算昨天的時間範圍
            LocalDate today = LocalDate.now(AppConstants.ZONE_ID);
            LocalDate yesterday = today.minusDays(1);
            LocalDateTime startOfYesterday = yesterday.atStartOfDay();
            LocalDateTime startOfToday = today.atStartOfDay();

            // 2. 取得各項資料
            Map<String, Object> yesterdayStats = tradeRecordService.getStatsForDateRange(startOfYesterday, startOfToday);
            List<Trade> yesterdayTrades = tradeRecordService.getClosedTradesForRange(startOfYesterday, startOfToday);
            Map<String, Object> overallStats = tradeRecordService.getStatsSummary();

            // 3. 組裝訊息
            String dateStr = yesterday.format(DATE_FMT);
            String message = buildDailyMessage(dateStr, yesterdayStats, yesterdayTrades, overallStats);

            // 4. 發送 Discord
            webhookService.sendNotification(
                    "📊 每日交易摘要 — " + dateStr,
                    message,
                    DiscordWebhookService.COLOR_BLUE);

            log.info("每日交易摘要已發送（{}）", dateStr);

        } catch (Exception e) {
            log.error("每日摘要發送失敗: {}", e.getMessage(), e);
            // 不拋出 — 排程下次照常執行
        }
    }

    /**
     * 組裝每日摘要訊息（6 大區塊）
     */
    @SuppressWarnings("unchecked")
    private String buildDailyMessage(String dateStr, Map<String, Object> dayStats,
                                      List<Trade> closedTrades, Map<String, Object> overallStats) {
        StringBuilder sb = new StringBuilder();

        // ===== 1. 帳戶餘額（Binance API）=====
        appendBalance(sb);

        // ===== 2. 昨日交易（DB）=====
        appendYesterdayTrades(sb, dayStats, closedTrades);

        // ===== 3. 當前持倉（DB）=====
        List<Trade> openTrades = (List<Trade>) dayStats.get("openTrades");
        appendOpenPositions(sb, openTrades);

        // ===== 4. 今日風控（DB + config）=====
        appendRiskBudget(sb);

        // ===== 5. 累計統計（DB）=====
        appendOverallStats(sb, overallStats);

        // ===== 6. 系統狀態（Memory）=====
        appendSystemStatus(sb);

        return sb.toString();
    }

    // ==================== 區塊 1: 帳戶餘額 ====================

    private void appendBalance(StringBuilder sb) {
        sb.append("💰 帳戶餘額\n");
        try {
            double balance = binanceFuturesService.getAvailableBalance();
            sb.append(String.format("可用餘額: %.2f USDT\n", balance));
        } catch (Exception e) {
            sb.append("可用餘額: 查詢失敗\n");
            log.warn("每日報告取餘額失敗: {}", e.getMessage());
        }
        sb.append("\n");
    }

    // ==================== 區塊 2: 昨日交易 ====================

    private void appendYesterdayTrades(StringBuilder sb, Map<String, Object> dayStats, List<Trade> closedTrades) {
        sb.append("📊 昨日交易\n");

        long trades = (long) dayStats.get("trades");
        long wins = (long) dayStats.get("wins");
        long losses = (long) dayStats.get("losses");
        double netProfit = (double) dayStats.get("netProfit");
        double commission = (double) dayStats.get("commission");

        if (trades == 0) {
            sb.append("昨日無已平倉交易\n");
        } else {
            String winRate = trades > 0 ? String.format("%.0f%%", (double) wins / trades * 100) : "0%";
            sb.append(String.format("交易筆數: %d (%d 勝 %d 負) | 勝率: %s\n", trades, wins, losses, winRate));
            sb.append(String.format("昨日淨利: %s USDT | 手續費: %.2f USDT\n", formatProfit(netProfit), commission));

            // 交易明細（最多列出 5 筆）
            if (!closedTrades.isEmpty()) {
                sb.append("─ 明細 ─\n");
                int limit = Math.min(closedTrades.size(), 5);
                for (int i = 0; i < limit; i++) {
                    Trade t = closedTrades.get(i);
                    String profit = t.getNetProfit() != null ? formatProfit(t.getNetProfit()) : "N/A";
                    String reason = t.getExitReason() != null ? t.getExitReason() : "?";
                    sb.append(String.format("  %s %s %s → %s USDT (%s)\n",
                            t.getSymbol(), t.getSide(),
                            formatPrice(t.getEntryPrice()) + "→" + formatPrice(t.getExitPrice()),
                            profit, reason));
                }
                if (closedTrades.size() > 5) {
                    sb.append(String.format("  ...還有 %d 筆\n", closedTrades.size() - 5));
                }

                // 最差交易
                closedTrades.stream()
                        .filter(t -> t.getNetProfit() != null)
                        .min(Comparator.comparingDouble(Trade::getNetProfit))
                        .ifPresent(worst -> {
                            if (worst.getNetProfit() < 0) {
                                sb.append(String.format("最大單筆虧損: %s %s %s USDT\n",
                                        worst.getSymbol(), worst.getSide(), formatProfit(worst.getNetProfit())));
                            }
                        });
            }
        }
        sb.append("\n");
    }

    // ==================== 區塊 3: 當前持倉 ====================

    private void appendOpenPositions(StringBuilder sb, List<Trade> openTrades) {
        sb.append("📍 當前持倉\n");
        if (openTrades == null || openTrades.isEmpty()) {
            sb.append("無持倉\n");
        } else {
            sb.append(String.format("持倉數: %d\n", openTrades.size()));
            for (Trade t : openTrades) {
                sb.append(String.format("• %s %s @ %s",
                        t.getSymbol(), t.getSide(),
                        formatPrice(t.getEntryPrice())));
                if (t.getStopLoss() != null) {
                    sb.append(String.format(" (SL: %s)", formatPrice(t.getStopLoss())));
                }
                if (t.getDcaCount() != null && t.getDcaCount() > 0) {
                    sb.append(String.format(" [DCA×%d]", t.getDcaCount()));
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    // ==================== 區塊 4: 今日風控 ====================

    private void appendRiskBudget(StringBuilder sb) {
        sb.append("🛡️ 今日風控\n");
        try {
            double todayLoss = tradeRecordService.getTodayRealizedLoss(); // 負數
            double maxDaily = riskConfig.getMaxDailyLossUsdt();
            double usedAbs = Math.abs(todayLoss);
            double usagePercent = maxDaily > 0 ? usedAbs / maxDaily * 100 : 0;

            sb.append(String.format("已用額度: %.2f / %.0f USDT (%.0f%%)\n", usedAbs, maxDaily, usagePercent));

            if (usagePercent >= 100) {
                sb.append("⛔ 熔斷中 — 今日已達虧損上限\n");
            } else if (usagePercent >= 70) {
                sb.append("⚠️ 接近熔斷線\n");
            } else {
                sb.append("✅ 正常\n");
            }
        } catch (Exception e) {
            sb.append("風控狀態: 查詢失敗\n");
            log.warn("每日報告取風控資料失敗: {}", e.getMessage());
        }
        sb.append("\n");
    }

    // ==================== 區塊 5: 累計統計 ====================

    private void appendOverallStats(StringBuilder sb, Map<String, Object> overallStats) {
        sb.append("📈 累計統計\n");
        sb.append(String.format("總淨利: %s USDT | 勝率: %s\n",
                formatProfit((double) overallStats.get("totalNetProfit")),
                overallStats.get("winRate")));
        sb.append(String.format("PF: %.2f | 平均每筆: %s USDT\n",
                (double) overallStats.get("profitFactor"),
                formatProfit((double) overallStats.get("avgProfitPerTrade"))));
        sb.append(String.format("總手續費: %.2f USDT | 已平倉: %d 筆\n",
                (double) overallStats.get("totalCommission"),
                (long) overallStats.get("closedTrades")));
        sb.append("\n");
    }

    // ==================== 區塊 6: 系統狀態 ====================

    private void appendSystemStatus(StringBuilder sb) {
        sb.append("⚙️ 系統狀態\n");

        // Monitor 心跳
        try {
            Map<String, Object> monitorStatus = monitorHeartbeatService.getStatus();
            boolean monitorOnline = (boolean) monitorStatus.get("online");
            String mStatus = (String) monitorStatus.get("monitorStatus");
            String aiStatus = (String) monitorStatus.get("aiStatus");

            sb.append(String.format("Monitor: %s (%s)",
                    monitorOnline ? "🟢 在線" : "🔴 離線", mStatus));
            sb.append(String.format(" | AI: %s\n",
                    "active".equals(aiStatus) ? "🟢" : "⚠️ " + aiStatus));
        } catch (Exception e) {
            sb.append("Monitor: 查詢失敗\n");
        }

        // WebSocket 連線
        try {
            Map<String, Object> wsStatus = userDataStreamService.getStatus();
            boolean wsConnected = (boolean) wsStatus.get("connected");
            sb.append(String.format("WebSocket: %s\n",
                    wsConnected ? "🟢 已連線" : "🔴 未連線"));
        } catch (Exception e) {
            sb.append("WebSocket: 查詢失敗\n");
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 格式化盈虧數字（正數加 +，負數自帶 -）
     */
    private String formatProfit(double value) {
        if (value >= 0) {
            return String.format("+%.2f", value);
        }
        return String.format("%.2f", value);
    }

    /**
     * 格式化價格（避免 null）
     */
    private String formatPrice(Double price) {
        if (price == null) return "N/A";
        // 整數價格不顯示小數點
        if (price == Math.floor(price) && price < 1_000_000) {
            return String.format("%.0f", price);
        }
        return String.format("%.2f", price);
    }
}
