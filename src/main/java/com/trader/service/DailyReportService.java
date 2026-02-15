package com.trader.service;

import com.trader.entity.Trade;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 每日交易摘要排程服務
 *
 * 每天台灣時間 08:00（= UTC 00:00，幣安日線切換時間）自動發送
 * Discord 通知，彙整當日交易績效和累計統計。
 *
 * 特性：
 * - 獨立排程線程，不影響 HTTP 請求處理
 * - 全包 try-catch，任何失敗只 log 不拋出
 * - 唯讀操作（只讀 DB + 發 Webhook），不影響交易邏輯
 */
@Slf4j
@Service
public class DailyReportService {

    private static final ZoneId TAIPEI_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TradeRecordService tradeRecordService;
    private final DiscordWebhookService webhookService;

    public DailyReportService(TradeRecordService tradeRecordService, DiscordWebhookService webhookService) {
        this.tradeRecordService = tradeRecordService;
        this.webhookService = webhookService;
    }

    /**
     * 每日 08:00 台灣時間自動發送交易摘要
     *
     * cron = "0 0 8 * * *" → 每天 08:00:00
     * zone = "Asia/Taipei" → 台灣時區
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Taipei")
    public void sendDailyReport() {
        try {
            log.info("開始產生每日交易摘要...");

            // 1. 取得今日統計
            Map<String, Object> todayStats = tradeRecordService.getTodayStats();

            // 2. 取得累計統計
            Map<String, Object> overallStats = tradeRecordService.getStatsSummary();

            // 3. 組裝訊息
            String dateStr = ZonedDateTime.now(TAIPEI_ZONE).format(DATE_FMT);
            String message = buildDailyMessage(dateStr, todayStats, overallStats);

            // 4. 發送 Discord
            webhookService.sendNotification(
                    "📊 每日交易摘要 — " + dateStr,
                    message,
                    DiscordWebhookService.COLOR_BLUE);

            log.info("每日交易摘要已發送");

        } catch (Exception e) {
            log.error("每日摘要發送失敗: {}", e.getMessage(), e);
            // 不拋出 — 排程下次照常執行
        }
    }

    /**
     * 組裝每日摘要訊息
     */
    @SuppressWarnings("unchecked")
    private String buildDailyMessage(String dateStr, Map<String, Object> todayStats, Map<String, Object> overallStats) {
        StringBuilder sb = new StringBuilder();

        long todayTrades = (long) todayStats.get("todayTrades");
        long todayWins = (long) todayStats.get("todayWins");
        long todayLosses = (long) todayStats.get("todayLosses");
        double todayNetProfit = (double) todayStats.get("todayNetProfit");
        double todayCommission = (double) todayStats.get("todayCommission");
        List<Trade> openTrades = (List<Trade>) todayStats.get("openTrades");

        // === 今日交易 ===
        if (todayTrades == 0) {
            sb.append("今日無已平倉交易\n");
        } else {
            sb.append(String.format("今日交易: %d 筆 (%d 勝 %d 負)\n", todayTrades, todayWins, todayLosses));
            sb.append(String.format("今日淨利: %s USDT\n", formatProfit(todayNetProfit)));
            sb.append(String.format("今日手續費: %.2f USDT\n", todayCommission));
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
