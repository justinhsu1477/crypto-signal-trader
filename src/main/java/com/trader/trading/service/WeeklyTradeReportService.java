package com.trader.trading.service;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.NotificationService;
import com.trader.shared.config.AppConstants;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 每週交易績效報告 — 自動推送到 Admin Discord
 *
 * 觸發：每週一 09:00 (Asia/Taipei)，覆蓋上週一 00:00 ~ 週日 23:59 已平倉實盤交易
 *
 * 報告內容：
 * - 總體：交易數 / 累積 PnL / 勝率 / 最佳日
 * - 用戶 Top 5 PnL 排名
 * - 訊號來源分佈
 * - 多空 / 出場原因
 *
 * 依賴：
 * - 直接用 EntityManager 跑 native query（不污染 TradeRepository）
 * - 使用既有 DiscordWebhookService.sendNotificationToAdmins
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WeeklyTradeReportService {

    private final EntityManager entityManager;
    private final NotificationService notificationService;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM/dd");
    private static final int TOP_USERS_LIMIT = 5;

    /**
     * 每週一 9:00 自動推送（Asia/Taipei）
     * cron: 秒 分 時 日 月 週 — 0 0 9 ? * MON
     */
    @Scheduled(cron = "0 0 9 ? * MON", zone = "Asia/Taipei")
    public void sendWeeklyReportScheduled() {
        try {
            sendWeeklyReport();
        } catch (Exception e) {
            log.error("週報自動推送失敗: {}", e.getMessage(), e);
        }
    }

    /**
     * 立即產出 + 推送週報（admin 手動觸發或 scheduler 呼叫）
     */
    public void sendWeeklyReport() {
        Window window = computeLastWeekWindow();
        log.info("產生週報: {} ~ {}", window.start.toLocalDate(), window.end.toLocalDate());

        Summary summary = querySummary(window);
        if (summary.totalTrades == 0) {
            log.info("上週無已平倉實盤交易，跳過週報");
            return;
        }

        String body = buildBody(window, summary);
        int color = summary.totalPnl >= 0
                ? DiscordWebhookService.COLOR_GREEN
                : DiscordWebhookService.COLOR_RED;
        String title = String.format("📊 週報 %s ~ %s",
                window.start.toLocalDate().format(DATE_FMT),
                window.end.toLocalDate().format(DATE_FMT));

        notificationService.sendNotificationToAdmins(title, body, color);
        log.info("週報推送完成: trades={} pnl={}", summary.totalTrades, summary.totalPnl);
    }

    // ==================== 查詢 ====================

    @Transactional(readOnly = true)
    Summary querySummary(Window window) {
        Summary s = new Summary();

        // 1. 總體
        Object[] overall = (Object[]) entityManager.createNativeQuery("""
                SELECT
                  COUNT(*) AS total,
                  COUNT(*) FILTER (WHERE net_profit > 0) AS wins,
                  COUNT(*) FILTER (WHERE net_profit <= 0) AS losses,
                  COALESCE(SUM(net_profit), 0) AS total_pnl,
                  COALESCE(MAX(net_profit), 0) AS best,
                  COALESCE(MIN(net_profit), 0) AS worst
                FROM trades
                WHERE status = 'CLOSED' AND (simulated = FALSE OR simulated IS NULL)
                  AND exit_time >= :start AND exit_time < :end
                """)
                .setParameter("start", window.start)
                .setParameter("end", window.end)
                .getSingleResult();
        s.totalTrades = ((Number) overall[0]).longValue();
        s.wins = ((Number) overall[1]).longValue();
        s.losses = ((Number) overall[2]).longValue();
        s.totalPnl = ((Number) overall[3]).doubleValue();
        s.bestTrade = ((Number) overall[4]).doubleValue();
        s.worstTrade = ((Number) overall[5]).doubleValue();

        if (s.totalTrades == 0) return s;

        // 2. Top users（依 PnL 降冪取 N）
        @SuppressWarnings("unchecked")
        List<Object[]> userRows = entityManager.createNativeQuery("""
                SELECT
                  COALESCE(u.name, u.email, t.user_id) AS user_name,
                  COUNT(*) AS trades,
                  COALESCE(SUM(t.net_profit), 0) AS pnl
                FROM trades t
                LEFT JOIN users u ON t.user_id = u.user_id
                WHERE t.status = 'CLOSED' AND (t.simulated = FALSE OR t.simulated IS NULL)
                  AND t.exit_time >= :start AND t.exit_time < :end
                GROUP BY u.name, u.email, t.user_id
                ORDER BY pnl DESC
                LIMIT :lim
                """)
                .setParameter("start", window.start)
                .setParameter("end", window.end)
                .setParameter("lim", TOP_USERS_LIMIT)
                .getResultList();
        for (Object[] row : userRows) {
            UserStat us = new UserStat();
            us.name = (String) row[0];
            us.trades = ((Number) row[1]).longValue();
            us.pnl = ((Number) row[2]).doubleValue();
            s.topUsers.add(us);
        }

        // 3. 訊號來源分佈
        @SuppressWarnings("unchecked")
        List<Object[]> sourceRows = entityManager.createNativeQuery("""
                SELECT
                  COALESCE(source_author_name, '(未知)') AS src,
                  COUNT(*) AS trades,
                  COALESCE(SUM(net_profit), 0) AS pnl
                FROM trades
                WHERE status = 'CLOSED' AND (simulated = FALSE OR simulated IS NULL)
                  AND exit_time >= :start AND exit_time < :end
                GROUP BY source_author_name
                ORDER BY pnl DESC
                """)
                .setParameter("start", window.start)
                .setParameter("end", window.end)
                .getResultList();
        for (Object[] row : sourceRows) {
            SourceStat ss = new SourceStat();
            ss.name = (String) row[0];
            ss.trades = ((Number) row[1]).longValue();
            ss.pnl = ((Number) row[2]).doubleValue();
            s.sources.add(ss);
        }

        // 4. 多空 + 出場原因
        @SuppressWarnings("unchecked")
        List<Object[]> sideRows = entityManager.createNativeQuery("""
                SELECT side, COUNT(*), COALESCE(SUM(net_profit), 0)
                FROM trades
                WHERE status = 'CLOSED' AND (simulated = FALSE OR simulated IS NULL)
                  AND exit_time >= :start AND exit_time < :end
                GROUP BY side
                """)
                .setParameter("start", window.start)
                .setParameter("end", window.end)
                .getResultList();
        for (Object[] row : sideRows) {
            String key = row[0] == null ? "(null)" : row[0].toString();
            s.sideCounts.put(key, ((Number) row[1]).longValue());
            s.sidePnl.put(key, ((Number) row[2]).doubleValue());
        }

        return s;
    }

    // ==================== 訊息組裝 ====================

    String buildBody(Window window, Summary s) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("**%s ~ %s** 已平倉實盤交易彙整\n\n",
                window.start.toLocalDate().format(DATE_FMT),
                window.end.toLocalDate().format(DATE_FMT)));

        // 總體
        sb.append("**📈 總覽**\n");
        sb.append(String.format("• 交易數: %d 筆\n", s.totalTrades));
        sb.append(String.format("• 累積 PnL: %s%.2f USDT\n",
                s.totalPnl >= 0 ? "+" : "", s.totalPnl));
        double winRate = s.totalTrades > 0 ? 100.0 * s.wins / s.totalTrades : 0;
        sb.append(String.format("• 勝率: %.0f%% (%dW / %dL)\n", winRate, s.wins, s.losses));
        sb.append(String.format("• 最佳交易: +%.2f / 最差: %.2f\n\n", s.bestTrade, s.worstTrade));

        // Top users
        if (!s.topUsers.isEmpty()) {
            sb.append("**🥇 用戶 PnL Top ").append(s.topUsers.size()).append("**\n");
            int rank = 1;
            for (UserStat u : s.topUsers) {
                sb.append(String.format("%d. **%s** %s%.2f (%d 筆)\n",
                        rank++, u.name,
                        u.pnl >= 0 ? "+" : "", u.pnl, u.trades));
            }
            sb.append("\n");
        }

        // 訊號來源
        if (!s.sources.isEmpty()) {
            sb.append("**📡 訊號來源**\n");
            for (SourceStat src : s.sources) {
                double pct = s.totalPnl != 0 ? 100.0 * src.pnl / s.totalPnl : 0;
                sb.append(String.format("• %s: %s%.2f USDT (%.0f%%, %d 筆)\n",
                        src.name,
                        src.pnl >= 0 ? "+" : "", src.pnl, pct, src.trades));
            }
            sb.append("\n");
        }

        // 多空
        if (!s.sideCounts.isEmpty()) {
            sb.append("**📊 多空分佈**\n");
            for (Map.Entry<String, Long> entry : s.sideCounts.entrySet()) {
                String side = entry.getKey();
                long count = entry.getValue();
                double pnl = s.sidePnl.getOrDefault(side, 0.0);
                sb.append(String.format("• %s: %d 筆, %s%.2f USDT\n",
                        side, count, pnl >= 0 ? "+" : "", pnl));
            }
        }

        return sb.toString();
    }

    // ==================== Helpers ====================

    /** 計算上週一 00:00 ~ 本週一 00:00（Asia/Taipei，再轉成系統 LocalDateTime）*/
    Window computeLastWeekWindow() {
        LocalDate today = LocalDate.now(AppConstants.ZONE_ID);
        LocalDate thisMonday = today.with(DayOfWeek.MONDAY);
        LocalDate lastMonday = thisMonday.minusWeeks(1);
        return new Window(
                LocalDateTime.of(lastMonday, LocalTime.MIN),
                LocalDateTime.of(thisMonday, LocalTime.MIN));
    }

    // ==================== DTOs ====================

    static class Window {
        final LocalDateTime start;
        final LocalDateTime end;
        Window(LocalDateTime start, LocalDateTime end) { this.start = start; this.end = end; }
    }

    static class Summary {
        long totalTrades;
        long wins;
        long losses;
        double totalPnl;
        double bestTrade;
        double worstTrade;
        List<UserStat> topUsers = new ArrayList<>();
        List<SourceStat> sources = new ArrayList<>();
        java.util.LinkedHashMap<String, Long> sideCounts = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, Double> sidePnl = new java.util.LinkedHashMap<>();
    }

    static class UserStat {
        String name; long trades; double pnl;
    }

    static class SourceStat {
        String name; long trades; double pnl;
    }
}
