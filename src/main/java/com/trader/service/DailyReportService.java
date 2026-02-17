package com.trader.service;

import com.trader.entity.Trade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 每日排程服務
 *
 * 排程任務：
 * 1. 07:55 — 殭屍 Trade 清理（比對幣安實際持倉）
 * 2. 08:00 — 昨日交易摘要（Discord 通知）
 *
 * 特性：
 * - 獨立排程線程，不影響 HTTP 請求處理
 * - 全包 try-catch，任何失敗只 log 不拋出
 * - 清理在報告之前跑，確保報告中的持倉數是乾淨的
 */
@Slf4j
@Service
public class DailyReportService {

    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TradeRecordService tradeRecordService;
    private final DiscordWebhookService webhookService;
    private final BinanceFuturesService binanceFuturesService;

    public DailyReportService(TradeRecordService tradeRecordService,
                              DiscordWebhookService webhookService,
                              BinanceFuturesService binanceFuturesService) {
        this.tradeRecordService = tradeRecordService;
        this.webhookService = webhookService;
        this.binanceFuturesService = binanceFuturesService;
    }

    // ==================== 排程 1: 殭屍 Trade 清理 ====================

    /**
     * 每日 07:55 台灣時間自動清理殭屍 OPEN 紀錄
     *
     * 在每日報告（08:00）前 5 分鐘執行，確保報告中的持倉數是乾淨的。
     * 比對 DB 中 OPEN 的 Trade 與幣安實際持倉，無持倉的標記為 CANCELLED。
     */
    @Scheduled(cron = "0 55 7 * * *", zone = "Asia/Taipei")
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

    // ==================== 排程 2: 昨日交易摘要 ====================

    /**
     * 每日 08:00 台灣時間自動發送「昨日」交易摘要
     *
     * cron = "0 0 8 * * *" → 每天 08:00:00
     * zone = "Asia/Taipei" → 台灣時區
     *
     * 時間範圍：昨天 00:00:00 ~ 今天 00:00:00（台灣時間）
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Taipei")
    public void sendDailyReport() {
        try {
            log.info("開始產生每日交易摘要...");

            // 1. 計算昨天的時間範圍
            LocalDate today = LocalDate.now(TAIPEI_ZONE);
            LocalDate yesterday = today.minusDays(1);
            LocalDateTime startOfYesterday = yesterday.atStartOfDay();
            LocalDateTime startOfToday = today.atStartOfDay();

            // 2. 取得昨日統計
            Map<String, Object> yesterdayStats = tradeRecordService.getStatsForDateRange(startOfYesterday, startOfToday);

            // 3. 取得累計統計
            Map<String, Object> overallStats = tradeRecordService.getStatsSummary();

            // 4. 組裝訊息（標題顯示昨天日期）
            String dateStr = yesterday.format(DATE_FMT);
            String message = buildDailyMessage(dateStr, yesterdayStats, overallStats);

            // 5. 發送 Discord
            webhookService.sendNotification(
                    "📊 每日交易摘要 — " + dateStr + "（昨日）",
                    message,
                    DiscordWebhookService.COLOR_BLUE);

            log.info("每日交易摘要已發送（{}）", dateStr);

        } catch (Exception e) {
            log.error("每日摘要發送失敗: {}", e.getMessage(), e);
            // 不拋出 — 排程下次照常執行
        }
    }

    /**
     * 組裝每日摘要訊息
     */
    @SuppressWarnings("unchecked")
    private String buildDailyMessage(String dateStr, Map<String, Object> dayStats, Map<String, Object> overallStats) {
        StringBuilder sb = new StringBuilder();

        long trades = (long) dayStats.get("trades");
        long wins = (long) dayStats.get("wins");
        long losses = (long) dayStats.get("losses");
        double netProfit = (double) dayStats.get("netProfit");
        double commission = (double) dayStats.get("commission");
        List<Trade> openTrades = (List<Trade>) dayStats.get("openTrades");

        // === 昨日交易 ===
        if (trades == 0) {
            sb.append("昨日無已平倉交易\n");
        } else {
            sb.append(String.format("昨日交易: %d 筆 (%d 勝 %d 負)\n", trades, wins, losses));
            sb.append(String.format("昨日淨利: %s USDT\n", formatProfit(netProfit)));
            sb.append(String.format("昨日手續費: %.2f USDT\n", commission));
        }

        // === 當前持倉 ===
        sb.append("\n");
        if (openTrades.isEmpty()) {
            sb.append("當前持倉: 無\n");
        } else {
            sb.append(String.format("當前持倉: %d 筆\n", openTrades.size()));
            for (Trade t : openTrades) {
                sb.append(String.format("• %s %s @ %s",
                        t.getSymbol(), t.getSide(),
                        formatPrice(t.getEntryPrice())));
                if (t.getStopLoss() != null) {
                    sb.append(String.format(" (SL: %s)", formatPrice(t.getStopLoss())));
                }
                sb.append("\n");
            }
        }

        // === 累計統計 ===
        sb.append("\n");
        sb.append("累計統計:\n");
        sb.append(String.format("總淨利: %s USDT | 勝率: %s\n",
                formatProfit((double) overallStats.get("totalNetProfit")),
                overallStats.get("winRate")));
        sb.append(String.format("Profit Factor: %.2f | 總手續費: %.2f USDT",
                (double) overallStats.get("profitFactor"),
                (double) overallStats.get("totalCommission")));

        return sb.toString();
    }

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
