package com.trader.dashboard.service;

import com.trader.dashboard.dto.DashboardOverview;
import com.trader.dashboard.dto.FunnelStatsResponse;
import com.trader.dashboard.dto.PerformanceStats;
import com.trader.dashboard.dto.TradeHistoryResponse;
import com.trader.shared.config.AppConstants;
import com.trader.shared.config.RiskConfig;
import com.trader.subscription.dto.SubscriptionStatusResponse;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.StartOfDayBalanceCache;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.repository.TradeRepository;
import com.trader.trading.service.TradeRecordService;
import com.trader.referral.entity.ReferralStatus;
import com.trader.referral.repository.UserExchangeReferralLinkRepository;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import com.trader.user.service.UserDiscordWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Dashboard 聚合服務
 *
 * 整合 trading + subscription + binance 模組的資料，
 * 提供前端 Dashboard 需要的各種摘要和統計。
 *
 * 差異化價值（幣安看不到的）：
 * - 訊號來源績效排名
 * - 風控預算即時狀態
 * - 出場原因分布
 * - 盈虧曲線 + 回撤疊加
 * - 幣種 / 多空 / 時間分組
 * - DCA 補倉效果分析
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final TradeRecordService tradeRecordService;
    private final SubscriptionService subscriptionService;
    private final BinanceFuturesService binanceFuturesService;
    private final RiskConfig riskConfig;
    private final UserRepository userRepository;
    private final TradeConfigResolver tradeConfigResolver;
    private final MultiUserConfig multiUserConfig;
    private final UserApiKeyService userApiKeyService;
    private final UserDiscordWebhookService userDiscordWebhookService;
    private final StartOfDayBalanceCache startOfDayBalanceCache;
    private final TradeRepository tradeRepository;
    private final UserExchangeReferralLinkRepository referralLinkRepository;
    private final SubscriptionRepository subscriptionRepository;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy-MM");

    // ==================== Overview ====================

    /**
     * 取得首頁摘要（帳戶、風控、訂閱、持倉、自動跟單狀態）
     */
    @Transactional(readOnly = true)
    public DashboardOverview getOverview(String userId) {
        var userOpt = userRepository.findById(userId);
        boolean autoTradeEnabled = userOpt.map(u -> u.isAutoTradeEnabled()).orElse(false);
        boolean discordNotificationEnabled = userOpt.map(u -> u.isDiscordNotificationEnabled()).orElse(true);
        boolean hasBinanceApiKey = userApiKeyService.getUserIdsWithApiKey("BINANCE").contains(userId);
        boolean hasDiscordWebhook = userDiscordWebhookService.getPrimaryWebhook(userId).isPresent();

        // 一次取得餘額，避免重複呼叫 Binance API（account + riskBudget 共用）
        double balance = 0;
        try {
            balance = fetchBalanceWithUserKeys(userId);
        } catch (Exception e) {
            log.error("用戶 {} 取得餘額失敗: {}", userId, e.getMessage(), e);
        }

        List<DashboardOverview.OpenPositionSummary> positions = buildPositionList(userId);

        // 計算保證金統計
        double totalMarginUsed = positions.stream()
                .filter(p -> p.getMarginUsed() != null)
                .mapToDouble(DashboardOverview.OpenPositionSummary::getMarginUsed)
                .sum();
        double marginRatio = balance > 0 ? round2(totalMarginUsed / balance * 100) : 0;

        return DashboardOverview.builder()
                .account(buildAccountSummary(userId, balance, totalMarginUsed, marginRatio))
                .riskBudget(buildRiskBudget(userId, balance))
                .subscription(buildSubscriptionInfo(userId))
                .autoTradeEnabled(autoTradeEnabled)
                .discordNotificationEnabled(discordNotificationEnabled)
                .hasBinanceApiKey(hasBinanceApiKey)
                .hasDiscordWebhook(hasDiscordWebhook)
                .positions(positions)
                .build();
    }

    private DashboardOverview.AccountSummary buildAccountSummary(
            String userId, double cachedBalance, double totalMarginUsed, double marginRatio) {
        Map<String, Object> todayStats = tradeRecordService.getTodayStats(userId);
        long todayTrades = (long) todayStats.get("trades");
        double todayPnl = (double) todayStats.get("netProfit");
        List<Trade> openTrades = tradeRecordService.findAllOpenTrades(userId);

        return DashboardOverview.AccountSummary.builder()
                .availableBalance(round2(cachedBalance))
                .openPositionCount(openTrades.size())
                .todayPnl(round2(todayPnl))
                .todayTradeCount((int) todayTrades)
                .totalMarginUsed(round2(totalMarginUsed))
                .marginRatio(marginRatio)
                .build();
    }

    /**
     * 取得帳戶餘額（多用戶模式下使用 per-user API Key）
     * 單用戶模式直接使用全局 API Key，行為不變。
     */
    private double fetchBalanceWithUserKeys(String userId) {
        if (multiUserConfig.isEnabled()) {
            Optional<BinanceKeys> keysOpt = userApiKeyService.getUserBinanceKeys(userId);
            if (keysOpt.isEmpty()) {
                log.warn("用戶 {} 未設定 Binance API Key，無法查詢帳戶餘額", userId);
                return 0;
            }
            String apiKeyPrefix = keysOpt.get().apiKey().substring(0, 8);
            log.debug("用戶 {} 查詢帳戶餘額（per-user key, prefix={}...）", userId, apiKeyPrefix);
            BinanceFuturesService.setCurrentUserKeys(keysOpt.get());
            try {
                double balance = binanceFuturesService.getAvailableBalance();
                log.debug("用戶 {} 餘額查詢成功: {} USDT", userId, balance);
                return balance;
            } finally {
                BinanceFuturesService.clearCurrentUserKeys();
            }
        }
        // 單用戶模式 → 直接使用全局 API Key
        return binanceFuturesService.getAvailableBalance();
    }

    private DashboardOverview.RiskBudget buildRiskBudget(String userId, double cachedBalance) {
        try {
            EffectiveTradeConfig config = tradeConfigResolver.resolve(userId);
            final double fetchedBalance = cachedBalance;
            double sodBalance = startOfDayBalanceCache.getOrCompute(userId, () -> fetchedBalance);
            double dailyLimit = config.effectiveDailyLossLimit(sodBalance);
            double todayLoss = tradeRecordService.getTodayRealizedLoss(userId); // 負數
            double lossUsed = Math.abs(todayLoss);
            double remaining = Math.max(0, dailyLimit - lossUsed);

            return DashboardOverview.RiskBudget.builder()
                    .dailyLossLimit(round2(dailyLimit))
                    .todayLossUsed(round2(lossUsed))
                    .remainingBudget(round2(remaining))
                    .circuitBreakerActive(lossUsed >= dailyLimit)
                    .build();
        } catch (Exception e) {
            log.warn("風控預算建構失敗: {}", e.getMessage());
            return DashboardOverview.RiskBudget.builder()
                    .dailyLossLimit(0)
                    .todayLossUsed(0)
                    .remainingBudget(0)
                    .circuitBreakerActive(false)
                    .build();
        }
    }

    private DashboardOverview.SubscriptionInfo buildSubscriptionInfo(String userId) {
        try {
            SubscriptionStatusResponse status = subscriptionService.getStatus(userId);
            return DashboardOverview.SubscriptionInfo.builder()
                    .plan(status.getPlanId() != null ? status.getPlanId() : "none")
                    .active(status.isActive())
                    .expiresAt(status.getCurrentPeriodEnd() != null
                            ? status.getCurrentPeriodEnd().toString() : null)
                    .build();
        } catch (Exception e) {
            log.warn("取得訂閱狀態失敗: {}", e.getMessage());
            return DashboardOverview.SubscriptionInfo.builder()
                    .plan("none").active(false).build();
        }
    }

    private List<DashboardOverview.OpenPositionSummary> buildPositionList(String userId) {
        List<Trade> openTrades = tradeRecordService.findAllOpenTrades(userId);

        // 嘗試取得 Binance 即時持倉數據（graceful degradation）
        Map<String, JsonObject> livePositions = fetchLivePositions(userId);

        return openTrades.stream()
                .map(t -> {
                    var builder = DashboardOverview.OpenPositionSummary.builder()
                            .symbol(t.getSymbol())
                            .side(t.getSide())
                            .entryPrice(t.getEntryPrice() != null ? t.getEntryPrice() : 0)
                            .stopLoss(t.getStopLoss())
                            .riskAmount(t.getRiskAmount())
                            .dcaCount(t.getDcaCount())
                            .signalSource(t.getSourceAuthorName())
                            .entryTime(t.getEntryTime() != null ? t.getEntryTime().toString() : null)
                            .aiConfidence(t.getAiConfidence())
                            .aiReasoning(t.getAiReasoning())
                            .entryQuantity(t.getEntryQuantity());

                    // 即時數據 enrichment
                    JsonObject pos = livePositions.get(t.getSymbol());
                    if (pos != null) {
                        double markPrice = pos.has("markPrice") ? pos.get("markPrice").getAsDouble() : 0;
                        double unrealizedPnl = pos.has("unRealizedProfit") ? pos.get("unRealizedProfit").getAsDouble() : 0;
                        double isolatedMargin = pos.has("isolatedMargin") ? pos.get("isolatedMargin").getAsDouble() : 0;
                        builder.markPrice(round2(markPrice))
                                .unrealizedPnl(round2(unrealizedPnl))
                                .marginUsed(round2(isolatedMargin));
                    }

                    // 持倉價值 = 入場價 × 入場數量
                    if (t.getEntryPrice() != null && t.getEntryQuantity() != null) {
                        builder.positionValue(round2(t.getEntryPrice() * t.getEntryQuantity()));
                    }

                    return builder.build();
                })
                .toList();
    }

    /**
     * 取得 Binance 即時持倉數據，建立 symbol → positionRisk 的映射
     * 失敗時回傳空 Map（graceful degradation）
     */
    private Map<String, JsonObject> fetchLivePositions(String userId) {
        try {
            String positionsJson;
            if (multiUserConfig.isEnabled()) {
                Optional<BinanceKeys> keysOpt = userApiKeyService.getUserBinanceKeys(userId);
                if (keysOpt.isEmpty()) return Map.of();
                BinanceFuturesService.setCurrentUserKeys(keysOpt.get());
                try {
                    positionsJson = binanceFuturesService.getPositions();
                } finally {
                    BinanceFuturesService.clearCurrentUserKeys();
                }
            } else {
                positionsJson = binanceFuturesService.getPositions();
            }

            if (positionsJson == null || positionsJson.isBlank()) return Map.of();

            JsonArray arr = JsonParser.parseString(positionsJson).getAsJsonArray();
            Map<String, JsonObject> result = new HashMap<>();
            for (JsonElement el : arr) {
                JsonObject obj = el.getAsJsonObject();
                double posAmt = obj.has("positionAmt") ? obj.get("positionAmt").getAsDouble() : 0;
                if (posAmt != 0) {
                    String symbol = obj.get("symbol").getAsString();
                    result.put(symbol, obj);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("取得即時持倉數據失敗（fallback 無即時數據）: {}", e.getMessage());
            return Map.of();
        }
    }

    // ==================== Performance ====================

    /**
     * 取得績效統計（勝率、PF、訊號來源排名、盈虧曲線、進階分析）
     */
    @Transactional(readOnly = true)
    public PerformanceStats getPerformance(String userId, int days) {
        LocalDateTime since = LocalDate.now(AppConstants.ZONE_ID).minusDays(days).atStartOfDay();

        List<Trade> closedTrades = tradeRecordService.findAll(userId).stream()
                .filter(t -> "CLOSED".equals(t.getStatus()))
                .filter(t -> t.getExitTime() != null && t.getExitTime().isAfter(since))
                .toList();

        return PerformanceStats.builder()
                .summary(buildSummary(closedTrades))
                .exitReasonBreakdown(closedTrades.stream()
                        .filter(t -> t.getExitReason() != null)
                        .collect(Collectors.groupingBy(Trade::getExitReason, Collectors.counting())))
                .signalSourceRanking(buildSignalSourceRanking(closedTrades))
                .pnlCurve(buildPnlCurve(closedTrades))
                // === 進階分析 ===
                .symbolStats(buildSymbolStats(closedTrades))
                .sideComparison(buildSideComparison(closedTrades))
                .weeklyStats(buildWeeklyStats(closedTrades))
                .monthlyStats(buildMonthlyStats(closedTrades))
                .dayOfWeekStats(buildDayOfWeekStats(closedTrades))
                .dcaAnalysis(buildDcaAnalysis(closedTrades))
                .build();
    }

    // ==================== Summary（含進階指標） ====================

    private PerformanceStats.Summary buildSummary(List<Trade> closedTrades) {
        long total = closedTrades.size();
        long wins = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
        long losses = total - wins;
        double winRate = total > 0 ? (double) wins / total * 100 : 0;

        double totalNet = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null)
                .mapToDouble(Trade::getNetProfit).sum();
        double grossWins = closedTrades.stream()
                .filter(t -> t.getGrossProfit() != null && t.getGrossProfit() > 0)
                .mapToDouble(Trade::getGrossProfit).sum();
        double grossLosses = closedTrades.stream()
                .filter(t -> t.getGrossProfit() != null && t.getGrossProfit() < 0)
                .mapToDouble(t -> Math.abs(t.getGrossProfit())).sum();
        double pf = grossLosses > 0 ? grossWins / grossLosses : 0;
        double totalCommission = closedTrades.stream()
                .filter(t -> t.getCommission() != null)
                .mapToDouble(Trade::getCommission).sum();
        double avgProfit = total > 0 ? totalNet / total : 0;
        double maxWin = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null)
                .mapToDouble(Trade::getNetProfit).max().orElse(0);
        double maxLoss = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null)
                .mapToDouble(Trade::getNetProfit).min().orElse(0);

        // === 進階指標 ===

        // 平均獲利 / 平均虧損
        double avgWin = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0)
                .mapToDouble(Trade::getNetProfit).average().orElse(0);
        double avgLoss = closedTrades.stream()
                .filter(t -> t.getNetProfit() != null && t.getNetProfit() <= 0)
                .mapToDouble(Trade::getNetProfit).average().orElse(0);

        // 風報比 = |avgWin| / |avgLoss|
        double riskRewardRatio = avgLoss != 0 ? Math.abs(avgWin / avgLoss) : 0;

        // 期望值 = (winPct × avgWin) - (lossPct × |avgLoss|)
        double winPct = total > 0 ? (double) wins / total : 0;
        double lossPct = total > 0 ? (double) losses / total : 0;
        double expectancy = (winPct * avgWin) - (lossPct * Math.abs(avgLoss));

        // 最大連勝 / 連敗
        int[] streaks = calculateStreaks(closedTrades);

        // 最大回撤
        double[] drawdownResult = calculateMaxDrawdown(closedTrades);

        // 平均持倉時間（小時）
        double avgHoldingHours = closedTrades.stream()
                .filter(t -> t.getEntryTime() != null && t.getExitTime() != null)
                .mapToLong(t -> Duration.between(t.getEntryTime(), t.getExitTime()).toMinutes())
                .average().orElse(0) / 60.0;

        return PerformanceStats.Summary.builder()
                .totalTrades(total).winningTrades(wins).losingTrades(losses)
                .winRate(round2(winRate)).profitFactor(round2(pf))
                .totalNetProfit(round2(totalNet)).avgProfitPerTrade(round2(avgProfit))
                .totalCommission(round2(totalCommission))
                .maxWin(round2(maxWin)).maxLoss(round2(maxLoss))
                .avgWin(round2(avgWin)).avgLoss(round2(avgLoss))
                .riskRewardRatio(round2(riskRewardRatio))
                .expectancy(round2(expectancy))
                .maxConsecutiveWins(streaks[0]).maxConsecutiveLosses(streaks[1])
                .maxDrawdown(round2(drawdownResult[0]))
                .maxDrawdownPercent(round2(drawdownResult[1]))
                .maxDrawdownDays((int) drawdownResult[2])
                .avgHoldingHours(round2(avgHoldingHours))
                .build();
    }

    /**
     * 計算最大連勝和最大連敗
     * @return [maxConsecutiveWins, maxConsecutiveLosses]
     */
    private int[] calculateStreaks(List<Trade> closedTrades) {
        List<Trade> sorted = closedTrades.stream()
                .filter(t -> t.getExitTime() != null)
                .sorted(Comparator.comparing(Trade::getExitTime))
                .toList();

        int maxWins = 0, maxLosses = 0, currentWins = 0, currentLosses = 0;
        for (Trade t : sorted) {
            if (t.getNetProfit() != null && t.getNetProfit() > 0) {
                currentWins++;
                currentLosses = 0;
                maxWins = Math.max(maxWins, currentWins);
            } else {
                currentLosses++;
                currentWins = 0;
                maxLosses = Math.max(maxLosses, currentLosses);
            }
        }
        return new int[]{maxWins, maxLosses};
    }

    /**
     * 計算最大回撤 (金額、百分比、天數)
     * 遍歷按 exitTime 排序的交易，累計 equity curve，追蹤 peak-to-trough。
     *
     * @return [maxDrawdownUsdt, maxDrawdownPercent, maxDrawdownDays]
     */
    private double[] calculateMaxDrawdown(List<Trade> closedTrades) {
        List<Trade> sorted = closedTrades.stream()
                .filter(t -> t.getExitTime() != null && t.getNetProfit() != null)
                .sorted(Comparator.comparing(Trade::getExitTime))
                .toList();

        if (sorted.isEmpty()) return new double[]{0, 0, 0};

        double cumPnl = 0;
        double peak = 0;
        double maxDd = 0;
        double maxDdPercent = 0;
        LocalDate peakDate = sorted.get(0).getExitTime().toLocalDate();
        LocalDate troughDate = peakDate;
        int maxDdDays = 0;

        for (Trade t : sorted) {
            cumPnl += t.getNetProfit();
            if (cumPnl > peak) {
                peak = cumPnl;
                peakDate = t.getExitTime().toLocalDate();
            }
            double dd = cumPnl - peak; // 負數或零
            if (dd < maxDd) {
                maxDd = dd;
                maxDdPercent = peak > 0 ? (dd / peak) * 100 : 0;
                troughDate = t.getExitTime().toLocalDate();
                maxDdDays = (int) ChronoUnit.DAYS.between(peakDate, troughDate);
            }
        }
        return new double[]{maxDd, maxDdPercent, maxDdDays};
    }

    // ==================== 訊號來源排名 ====================

    private List<PerformanceStats.SignalSourceStats> buildSignalSourceRanking(List<Trade> closedTrades) {
        Map<String, List<Trade>> bySource = closedTrades.stream()
                .filter(t -> t.getSourceAuthorName() != null && !t.getSourceAuthorName().isBlank())
                .collect(Collectors.groupingBy(Trade::getSourceAuthorName));

        return bySource.entrySet().stream()
                .map(e -> {
                    List<Trade> trades = e.getValue();
                    long count = trades.size();
                    long wins = trades.stream()
                            .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
                    double netProfit = trades.stream()
                            .filter(t -> t.getNetProfit() != null)
                            .mapToDouble(Trade::getNetProfit).sum();
                    return PerformanceStats.SignalSourceStats.builder()
                            .source(e.getKey()).trades(count)
                            .winRate(round2(count > 0 ? (double) wins / count * 100 : 0))
                            .netProfit(round2(netProfit))
                            .build();
                })
                .sorted(Comparator.comparingDouble(PerformanceStats.SignalSourceStats::getNetProfit).reversed())
                .toList();
    }

    // ==================== 盈虧曲線（含回撤） ====================

    private List<PerformanceStats.PnlDataPoint> buildPnlCurve(List<Trade> closedTrades) {
        Map<LocalDate, Double> dailyPnl = new TreeMap<>();
        for (Trade t : closedTrades) {
            if (t.getExitTime() == null || t.getNetProfit() == null) continue;
            dailyPnl.merge(t.getExitTime().toLocalDate(), t.getNetProfit(), Double::sum);
        }

        List<PerformanceStats.PnlDataPoint> curve = new ArrayList<>();
        double cumulative = 0;
        double peak = 0;
        for (Map.Entry<LocalDate, Double> entry : dailyPnl.entrySet()) {
            cumulative += entry.getValue();
            if (cumulative > peak) peak = cumulative;
            double dd = cumulative - peak;
            double ddPercent = peak > 0 ? (dd / peak) * 100 : 0;

            curve.add(PerformanceStats.PnlDataPoint.builder()
                    .date(entry.getKey().format(DATE_FMT))
                    .dailyPnl(round2(entry.getValue()))
                    .cumulativePnl(round2(cumulative))
                    .drawdown(round2(dd))
                    .drawdownPercent(round2(ddPercent))
                    .build());
        }
        return curve;
    }

    // ==================== 幣種別績效 ====================

    private List<PerformanceStats.SymbolStats> buildSymbolStats(List<Trade> closedTrades) {
        Map<String, List<Trade>> bySymbol = closedTrades.stream()
                .filter(t -> t.getSymbol() != null)
                .collect(Collectors.groupingBy(Trade::getSymbol));

        return bySymbol.entrySet().stream()
                .map(e -> {
                    List<Trade> trades = e.getValue();
                    long count = trades.size();
                    long wins = trades.stream()
                            .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
                    double netProfit = trades.stream()
                            .filter(t -> t.getNetProfit() != null)
                            .mapToDouble(Trade::getNetProfit).sum();
                    return PerformanceStats.SymbolStats.builder()
                            .symbol(e.getKey())
                            .trades(count).wins(wins)
                            .winRate(round2(count > 0 ? (double) wins / count * 100 : 0))
                            .netProfit(round2(netProfit))
                            .avgProfit(round2(count > 0 ? netProfit / count : 0))
                            .build();
                })
                .sorted(Comparator.comparingDouble(PerformanceStats.SymbolStats::getNetProfit).reversed())
                .toList();
    }

    // ==================== 多空對比 ====================

    private PerformanceStats.SideComparison buildSideComparison(List<Trade> closedTrades) {
        return PerformanceStats.SideComparison.builder()
                .longStats(buildSideStats(closedTrades, "LONG"))
                .shortStats(buildSideStats(closedTrades, "SHORT"))
                .build();
    }

    private PerformanceStats.SideStats buildSideStats(List<Trade> closedTrades, String side) {
        List<Trade> filtered = closedTrades.stream()
                .filter(t -> side.equals(t.getSide()))
                .toList();

        long count = filtered.size();
        long wins = filtered.stream()
                .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
        double netProfit = filtered.stream()
                .filter(t -> t.getNetProfit() != null)
                .mapToDouble(Trade::getNetProfit).sum();
        double grossWins = filtered.stream()
                .filter(t -> t.getGrossProfit() != null && t.getGrossProfit() > 0)
                .mapToDouble(Trade::getGrossProfit).sum();
        double grossLosses = filtered.stream()
                .filter(t -> t.getGrossProfit() != null && t.getGrossProfit() < 0)
                .mapToDouble(t -> Math.abs(t.getGrossProfit())).sum();

        return PerformanceStats.SideStats.builder()
                .trades(count).wins(wins)
                .winRate(round2(count > 0 ? (double) wins / count * 100 : 0))
                .netProfit(round2(netProfit))
                .avgProfit(round2(count > 0 ? netProfit / count : 0))
                .profitFactor(round2(grossLosses > 0 ? grossWins / grossLosses : 0))
                .build();
    }

    // ==================== 週統計 ====================

    private List<PerformanceStats.WeeklyStats> buildWeeklyStats(List<Trade> closedTrades) {
        WeekFields weekFields = WeekFields.ISO;

        Map<String, List<Trade>> byWeek = closedTrades.stream()
                .filter(t -> t.getExitTime() != null)
                .collect(Collectors.groupingBy(t -> {
                    LocalDate date = t.getExitTime().toLocalDate();
                    int year = date.get(weekFields.weekBasedYear());
                    int week = date.get(weekFields.weekOfWeekBasedYear());
                    return String.format("%d-W%02d", year, week);
                }, TreeMap::new, Collectors.toList()));

        return byWeek.entrySet().stream()
                .map(e -> {
                    List<Trade> trades = e.getValue();
                    long count = trades.size();
                    long wins = trades.stream()
                            .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
                    double netProfit = trades.stream()
                            .filter(t -> t.getNetProfit() != null)
                            .mapToDouble(Trade::getNetProfit).sum();
                    LocalDate earliest = trades.stream()
                            .map(t -> t.getExitTime().toLocalDate())
                            .min(Comparator.naturalOrder()).orElse(LocalDate.now(AppConstants.ZONE_ID));
                    LocalDate latest = trades.stream()
                            .map(t -> t.getExitTime().toLocalDate())
                            .max(Comparator.naturalOrder()).orElse(LocalDate.now(AppConstants.ZONE_ID));

                    return PerformanceStats.WeeklyStats.builder()
                            .weekStart(earliest.format(DATE_FMT))
                            .weekEnd(latest.format(DATE_FMT))
                            .trades(count)
                            .netProfit(round2(netProfit))
                            .winRate(round2(count > 0 ? (double) wins / count * 100 : 0))
                            .build();
                })
                .toList();
    }

    // ==================== 月統計 ====================

    private List<PerformanceStats.MonthlyStats> buildMonthlyStats(List<Trade> closedTrades) {
        Map<String, List<Trade>> byMonth = closedTrades.stream()
                .filter(t -> t.getExitTime() != null)
                .collect(Collectors.groupingBy(
                        t -> t.getExitTime().toLocalDate().format(MONTH_FMT),
                        TreeMap::new, Collectors.toList()));

        return byMonth.entrySet().stream()
                .map(e -> {
                    List<Trade> trades = e.getValue();
                    long count = trades.size();
                    long wins = trades.stream()
                            .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
                    double netProfit = trades.stream()
                            .filter(t -> t.getNetProfit() != null)
                            .mapToDouble(Trade::getNetProfit).sum();
                    return PerformanceStats.MonthlyStats.builder()
                            .month(e.getKey())
                            .trades(count)
                            .netProfit(round2(netProfit))
                            .winRate(round2(count > 0 ? (double) wins / count * 100 : 0))
                            .build();
                })
                .toList();
    }

    // ==================== 星期幾績效 ====================

    private List<PerformanceStats.DayOfWeekStats> buildDayOfWeekStats(List<Trade> closedTrades) {
        Map<DayOfWeek, List<Trade>> byDay = closedTrades.stream()
                .filter(t -> t.getExitTime() != null)
                .collect(Collectors.groupingBy(t -> t.getExitTime().getDayOfWeek()));

        return Arrays.stream(DayOfWeek.values())
                .map(day -> {
                    List<Trade> trades = byDay.getOrDefault(day, List.of());
                    long count = trades.size();
                    long wins = trades.stream()
                            .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
                    double netProfit = trades.stream()
                            .filter(t -> t.getNetProfit() != null)
                            .mapToDouble(Trade::getNetProfit).sum();
                    return PerformanceStats.DayOfWeekStats.builder()
                            .dayOfWeek(day.name())
                            .trades(count)
                            .netProfit(round2(netProfit))
                            .winRate(round2(count > 0 ? (double) wins / count * 100 : 0))
                            .build();
                })
                .toList();
    }

    // ==================== DCA 補倉分析 ====================

    private PerformanceStats.DcaAnalysis buildDcaAnalysis(List<Trade> closedTrades) {
        Map<Boolean, List<Trade>> partitioned = closedTrades.stream()
                .collect(Collectors.partitioningBy(t -> t.getDcaCount() != null && t.getDcaCount() > 0));

        List<Trade> dcaTrades = partitioned.get(true);
        List<Trade> noDcaTrades = partitioned.get(false);

        return PerformanceStats.DcaAnalysis.builder()
                .noDcaTrades(noDcaTrades.size())
                .noDcaWinRate(round2(calcWinRate(noDcaTrades)))
                .noDcaAvgProfit(round2(calcAvgProfit(noDcaTrades)))
                .dcaTrades(dcaTrades.size())
                .dcaWinRate(round2(calcWinRate(dcaTrades)))
                .dcaAvgProfit(round2(calcAvgProfit(dcaTrades)))
                .build();
    }

    // ==================== Trade History ====================

    /**
     * 取得交易歷史（分頁）
     */
    @Transactional(readOnly = true)
    public TradeHistoryResponse getTradeHistory(String userId, int page, int size) {
        List<Trade> allClosed = tradeRecordService.findByStatus("CLOSED", userId);

        long totalElements = allClosed.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);
        int fromIndex = Math.min(page * size, allClosed.size());
        int toIndex = Math.min(fromIndex + size, allClosed.size());
        List<Trade> pageContent = allClosed.subList(fromIndex, toIndex);

        List<TradeHistoryResponse.TradeRecord> records = pageContent.stream()
                .map(t -> TradeHistoryResponse.TradeRecord.builder()
                        .tradeId(t.getTradeId())
                        .symbol(t.getSymbol()).side(t.getSide())
                        .entryPrice(t.getEntryPrice()).exitPrice(t.getExitPrice())
                        .entryQuantity(t.getEntryQuantity())
                        .netProfit(t.getNetProfit()).exitReason(t.getExitReason())
                        .signalSource(t.getSourceAuthorName())
                        .dcaCount(t.getDcaCount())
                        .entryTime(t.getEntryTime() != null ? t.getEntryTime().toString() : null)
                        .exitTime(t.getExitTime() != null ? t.getExitTime().toString() : null)
                        .status(t.getStatus())
                        // 手續費明細
                        .grossProfit(t.getGrossProfit())
                        .entryCommission(t.getEntryCommission())
                        .exitCommission(t.getCommission() != null && t.getEntryCommission() != null
                                ? round2(t.getCommission() - t.getEntryCommission()) : null)
                        .totalCommission(t.getCommission())
                        .leverage(t.getLeverage())
                        // AI 訊號評分
                        .aiConfidence(t.getAiConfidence())
                        .aiReasoning(t.getAiReasoning())
                        .build())
                .toList();

        return TradeHistoryResponse.builder()
                .trades(records)
                .pagination(TradeHistoryResponse.Pagination.builder()
                        .page(page).size(size)
                        .totalPages(totalPages).totalElements(totalElements)
                        .build())
                .build();
    }

    // ==================== Utility ====================

    private double calcWinRate(List<Trade> trades) {
        if (trades.isEmpty()) return 0;
        long wins = trades.stream()
                .filter(t -> t.getNetProfit() != null && t.getNetProfit() > 0).count();
        return (double) wins / trades.size() * 100;
    }

    private double calcAvgProfit(List<Trade> trades) {
        if (trades.isEmpty()) return 0;
        return trades.stream()
                .filter(t -> t.getNetProfit() != null)
                .mapToDouble(Trade::getNetProfit).average().orElse(0);
    }

    /**
     * 輕量用戶交易統計（僅查 DB，不呼叫 Binance API）
     * 供單一用戶查詢使用（保留向後相容）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getLightweightUserStats(String userId) {
        Map<String, Object> todayStats = tradeRecordService.getTodayStats(userId);
        List<Trade> openTrades = tradeRecordService.findAllOpenTrades(userId);
        Map<String, Object> summary = tradeRecordService.getStatsSummary(userId);

        return Map.of(
                "openPositionCount", openTrades.size(),
                "closedTradeCount", summary.get("closedTrades"),
                "totalNetProfit", summary.get("totalNetProfit"),
                "todayPnl", todayStats.get("netProfit"),
                "todayTradeCount", todayStats.get("trades")
        );
    }

    /**
     * 批次取得所有用戶的輕量交易統計（解決 system-overview N+1 問題）
     *
     * 面試重點：
     *   原本 for-each user → getLightweightUserStats() → 每人 ~10 次 DB 查詢（N+1）
     *   改為 2 次 GROUP BY 批次聚合查詢，取代 N * 10 次查詢。
     *   100 用戶：1000+ 次查詢 → 2 次查詢。
     *
     * @return Map<userId, stats>，stats 包含 openPositionCount, closedTradeCount, totalNetProfit, todayPnl, todayTradeCount
     */
    @Transactional(readOnly = true)
    public Map<String, Map<String, Object>> getBatchLightweightUserStats() {
        Map<String, Map<String, Object>> result = new HashMap<>();

        // 1. 批次聚合：已平倉統計 + OPEN 持倉數（1 query for all users）
        tradeRecordService.getTradeRepository().aggregateStatsPerUser().forEach(row -> {
            String userId = (String) row[0];
            Map<String, Object> stats = new HashMap<>();
            stats.put("openPositionCount", ((Number) row[4]).intValue());
            stats.put("closedTradeCount", ((Number) row[1]).longValue());
            stats.put("totalNetProfit", ((Number) row[3]).doubleValue());
            stats.put("todayPnl", 0.0);
            stats.put("todayTradeCount", 0L);
            stats.put("weekPnl", 0.0);
            stats.put("monthPnl", 0.0);
            result.put(userId, stats);
        });

        // 2. 批次聚合：今日統計（1 query for all users）
        LocalDateTime startOfToday = LocalDate.now(AppConstants.ZONE_ID).atStartOfDay();
        tradeRecordService.getTradeRepository().aggregateTodayStatsPerUser(startOfToday).forEach(row -> {
            String userId = (String) row[0];
            Map<String, Object> stats = result.get(userId);
            if (stats != null) {
                stats.put("todayTradeCount", ((Number) row[1]).longValue());
                stats.put("todayPnl", ((Number) row[2]).doubleValue());
            } else {
                // 用戶只有今日交易、沒有歷史交易的情況（理論上不會，但防禦性處理）
                Map<String, Object> newStats = new HashMap<>();
                newStats.put("openPositionCount", 0);
                newStats.put("closedTradeCount", 0L);
                newStats.put("totalNetProfit", 0.0);
                newStats.put("todayPnl", ((Number) row[2]).doubleValue());
                newStats.put("todayTradeCount", ((Number) row[1]).longValue());
                newStats.put("weekPnl", 0.0);
                newStats.put("monthPnl", 0.0);
                result.put(userId, newStats);
            }
        });

        // 3. 批次聚合：本周統計（1 query for all users）
        LocalDateTime startOfWeek = LocalDate.now(AppConstants.ZONE_ID)
                .with(DayOfWeek.MONDAY).atStartOfDay();
        tradeRecordService.getTradeRepository().aggregateStatsPerUserSince(startOfWeek).forEach(row -> {
            String userId = (String) row[0];
            Map<String, Object> stats = result.get(userId);
            if (stats != null) {
                stats.put("weekPnl", ((Number) row[2]).doubleValue());
            }
        });

        // 4. 批次聚合：本月統計（1 query for all users）
        LocalDateTime startOfMonth = LocalDate.now(AppConstants.ZONE_ID)
                .withDayOfMonth(1).atStartOfDay();
        tradeRecordService.getTradeRepository().aggregateStatsPerUserSince(startOfMonth).forEach(row -> {
            String userId = (String) row[0];
            Map<String, Object> stats = result.get(userId);
            if (stats != null) {
                stats.put("monthPnl", ((Number) row[2]).doubleValue());
            }
        });

        // 5. API Key 查詢：複用已有方法（1 query for all users）
        Set<String> userIdsWithApiKey = userApiKeyService.getUserIdsWithApiKey("BINANCE");
        result.forEach((userId, stats) -> stats.put("hasBinanceApiKey", userIdsWithApiKey.contains(userId)));

        // 6. lastTradeAt + consecutiveLosses（1 JPQL query → Java 計算）
        Map<String, LocalDateTime> lastTradeMap = new HashMap<>();
        Map<String, Integer> consecutiveLossMap = new HashMap<>();
        String currentUserId = null;
        int currentStreak = 0;
        boolean streakBroken = false;

        for (Object[] row : tradeRecordService.getTradeRepository().findRecentClosedTradesAllUsers()) {
            String uid = (String) row[0];
            LocalDateTime exitTime = (LocalDateTime) row[1];
            double netProfit = row[2] != null ? ((Number) row[2]).doubleValue() : 0.0;

            // 換用戶 → 儲存前一個用戶的結果
            if (!uid.equals(currentUserId)) {
                if (currentUserId != null) {
                    consecutiveLossMap.put(currentUserId, currentStreak);
                }
                currentUserId = uid;
                currentStreak = 0;
                streakBroken = false;
                lastTradeMap.put(uid, exitTime); // 已按 exitTime DESC 排序，第一筆就是最新
            }

            // 連續虧損：從最近一筆往回數，遇到盈利就停
            if (!streakBroken) {
                if (netProfit < 0) {
                    currentStreak++;
                } else {
                    streakBroken = true;
                }
            }
        }
        // 最後一個用戶
        if (currentUserId != null) {
            consecutiveLossMap.put(currentUserId, currentStreak);
        }

        result.forEach((userId, stats) -> {
            stats.put("lastTradeAt", lastTradeMap.get(userId));
            stats.put("consecutiveLosses", consecutiveLossMap.getOrDefault(userId, 0));
        });

        // 7. circuitBreakerActive — 簡化版（用 maxDailyLossUsdt 絕對上限比較，不需 Binance API）
        result.forEach((userId, stats) -> {
            double todayPnl = (double) stats.get("todayPnl");
            boolean cbActive = false;
            if (todayPnl < 0) {
                try {
                    EffectiveTradeConfig config = tradeConfigResolver.resolve(userId);
                    double limit = config.maxDailyLossUsdt();
                    if (limit > 0 && Math.abs(todayPnl) >= limit) {
                        cbActive = true;
                    }
                } catch (Exception e) {
                    // 無法解析 config → 忽略，不影響 overview
                }
            }
            stats.put("circuitBreakerActive", cbActive);
        });

        return result;
    }

    // ── Funnel Stats ──

    /**
     * 用戶漏斗統計 — 6 階段 + 註冊趨勢 + 最近註冊列表
     *
     * 階段判斷（由高到低）：
     * subscribed → traded → api_key_set → referral_verified → email_verified → registered
     */
    @Transactional(readOnly = true)
    public FunnelStatsResponse getFunnelStats() {
        long totalUsers = userRepository.count();
        long emailVerified = userRepository.countByEmailVerifiedTrue();
        long referralVerified = referralLinkRepository.countByStatus(ReferralStatus.VERIFIED);
        Set<String> userIdsWithApiKey = userApiKeyService.getUserIdsWithApiKey("BINANCE");
        long hasApiKey = userIdsWithApiKey.size();
        long hasTraded = tradeRepository.countDistinctUserIdsWithClosedTrades();
        long activeSubscription = subscriptionRepository.countActiveSubscriptions();

        // 註冊趨勢（最近 90 天）
        LocalDateTime since = LocalDateTime.now(ZoneId.of("Asia/Taipei")).minusDays(90);
        List<Object[]> regRows = userRepository.countRegistrationsByDate(since);
        List<FunnelStatsResponse.DateCount> registrationsByDate = regRows.stream()
                .map(row -> FunnelStatsResponse.DateCount.builder()
                        .date(row[0].toString())
                        .count(((Number) row[1]).longValue())
                        .build())
                .toList();

        // 最近 10 筆註冊 + stage 判斷
        Set<String> activeSubUserIds = new HashSet<>(subscriptionRepository.findUserIdsWithActiveSubscription());
        List<User> recentUsers = userRepository.findTop10ByOrderByCreatedAtDesc();
        List<FunnelStatsResponse.RecentUser> recentUserList = recentUsers.stream()
                .map(user -> {
                    String stage = determineUserStage(user, userIdsWithApiKey, activeSubUserIds);
                    return FunnelStatsResponse.RecentUser.builder()
                            .userId(user.getUserId())
                            .name(user.getName())
                            .email(user.getEmail())
                            .createdAt(user.getCreatedAt() != null
                                    ? user.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                                    : null)
                            .stage(stage)
                            .build();
                })
                .toList();

        return FunnelStatsResponse.builder()
                .totalUsers((int) totalUsers)
                .emailVerified((int) emailVerified)
                .referralVerified((int) referralVerified)
                .hasApiKey((int) hasApiKey)
                .hasTraded((int) hasTraded)
                .activeSubscription((int) activeSubscription)
                .registrationsByDate(registrationsByDate)
                .recentUsers(recentUserList)
                .build();
    }

    /**
     * 判斷用戶目前的漏斗階段（由高到低）
     */
    private String determineUserStage(User user, Set<String> userIdsWithApiKey,
                                       Set<String> activeSubUserIds) {
        String userId = user.getUserId();

        if (activeSubUserIds.contains(userId)) return "subscribed";

        long closedCount = tradeRepository.countByUserIdAndStatus(userId, "CLOSED");
        if (closedCount > 0) return "traded";

        if (userIdsWithApiKey.contains(userId)) return "api_key_set";

        boolean referralOk = referralLinkRepository
                .existsByUserIdAndExchangeAndStatus(userId, "BINANCE", ReferralStatus.VERIFIED);
        if (referralOk) return "referral_verified";

        if (user.isEmailVerified()) return "email_verified";

        return "registered";
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
