package com.trader.trading.service;

import com.trader.shared.config.AppConstants;
import com.trader.shared.config.RiskConfig;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.dto.signalsource.ShadowGraduationResult;
import com.trader.trading.entity.Trade;
import com.trader.trading.model.TradeContext;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.NotificationService;
import com.trader.user.entity.User;
import com.trader.user.repository.UserDiscordWebhookRepository;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 每日排程服務
 *
 * 排程任務：
 * 1. 07:55 — 殭屍 Trade 清理（比對幣安實際持倉）
 * 2. 08:00 — 每日交易摘要（Discord 通知）
 *
 * 多用戶模式（MULTI_USER_ENABLED）：
 * - false（單人）：全局查詢 + 全局 webhook（現有行為不變）
 * - true（多人）：遍歷每個 enabled 用戶 → per-user 查詢 + per-user webhook
 *
 * 報告包含 6 大區塊：
 * 1. 帳戶餘額（Binance API — 多用戶模式用 per-user API Key）
 * 2. 昨日交易（DB 已平倉明細 + 最差交易）
 * 3. 當前持倉（DB OPEN 交易）
 * 4. 今日風控（DB 已實現虧損 + config 每日限額）
 * 5. 累計統計（DB 聚合查詢）
 * 6. 系統狀態（Memory：Monitor 心跳 + WebSocket 連線）
 *
 * 特性：
 * - 獨立排程線程，不影響 HTTP 請求處理
 * - 全包 try-catch，任何失敗只 log 不拋出
 * - 清理在報告之前跑，確保報告中的持倉數是乾淨的
 * - 多用戶模式下，一個用戶發送失敗不影響其他用戶
 */
@Slf4j
@Service
public class DailyReportService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final TradeRecordService tradeRecordService;
    private final NotificationService webhookService;
    private final BinanceFuturesService binanceFuturesService;
    private final BinanceUserDataStreamService userDataStreamService;
    private final MonitorHeartbeatService monitorHeartbeatService;
    private final RiskConfig riskConfig;
    private final MultiUserConfig multiUserConfig;
    private final UserRepository userRepository;
    private final UserApiKeyService userApiKeyService;
    private final UserDiscordWebhookRepository userDiscordWebhookRepository;
    private final TradeConfigResolver tradeConfigResolver;
    private final StartOfDayBalanceCache startOfDayBalanceCache;
    private final ShadowGraduationService shadowGraduationService;

    public DailyReportService(TradeRecordService tradeRecordService,
                              NotificationService webhookService,
                              BinanceFuturesService binanceFuturesService,
                              BinanceUserDataStreamService userDataStreamService,
                              MonitorHeartbeatService monitorHeartbeatService,
                              RiskConfig riskConfig,
                              MultiUserConfig multiUserConfig,
                              UserRepository userRepository,
                              UserApiKeyService userApiKeyService,
                              UserDiscordWebhookRepository userDiscordWebhookRepository,
                              TradeConfigResolver tradeConfigResolver,
                              StartOfDayBalanceCache startOfDayBalanceCache,
                              ShadowGraduationService shadowGraduationService) {
        this.tradeRecordService = tradeRecordService;
        this.webhookService = webhookService;
        this.binanceFuturesService = binanceFuturesService;
        this.userDataStreamService = userDataStreamService;
        this.monitorHeartbeatService = monitorHeartbeatService;
        this.riskConfig = riskConfig;
        this.multiUserConfig = multiUserConfig;
        this.userRepository = userRepository;
        this.userApiKeyService = userApiKeyService;
        this.userDiscordWebhookRepository = userDiscordWebhookRepository;
        this.tradeConfigResolver = tradeConfigResolver;
        this.startOfDayBalanceCache = startOfDayBalanceCache;
        this.shadowGraduationService = shadowGraduationService;
    }

    // ==================== 排程 1: 殭屍 Trade 清理 ====================

    /**
     * 每日 07:55 台灣時間自動清理殭屍 OPEN 紀錄
     *
     * 在每日報告（08:00）前 5 分鐘執行，確保報告中的持倉數是乾淨的。
     * 比對 DB 中 OPEN 的 Trade 與幣安實際持倉，無持倉的標記為 CANCELLED。
     *
     * 多用戶模式下：遍歷每個用戶，使用各自的 API Key 查詢持倉。
     * 單人模式下：使用全局 API Key 查詢（現有行為不變）。
     */
    @Scheduled(cron = "0 55 7 * * *", zone = "${app.timezone}")
    public void scheduledCleanup() {
        try {
            if (multiUserConfig.isEnabled()) {
                cleanupForAllUsers();
            } else {
                cleanupGlobal();
            }
        } catch (Exception e) {
            log.error("排程清理失敗: {}", e.getMessage(), e);
            // 不拋出 — 不影響後續的每日報告排程
        }
    }

    /**
     * 全局清理（單人模式） — 現有邏輯不變
     */
    private void cleanupGlobal() {
        log.info("排程殭屍 Trade 清理開始...");
        Map<String, Object> result = tradeRecordService.cleanupStaleTrades(
                binanceFuturesService::getCurrentPositionAmount);

        int cleaned = (int) result.get("cleaned");
        int skipped = (int) result.get("skipped");
        log.info("排程清理完成: 清理 {} 筆, 跳過 {} 筆", cleaned, skipped);

        if (cleaned > 0) {
            webhookService.sendNotification(
                    "🧹 殭屍 Trade 自動清理",
                    String.format("清理: %d 筆 | 跳過: %d 筆\n來源: 每日排程 (07:55)", cleaned, skipped),
                    DiscordWebhookService.COLOR_BLUE);
        }
    }

    /**
     * 遍歷所有用戶清理殭屍 Trade（多用戶模式）
     *
     * 每個用戶使用自己的 API Key 查詢幣安持倉，確保清理的是該用戶的殭屍交易。
     * 清理通知發到用戶各自的 webhook。
     */
    private void cleanupForAllUsers() {
        List<User> users = userRepository.findAll().stream()
                .filter(User::isEnabled)
                .toList();

        log.info("多用戶殭屍 Trade 清理開始: {} 個用戶", users.size());
        int totalCleaned = 0;

        for (User user : users) {
            String userId = user.getUserId();
            try {
                // 設定 per-user API Key 以查詢該用戶的幣安持倉
                Optional<BinanceKeys> keysOpt = userApiKeyService.getUserBinanceKeys(userId);
                if (keysOpt.isEmpty()) {
                    log.debug("用戶 {} 未設定 API Key，跳過殭屍清理", userId);
                    continue;
                }

                BinanceFuturesService.setCurrentUserKeys(keysOpt.get());
                TradeContext ctx = TradeContext.forScheduledTask(userId);
                ctx.installThreadLocals();

                try {
                    Map<String, Object> result = tradeRecordService.cleanupStaleTrades(
                            symbol -> binanceFuturesService.getCurrentPositionAmount(symbol));

                    int cleaned = (int) result.get("cleaned");
                    int skipped = (int) result.get("skipped");
                    totalCleaned += cleaned;

                    if (cleaned > 0) {
                        webhookService.sendNotificationToUser(userId,
                                "🧹 殭屍 Trade 自動清理",
                                String.format("清理: %d 筆 | 跳過: %d 筆\n來源: 每日排程 (07:55)",
                                        cleaned, skipped),
                                DiscordWebhookService.COLOR_BLUE);
                    }
                } finally {
                    BinanceFuturesService.clearCurrentUserKeys();
                    TradeContext.clearThreadLocals();
                }
            } catch (Exception e) {
                log.error("用戶 {} 殭屍清理失敗: {}", userId, e.getMessage());
            }
        }

        log.info("多用戶殭屍清理完成: 共清理 {} 筆 ({} 個用戶)", totalCleaned, users.size());
    }

    // ==================== 排程 2: 每日交易摘要 ====================

    /**
     * 每日 08:00 台灣時間自動發送每日交易摘要
     *
     * cron = "0 0 8 * * *" → 每天 08:00:00
     * zone = "${app.timezone}" → 台灣時區
     *
     * 多用戶模式：遍歷每個用戶，產生個人摘要，發到個人 webhook
     * 單人模式：全局查詢 + 全局 webhook（現有行為不變）
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "${app.timezone}")
    public void sendDailyReport() {
        try {
            if (multiUserConfig.isEnabled()) {
                sendPerUserDailyReports();
            } else {
                sendGlobalDailyReport();
            }
        } catch (Exception e) {
            log.error("每日摘要發送失敗: {}", e.getMessage(), e);
            // 不拋出 — 排程下次照常執行
        }
    }

    /**
     * 全局每日摘要（單人模式）— 現有邏輯不變
     */
    private void sendGlobalDailyReport() {
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

        // 5. 重置每日 AI token 統計
        monitorHeartbeatService.resetDailyTokenStats();

        log.info("每日交易摘要已發送（{}）", dateStr);
    }

    /**
     * Per-user 每日摘要（多用戶模式）
     *
     * 遍歷每個 enabled 用戶：
     * 1. 使用 explicit-userId 重載查詢個人交易數據
     * 2. 使用 per-user API Key 查詢個人幣安帳戶餘額
     * 3. 使用 per-user webhook 發送個人摘要
     * 4. 一個用戶失敗不影響其他用戶
     */
    private void sendPerUserDailyReports() {
        // 排除 Admin（Admin 有自己的彙總報告，不需 per-user 摘要）
        // 排除 discordNotificationEnabled=false 的用戶
        // 排除無 per-user webhook 的用戶（避免 fallback 到全局 webhook 洗版 Admin 群組）
        Set<String> userIdsWithWebhook = new HashSet<>(userDiscordWebhookRepository.findUserIdsWithEnabledWebhook());

        List<User> users = userRepository.findAll().stream()
                .filter(User::isEnabled)
                .filter(user -> user.getRole() != User.Role.ADMIN)
                .filter(User::isDiscordNotificationEnabled)
                .filter(user -> userIdsWithWebhook.contains(user.getUserId()))
                .toList();

        LocalDate today = LocalDate.now(AppConstants.ZONE_ID);
        LocalDate yesterday = today.minusDays(1);
        LocalDateTime startOfYesterday = yesterday.atStartOfDay();
        LocalDateTime startOfToday = today.atStartOfDay();
        String dateStr = yesterday.format(DATE_FMT);

        log.info("開始產生多用戶每日摘要: {} 個用戶 ({})", users.size(), dateStr);
        int sent = 0;
        long totalTrades = 0;
        double totalNetProfit = 0;
        int usersWithTrades = 0;

        for (User user : users) {
            String userId = user.getUserId();
            try {
                // 使用 explicit-userId 重載查詢個人數據
                Map<String, Object> stats = tradeRecordService.getStatsForDateRange(
                        startOfYesterday, startOfToday, userId);
                List<Trade> trades = tradeRecordService.getClosedTradesForRange(
                        startOfYesterday, startOfToday, userId);
                Map<String, Object> overall = tradeRecordService.getStatsSummary(userId);

                // 組裝個人摘要（per-user 版本）
                String message = buildPerUserDailyMessage(dateStr, stats, trades, overall, userId);

                // 發送到用戶個人 webhook
                webhookService.sendNotificationToUser(userId,
                        "📊 每日交易摘要 — " + dateStr,
                        message,
                        DiscordWebhookService.COLOR_BLUE);
                sent++;

                // 累加聚合數據（供 Admin 彙總）
                long userTrades = (long) stats.get("trades");
                totalTrades += userTrades;
                totalNetProfit += (double) stats.get("netProfit");
                if (userTrades > 0) usersWithTrades++;
            } catch (Exception e) {
                log.error("用戶 {} 每日摘要發送失敗: {}", userId, e.getMessage());
            }
        }

        // Admin 彙總報告
        sendAdminDailySummary(dateStr, users.size(), sent, totalTrades, totalNetProfit, usersWithTrades);

        // 重置每日 AI token 統計（全局，只做一次）
        monitorHeartbeatService.resetDailyTokenStats();
        log.info("多用戶每日摘要已發送: {}/{} 個用戶 ({})", sent, users.size(), dateStr);
    }

    /**
     * Admin 每日彙總報告（多用戶模式）
     *
     * 在所有 per-user 報告發送完成後，發送全平台聚合摘要給所有 Admin。
     * 數據在 per-user 循環中累加，不需額外 DB 查詢。
     */
    private void sendAdminDailySummary(String dateStr, int totalUsers, int sentCount,
                                        long totalTrades, double totalNetProfit, int usersWithTrades) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("👥 用戶: %d 人（%d 人已發送）\n", totalUsers, sentCount));
        sb.append(String.format("📊 昨日總交易: %d 筆（%d 人有交易）\n", totalTrades, usersWithTrades));
        sb.append(String.format("💰 全平台淨利: %s USDT\n", formatProfit(totalNetProfit)));
        if (usersWithTrades > 0) {
            sb.append(String.format("📈 平均每人: %s USDT\n", formatProfit(totalNetProfit / usersWithTrades)));
        }

        // AI Token 用量（僅 Admin 彙總報告顯示）
        sb.append("\n");
        appendAiTokenUsage(sb);

        // SHADOW 畢業評估
        appendShadowGraduation(sb);

        webhookService.sendNotificationToAdmins(
                "📊 每日彙總報告 — " + dateStr,
                sb.toString(),
                DiscordWebhookService.COLOR_BLUE);
    }

    // ==================== 訊息組裝 ====================

    /**
     * 組裝每日摘要訊息 — 單人模式用（6 大區塊，全局查詢）
     */
    @SuppressWarnings("unchecked")
    private String buildDailyMessage(String dateStr, Map<String, Object> dayStats,
                                      List<Trade> closedTrades, Map<String, Object> overallStats) {
        StringBuilder sb = new StringBuilder();

        // ===== 1. 帳戶餘額（全局 API Key）=====
        appendBalance(sb);

        // ===== 2. 昨日交易 =====
        appendYesterdayTrades(sb, dayStats, closedTrades);

        // ===== 3. 當前持倉 =====
        List<Trade> openTrades = (List<Trade>) dayStats.get("openTrades");
        appendOpenPositions(sb, openTrades);

        // ===== 4. 今日風控（全局 config）=====
        appendRiskBudget(sb);

        // ===== 5. 累計統計 =====
        appendOverallStats(sb, overallStats);

        // ===== 6. 系統狀態（單人模式 = Admin，含 AI 用量）=====
        appendSystemStatus(sb, true);

        // ===== 7. SHADOW 畢業評估 =====
        appendShadowGraduation(sb);

        return sb.toString();
    }

    /**
     * 組裝每日摘要訊息 — 多用戶模式用（per-user 查詢 + per-user API Key）
     */
    @SuppressWarnings("unchecked")
    private String buildPerUserDailyMessage(String dateStr, Map<String, Object> dayStats,
                                             List<Trade> closedTrades, Map<String, Object> overallStats,
                                             String userId) {
        StringBuilder sb = new StringBuilder();

        // ===== 1. 帳戶餘額（per-user API Key）=====
        appendBalanceForUser(sb, userId);

        // ===== 2. 昨日交易 =====
        appendYesterdayTrades(sb, dayStats, closedTrades);

        // ===== 3. 當前持倉 =====
        List<Trade> openTrades = (List<Trade>) dayStats.get("openTrades");
        appendOpenPositions(sb, openTrades);

        // ===== 4. 今日風控（per-user config）=====
        appendRiskBudgetForUser(sb, userId);

        // ===== 5. 累計統計 =====
        appendOverallStats(sb, overallStats);

        // ===== 6. 系統狀態（per-user 不含 AI 用量，AI 用量只放 Admin 彙總）=====
        appendSystemStatus(sb, false);

        return sb.toString();
    }

    // ==================== 區塊 1: 帳戶餘額 ====================

    /**
     * 帳戶餘額 — 全局 API Key（單人模式）
     */
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

    /**
     * 帳戶餘額 — per-user API Key（多用戶模式）
     *
     * 使用用戶的加密 API Key 查詢其幣安帳戶餘額。
     * 若用戶未設定 API Key，顯示提示訊息。
     */
    private void appendBalanceForUser(StringBuilder sb, String userId) {
        sb.append("💰 帳戶餘額\n");
        Optional<BinanceKeys> keysOpt = userApiKeyService.getUserBinanceKeys(userId);
        if (keysOpt.isEmpty()) {
            sb.append("可用餘額: 未設定 API Key\n");
        } else {
            BinanceFuturesService.setCurrentUserKeys(keysOpt.get());
            try {
                double balance = binanceFuturesService.getAvailableBalance();
                sb.append(String.format("可用餘額: %.2f USDT\n", balance));
            } catch (Exception e) {
                sb.append("可用餘額: 查詢失敗\n");
                log.warn("用戶 {} 每日報告取餘額失敗: {}", userId, e.getMessage());
            } finally {
                BinanceFuturesService.clearCurrentUserKeys();
            }
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

    /**
     * 今日風控 — 全局 config（單人模式）
     */
    private void appendRiskBudget(StringBuilder sb) {
        sb.append("🛡️ 今日風控\n");
        try {
            double todayLoss = tradeRecordService.getTodayRealizedLoss(); // 負數
            String userId = tradeRecordService.getActiveUserId();
            double balance = binanceFuturesService.getAvailableBalance();
            double sodBalance = startOfDayBalanceCache.getOrCompute(userId, () -> balance);
            EffectiveTradeConfig config = tradeConfigResolver.resolve(userId);
            double maxDaily = config.effectiveDailyLossLimit(sodBalance);
            appendRiskBudgetContent(sb, todayLoss, maxDaily);
        } catch (Exception e) {
            sb.append("風控狀態: 查詢失敗\n");
            log.warn("每日報告取風控資料失敗: {}", e.getMessage());
        }
        sb.append("\n");
    }

    /**
     * 今日風控 — per-user config（多用戶模式）
     */
    private void appendRiskBudgetForUser(StringBuilder sb, String userId) {
        sb.append("🛡️ 今日風控\n");
        try {
            double todayLoss = tradeRecordService.getTodayRealizedLoss(userId); // explicit-userId
            EffectiveTradeConfig config = tradeConfigResolver.resolve(userId);

            // 取餘額需要 per-user API Key
            double balance;
            Optional<BinanceKeys> keysOpt = userApiKeyService.getUserBinanceKeys(userId);
            if (keysOpt.isPresent()) {
                BinanceFuturesService.setCurrentUserKeys(keysOpt.get());
                try {
                    balance = binanceFuturesService.getAvailableBalance();
                } catch (Exception e) {
                    log.warn("用戶 {} 風控取餘額失敗: {}", userId, e.getMessage());
                    balance = 0;
                } finally {
                    BinanceFuturesService.clearCurrentUserKeys();
                }
            } else {
                balance = 0;
            }

            final double fetchedBalance = balance;
            double sodBalance = startOfDayBalanceCache.getOrCompute(userId, () -> fetchedBalance);
            double maxDaily = config.effectiveDailyLossLimit(sodBalance);
            appendRiskBudgetContent(sb, todayLoss, maxDaily);
        } catch (Exception e) {
            sb.append("風控狀態: 查詢失敗\n");
            log.warn("用戶 {} 每日報告取風控資料失敗: {}", userId, e.getMessage());
        }
        sb.append("\n");
    }

    /**
     * 風控區塊共用內容
     */
    private void appendRiskBudgetContent(StringBuilder sb, double todayLoss, double maxDaily) {
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
    }

    // ==================== 區塊 5: 累計統計 ====================

    private void appendOverallStats(StringBuilder sb, Map<String, Object> overallStats) {
        sb.append("📈 累計統計\n");
        sb.append(String.format("總淨利: %s USDT | 勝率: %s\n",
                formatProfit((double) overallStats.get("totalNetProfit")),
                overallStats.get("winRate")));
        double pf = (double) overallStats.get("profitFactor");
        String pfStr = pf == 0 ? "N/A" : String.format("%.2f", pf);
        sb.append(String.format("PF: %s | 平均每筆: %s USDT\n",
                pfStr,
                formatProfit((double) overallStats.get("avgProfitPerTrade"))));
        sb.append(String.format("總手續費: %.2f USDT | 已平倉: %d 筆\n",
                (double) overallStats.get("totalCommission"),
                (long) overallStats.get("closedTrades")));
        sb.append("\n");
    }

    // ==================== 區塊 6: 系統狀態 ====================

    private void appendSystemStatus(StringBuilder sb, boolean includeAiUsage) {
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

        // AI Token 用量（僅 Admin 彙總 / 單人模式顯示）
        if (includeAiUsage) {
            appendAiTokenUsage(sb);
        }
    }

    /**
     * AI Token 用量區塊（獨立方法，供 Admin 彙總報告和單人模式使用）
     */
    private void appendAiTokenUsage(StringBuilder sb) {
        try {
            Map<String, Long> tokenStats = monitorHeartbeatService.getDailyTokenStats();
            long calls = tokenStats.get("callCount");
            long prompt = tokenStats.get("promptTokens");
            long response = tokenStats.get("responseTokens");
            if (calls > 0) {
                sb.append(String.format("🤖 AI 用量: %d 次呼叫 | %,d + %,d = %,d tokens\n",
                        calls, prompt, response, prompt + response));
            } else {
                sb.append("🤖 AI 用量: 無呼叫紀錄\n");
            }
        } catch (Exception e) {
            sb.append("🤖 AI 用量: 查詢失敗\n");
        }
    }

    // ==================== SHADOW 畢業評估 ====================

    /**
     * SHADOW 頻道畢業評估區塊 — 附加到 Admin 彙總報告
     * 無 SHADOW 頻道時不輸出任何內容
     */
    private void appendShadowGraduation(StringBuilder sb) {
        try {
            List<ShadowGraduationResult> results = shadowGraduationService.evaluateAll();
            if (results.isEmpty()) return;

            sb.append("\n🔬 SHADOW 觀察中（").append(results.size()).append(" 個頻道）\n");
            for (ShadowGraduationResult r : results) {
                String statusEmoji = switch (r.getStatus()) {
                    case READY -> "✅";
                    case APPROACHING -> "🟡";
                    case NOT_READY -> "⏳";
                };
                String displayName = r.getDisplayName() != null ? r.getDisplayName() : r.getName();
                String pfDisplay = r.getPaperProfitFactor() == 0 ? "N/A" : String.format("%.2f", r.getPaperProfitFactor());
                sb.append(String.format("%s %s | %d筆 | 勝率%.1f%% | PF:%s | 連敗:%d | %s USDT\n",
                        statusEmoji, displayName,
                        r.getPaperTradeCount(), r.getPaperWinRate(),
                        pfDisplay, r.getPaperMaxConsecutiveLosses(),
                        formatProfit(r.getPaperTotalPnl())));
            }

            // 有達標頻道時提示
            List<String> readyNames = results.stream()
                    .filter(r -> r.getStatus() == ShadowGraduationResult.GraduationStatus.READY)
                    .map(r -> r.getDisplayName() != null ? r.getDisplayName() : r.getName())
                    .collect(Collectors.toList());
            if (!readyNames.isEmpty()) {
                sb.append("💡 ").append(String.join("、", readyNames)).append(" 已達畢業門檻，建議評估轉為 AUTO\n");
            }
        } catch (Exception e) {
            log.warn("SHADOW 畢業評估產生失敗: {}", e.getMessage());
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
