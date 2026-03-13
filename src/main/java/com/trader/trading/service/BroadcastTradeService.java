package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.dto.SignalScore;
import com.trader.advisor.service.SignalScoringService;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.NotificationService;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeRequest;
import com.trader.trading.entity.BroadcastLog;
import com.trader.trading.entity.SignalSourceConfig;
import com.trader.trading.model.TradeContext;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.user.service.UserApiKeyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.trader.papertrade.service.BinancePriceClient;
import com.trader.papertrade.service.PaperTradeService;
import com.trader.shared.config.AppConstants;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 廣播跟單服務 (共享線程池版本)
 * - 查詢所有啟用自動跟單且已設定 API Key 的用戶（推薦碼為軟提醒，不阻擋跟單）
 * - 用共享線程池（core=10, max=50）並行執行，不排隊
 */
@Slf4j
@Service
public class BroadcastTradeService {

    private final UserRepository userRepository;
    private final BinanceFuturesService binanceFuturesService;
    private final NotificationService discordWebhookService;
    private final UserApiKeyService userApiKeyService;
    private final SubscriptionRepository subscriptionRepository;
    private final SignalScoringService signalScoringService;
    private final SignalSourceService signalSourceService;
    private final TradeRepository tradeRepository;
    private final BroadcastLogRepository broadcastLogRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService broadcastExecutor;
    private final PaperTradeService paperTradeService;
    private final BinancePriceClient binancePriceClient;
    private final int batchSize;
    private final long batchDelayMs;

    private static final long TASK_TIMEOUT_SECONDS = 30;

    /** 結構化用戶結果（供持久化用） */
    record UserResultData(String userId, String email, boolean success, String errorMessage) {}

    public BroadcastTradeService(
            UserRepository userRepository,
            BinanceFuturesService binanceFuturesService,
            NotificationService discordWebhookService,
            UserApiKeyService userApiKeyService,
            SubscriptionRepository subscriptionRepository,
            SignalScoringService signalScoringService,
            SignalSourceService signalSourceService,
            TradeRepository tradeRepository,
            BroadcastLogRepository broadcastLogRepository,
            ObjectMapper objectMapper,
            @Qualifier("broadcastExecutor") ExecutorService broadcastExecutor,
            PaperTradeService paperTradeService,
            BinancePriceClient binancePriceClient,
            @Value("${broadcast.executor.batch-size:15}") int batchSize,
            @Value("${broadcast.executor.batch-delay-ms:200}") long batchDelayMs) {
        this.userRepository = userRepository;
        this.binanceFuturesService = binanceFuturesService;
        this.discordWebhookService = discordWebhookService;
        this.userApiKeyService = userApiKeyService;
        this.subscriptionRepository = subscriptionRepository;
        this.signalScoringService = signalScoringService;
        this.signalSourceService = signalSourceService;
        this.tradeRepository = tradeRepository;
        this.broadcastLogRepository = broadcastLogRepository;
        this.objectMapper = objectMapper;
        this.broadcastExecutor = broadcastExecutor;
        this.paperTradeService = paperTradeService;
        this.binancePriceClient = binancePriceClient;
        this.batchSize = batchSize;
        this.batchDelayMs = batchDelayMs;
    }

    /**
     * 廣播跟單給所有啟用的用戶
     * - 只向 autoTradeEnabled=true 的用戶廣播
     * - 用 Thread Pool 並行處理
     *
     * @param request 跟單請求
     * @return 執行結果統計
     */
    public Map<String, Object> broadcastTrade(TradeRequest request) {
        // 啟動 AI 信號評分（非同步，不阻塞任何交易流程）
        LocalDateTime broadcastStartTime = LocalDateTime.now(AppConstants.ZONE_ID);
        CompletableFuture<SignalScore> scoreFuture = signalScoringService.scoreAsync(request);

        // 一次查詢所有用戶，按角色分流：Admin 只收通知不下單
        List<User> allUsers = userRepository.findAll();

        List<User> adminUsers = allUsers.stream()
                .filter(User::isEnabled)
                .filter(user -> user.getRole() == User.Role.ADMIN)
                .toList();

        List<User> enabledUsers = allUsers.stream()
                .filter(User::isAutoTradeEnabled)
                .filter(User::isEnabled)
                .filter(user -> user.getRole() != User.Role.ADMIN)
                .toList();

        // Batch 查詢：一次取得所有有效訂閱的 userId（避免 N+1）
        Set<String> subscribedUserIds = new HashSet<>(subscriptionRepository.findUserIdsWithActiveSubscription());

        // 過濾：僅保留有有效訂閱 (ACTIVE/LIFETIME) 的用戶
        List<User> subscribedUsers = enabledUsers.stream()
                .filter(u -> subscribedUserIds.contains(u.getUserId()))
                .toList();

        int skippedNoSubscription = enabledUsers.size() - subscribedUsers.size();
        if (skippedNoSubscription > 0) {
            log.warn("廣播跟單: 跳過 {} 個用戶 (無有效訂閱)", skippedNoSubscription);
        }

        // Batch 查詢：一次取得所有已設定 API Key 的 userId（避免 N+1）
        Set<String> userIdsWithApiKey = userApiKeyService.getUserIdsWithApiKey("BINANCE");

        // 過濾：已設定 API Key
        List<User> activeUsers = subscribedUsers.stream()
                .filter(u -> userIdsWithApiKey.contains(u.getUserId()))
                .toList();

        int skippedNoApiKey = subscribedUsers.size() - activeUsers.size();
        if (skippedNoApiKey > 0) {
            log.warn("廣播跟單: 跳過 {} 個用戶 (無 API Key)", skippedNoApiKey);
        }

        // 訊號來源路由：按來源綁定過濾用戶
        // 防禦性設計：如果有 targetUserIds（緊急廣播），跳過來源路由（targetUserIds 優先）
        int skippedNotAssigned = 0;
        Long resolvedSourceId = null;
        boolean hasTargetUsers = request.getTargetUserIds() != null && !request.getTargetUserIds().isEmpty();

        SignalSourceConfig.TradeMode resolvedTradeMode = null;
        SignalSourceConfig resolvedSourceConfig = null;

        if (!hasTargetUsers && request.getSource() != null && request.getSource().getChannelId() != null) {
            String channelId = request.getSource().getChannelId();
            String guildId = request.getSource().getGuildId();

            Optional<SignalSourceConfig> resolvedSource = signalSourceService.resolveSource(channelId, guildId);
            resolvedSourceConfig = resolvedSource.orElse(null);
            resolvedSourceId = resolvedSource.map(SignalSourceConfig::getId).orElse(null);
            resolvedTradeMode = resolvedSource.map(SignalSourceConfig::getTradeMode).orElse(null);

            Optional<Set<String>> sourceUserIds = signalSourceService.resolveTargetUserIds(channelId, guildId);

            if (sourceUserIds.isPresent()) {
                // ASSIGNED 模式 → 只保留綁定用戶
                Set<String> assigned = sourceUserIds.get();
                int beforeSize = activeUsers.size();
                activeUsers = activeUsers.stream()
                        .filter(u -> assigned.contains(u.getUserId()))
                        .toList();
                skippedNotAssigned = beforeSize - activeUsers.size();
                if (skippedNotAssigned > 0) {
                    log.info("ASSIGNED 來源路由: channelId={} 符合 {} 人, 排除 {} 人",
                            channelId, activeUsers.size(), skippedNotAssigned);
                }
            } else if (resolvedSourceId != null) {
                // GLOBAL 模式 → 排除已綁定 ASSIGNED 來源的用戶（一人一源原則）
                Set<String> boundUserIds = signalSourceService.getUserIdsBoundToAssignedSources();
                if (!boundUserIds.isEmpty()) {
                    int beforeSize = activeUsers.size();
                    activeUsers = activeUsers.stream()
                            .filter(u -> !boundUserIds.contains(u.getUserId()))
                            .toList();
                    skippedNotAssigned = beforeSize - activeUsers.size();
                    if (skippedNotAssigned > 0) {
                        log.info("GLOBAL 來源路由: 排除 {} 個已綁定 ASSIGNED 來源的用戶, 剩餘 {} 人",
                                skippedNotAssigned, activeUsers.size());
                    }
                }
            }
            // resolvedSourceId == null → 無匹配來源 → 全量廣播（向下相容）
        }

        // trade_mode 控制：MANUAL → 跳過廣播（僅通知）；SHADOW → 記錄但不交易
        if (resolvedTradeMode == SignalSourceConfig.TradeMode.MANUAL) {
            log.info("MANUAL 模式: 來源 sourceId={} 跳過廣播，僅記錄", resolvedSourceId);
            saveBroadcastLog(request, 0, 0, 0,
                    skippedNoSubscription, skippedNoApiKey, skippedNotAssigned,
                    resolvedSourceId, "MANUAL_SKIPPED", null,
                    new ConcurrentLinkedQueue<>(), broadcastStartTime);
            Map<String, Object> manualResult = new HashMap<>();
            manualResult.put("status", "MANUAL_SKIPPED");
            manualResult.put("tradeMode", "MANUAL");
            manualResult.put("sourceId", resolvedSourceId);
            manualResult.put("message", "此訊號來源為手動模式，已記錄但未執行廣播");
            return manualResult;
        }

        // 指定用戶模式：從已通過篩選的 activeUsers 中，再過濾出目標用戶
        int skippedNotTargeted = 0;
        if (request.getTargetUserIds() != null && !request.getTargetUserIds().isEmpty()) {
            Set<String> targets = new HashSet<>(request.getTargetUserIds());
            int beforeSize = activeUsers.size();
            activeUsers = activeUsers.stream()
                    .filter(u -> targets.contains(u.getUserId()))
                    .toList();
            skippedNotTargeted = beforeSize - activeUsers.size();
            log.info("指定用戶模式: 目標 {} 人, 符合條件 {} 人, 排除 {} 人",
                    targets.size(), activeUsers.size(), skippedNotTargeted);
        }

        log.info("廣播跟單: 找到 {} 個有效用戶 (跳過無訂閱={}, 跳過無API Key={}, 未綁定來源={}, 非指定用戶={}), action={} symbol={}",
                activeUsers.size(), skippedNoSubscription, skippedNoApiKey, skippedNotAssigned, skippedNotTargeted, request.getAction(), request.getSymbol());

        // 廣播前 — 發訊號詳情通知給每位 Admin（per-user webhook）
        String signalDetail = formatBroadcastSignalForAdmin(request, activeUsers.size(), skippedNoSubscription, skippedNoApiKey);
        for (User admin : adminUsers) {
            discordWebhookService.sendNotificationToUser(
                    admin.getUserId(),
                    "📡 廣播訊號已發送",
                    signalDetail,
                    DiscordWebhookService.COLOR_BLUE);
        }

        if (activeUsers.isEmpty()) {
            String message;
            if (enabledUsers.isEmpty()) {
                message = "無啟用用戶";
            } else if (skippedNoSubscription > 0 && skippedNoApiKey == 0) {
                message = "所有用戶均無有效訂閱";
            } else if (skippedNoApiKey > 0 && skippedNoSubscription == 0) {
                message = "所有用戶均未設定 API Key";
            } else {
                message = "所有用戶均未符合條件 (訂閱/API Key)";
            }
            saveBroadcastLog(request, 0, 0, 0,
                    skippedNoSubscription, skippedNoApiKey, skippedNotAssigned,
                    resolvedSourceId, "COMPLETED", null,
                    new ConcurrentLinkedQueue<>(), broadcastStartTime);
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("status", "COMPLETED");
            emptyResult.put("totalUsers", 0);
            emptyResult.put("successCount", 0);
            emptyResult.put("failCount", 0);
            emptyResult.put("skippedNoSubscription", skippedNoSubscription);
            emptyResult.put("skippedNoApiKey", skippedNoApiKey);
            emptyResult.put("skippedNotAssigned", skippedNotAssigned);
            emptyResult.put("skippedNotTargeted", skippedNotTargeted);
            emptyResult.put("message", message);
            return emptyResult;
        }

        // SHADOW 模式 → 記錄廣播日誌但不執行 Binance 交易
        if (resolvedTradeMode == SignalSourceConfig.TradeMode.SHADOW) {
            log.info("SHADOW 模式: 來源 sourceId={} 記錄訊號但不執行交易, 符合用戶={}", resolvedSourceId, activeUsers.size());

            // 等待 AI 評分結果（SHADOW 不執行交易，可等較久）
            SignalScore shadowScore = null;
            try {
                shadowScore = scoreFuture.get(6_000, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.debug("SHADOW AI 評分未及時完成，跳過");
            } catch (ExecutionException e) {
                log.warn("SHADOW AI 評分執行失敗: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            saveBroadcastLog(request, activeUsers.size(), 0, 0,
                    skippedNoSubscription, skippedNoApiKey, skippedNotAssigned,
                    resolvedSourceId, "SHADOW_RECORDED", shadowScore,
                    new ConcurrentLinkedQueue<>(), broadcastStartTime);

            // 模擬交易（Paper Trading）— 若該來源啟用了模擬交易
            if (resolvedSourceConfig != null && resolvedSourceConfig.isPaperTradingEnabled()) {
                try {
                    String action = request.getAction();
                    String srcChannelId = request.getSource() != null ? request.getSource().getChannelId() : null;
                    if ("ENTRY".equalsIgnoreCase(action)) {
                        paperTradeService.createPaperTrade(request, shadowScore);
                    } else if ("CLOSE".equalsIgnoreCase(action) && srcChannelId != null) {
                        double markPrice = binancePriceClient.getMarkPrice(request.getSymbol());
                        paperTradeService.closePaperTrade(request.getSymbol(), srcChannelId, markPrice, "SIGNAL_CLOSE");
                    } else if ("MOVE_SL".equalsIgnoreCase(action) && srcChannelId != null) {
                        paperTradeService.movePaperStopLoss(request.getSymbol(), srcChannelId,
                                request.getNewStopLoss(), request.getNewTakeProfit());
                    }
                } catch (Exception e) {
                    log.warn("SHADOW 模擬交易處理失敗（不影響主流程）: {}", e.getMessage());
                }
            }

            // 通知 Admin 影子模式記錄
            for (User admin : adminUsers) {
                discordWebhookService.sendNotificationToUser(
                        admin.getUserId(),
                        "👻 影子模式訊號已記錄",
                        String.format("來源 sourceId=%d | %s %s | 符合 %d 人（未實際交易）",
                                resolvedSourceId, request.getAction(), request.getSymbol(), activeUsers.size()),
                        DiscordWebhookService.COLOR_YELLOW);
            }
            Map<String, Object> shadowResult = new HashMap<>();
            shadowResult.put("status", "SHADOW_RECORDED");
            shadowResult.put("tradeMode", "SHADOW");
            shadowResult.put("sourceId", resolvedSourceId);
            shadowResult.put("totalEligibleUsers", activeUsers.size());
            shadowResult.put("message", "影子模式：訊號已記錄但未執行交易");
            return shadowResult;
        }

        // 用共享線程池並行執行（不排隊，全員同時下單）
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        // Thread-safe 收集明細（成交限 10 筆、失敗限 5 筆，避免訊息過長）
        ConcurrentLinkedQueue<String> successDetails = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> failDetails = new ConcurrentLinkedQueue<>();
        // 結構化結果收集（供廣播紀錄持久化）
        ConcurrentLinkedQueue<UserResultData> userResultsLog = new ConcurrentLinkedQueue<>();
        int maxSuccessDetails = 10;
        int maxFailDetails = 5;
        // CLOSE 專用：收集 PnL 做彙總
        boolean isCloseAction = "CLOSE".equalsIgnoreCase(request.getAction());
        DoubleAdder totalPnl = new DoubleAdder();
        AtomicInteger pnlCount = new AtomicInteger(0);

        // 為每個用戶建立 Callable 任務
        List<Callable<Void>> tasks = new ArrayList<>();
        for (User user : activeUsers) {
            tasks.add(() -> {
                String userDisplay = formatUserDisplay(user);
                TradeContext ctx = TradeContext.forBroadcast(user.getUserId(), userDisplay);
                ctx.installThreadLocals();
                try {
                    List<OrderResult> results = binanceFuturesService.executeSignalForBroadcast(request, ctx.userId());
                    successCount.incrementAndGet();
                    userResultsLog.add(new UserResultData(user.getUserId(), user.getEmail(), true, null));
                    log.debug("跟單成功: userId={}", user.getUserId());

                    // 找到主要成交結果
                    OrderResult mainResult = (results != null && !results.isEmpty())
                            ? results.stream()
                                .filter(r -> r.isSuccess() && r.getOrderId() != null)
                                .findFirst().orElse(results.get(0))
                            : null;

                    // 非阻塞檢查：AI 分數是否已就緒？（不等待，避免延遲交易通知）
                    // 分數主要用於 Admin 報告 + DB 記錄（line 251-273），用戶通知有就顯示、沒有就跳過
                    SignalScore score = scoreFuture.getNow(null);

                    // 發送 enriched 成功通知給用戶（含實際成交價/PnL/AI 評分）
                    String successTitle;
                    String actionUpper = request.getAction() != null ? request.getAction().toUpperCase() : "";
                    if (isCloseAction) {
                        boolean isPartial = request.getCloseRatio() != null && request.getCloseRatio() < 1.0;
                        successTitle = isPartial
                                ? String.format("✅ 部分平倉已執行 (%.0f%%)", request.getCloseRatio() * 100)
                                : "✅ 全部平倉已執行";
                    } else if ("MOVE_SL".equals(actionUpper)) {
                        successTitle = "✅ 移動止損已執行";
                    } else if ("CANCEL".equals(actionUpper)) {
                        successTitle = "✅ 取消掛單已執行";
                    } else {
                        successTitle = "✅ 廣播跟單已執行";
                    }
                    discordWebhookService.sendNotificationToUser(
                            user.getUserId(),
                            successTitle,
                            formatUserResultBody(request, mainResult, userDisplay, score),
                            DiscordWebhookService.COLOR_GREEN);

                    // 收集成交明細供 Admin 報告（限 maxSuccessDetails 筆）
                    if (mainResult != null && successDetails.size() < maxSuccessDetails) {
                        successDetails.add(formatAdminDetailLine(request, mainResult, userDisplay));
                    }

                    // CLOSE: 收集 PnL 做彙總
                    if (isCloseAction && mainResult != null && mainResult.getNetProfit() != null) {
                        totalPnl.add(mainResult.getNetProfit());
                        pnlCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    userResultsLog.add(new UserResultData(user.getUserId(), user.getEmail(), false, e.getMessage()));
                    log.error("跟單失敗: userId={} error={}", user.getUserId(), e.getMessage());

                    // 收集失敗明細（限 maxFailDetails 筆，CAS-free — size 可能略超，可接受）
                    if (failDetails.size() < maxFailDetails) {
                        String reason = e.getMessage();
                        failDetails.add(userDisplay + ": " + (reason != null ? reason : "unknown error"));
                    }

                    // 發送失敗通知給用戶
                    discordWebhookService.sendNotificationToUser(
                            user.getUserId(),
                            "❌ 廣播跟單失敗",
                            String.format("%s\n用戶: %s\n錯誤: %s",
                                    request.getSymbol(),
                                    userDisplay,
                                    e.getMessage()),
                            DiscordWebhookService.COLOR_RED);
                } finally {
                    // 防禦性清除 ThreadLocal — 防止線程池複用時殘留上一用戶的 context
                    TradeContext.clearThreadLocals();
                }
                return null;
            });
        }

        try {
            // 分批派發：避免瞬間打爆 Binance API rate limit（2400 次/分鐘）
            long totalCancelledCount = 0;
            int totalBatches = (tasks.size() + batchSize - 1) / batchSize;

            for (int i = 0; i < tasks.size(); i += batchSize) {
                List<Callable<Void>> batch = tasks.subList(i, Math.min(i + batchSize, tasks.size()));
                int batchNum = (i / batchSize) + 1;
                log.debug("廣播跟單: 執行批次 {}/{} ({} 個用戶)", batchNum, totalBatches, batch.size());

                List<Future<Void>> futures = broadcastExecutor.invokeAll(batch, TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                long cancelledInBatch = futures.stream().filter(Future::isCancelled).count();
                if (cancelledInBatch > 0) {
                    log.warn("廣播跟單: 批次 {}/{} 有 {} 個任務超時", batchNum, totalBatches, cancelledInBatch);
                }
                totalCancelledCount += cancelledInBatch;

                // 非最後一批 → 延遲，讓 Binance API 計數器有時間回復
                if (i + batchSize < tasks.size() && batchDelayMs > 0) {
                    Thread.sleep(batchDelayMs);
                }
            }

            log.info("廣播跟單完成: 成功={} 失敗={} 超時取消={} 批次數={}",
                    successCount.get(), failCount.get(), totalCancelledCount, totalBatches);

            // 動態等待 AI 評分：Gemini 總可用時間 = 交易執行耗時 + 剩餘等待，上限 6 秒
            // Gemini Flash 模型回應此類短 prompt 通常 2-3 秒，6 秒綽綽有餘
            // 交易執行快（1-2秒）時多等一下，交易慢（5秒+）時 Gemini 幾乎必定已完成
            SignalScore finalScore = null;
            try {
                long elapsedMs = Duration.between(broadcastStartTime, LocalDateTime.now(AppConstants.ZONE_ID)).toMillis();
                long remainingMs = Math.max(500, 6_000 - elapsedMs); // 最少等 500ms
                finalScore = scoreFuture.get(remainingMs, TimeUnit.MILLISECONDS);
                log.debug("AI 評分取得成功，總耗時 {}ms（等待 {}ms）", elapsedMs + remainingMs, remainingMs);
            } catch (TimeoutException e) {
                long totalMs = Duration.between(broadcastStartTime, LocalDateTime.now(AppConstants.ZONE_ID)).toMillis();
                log.debug("AI 評分未及時完成（已等 {}ms），跳過", totalMs);
            } catch (ExecutionException e) {
                log.warn("AI 評分執行失敗: {}", e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
            }

            // 批次更新此次廣播建立的所有 Trade 記錄
            if (finalScore != null) {
                try {
                    int updated = tradeRepository.updateAiScore(
                            request.getSymbol(),
                            finalScore.getConfidence(),
                            finalScore.getReasoning(),
                            broadcastStartTime);
                    log.info("AI 評分已寫入 {} 筆 Trade 記錄 (confidence={})", updated, finalScore.getConfidence());
                } catch (Exception e) {
                    log.warn("AI 評分寫入 DB 失敗: {}", e.getMessage());
                }
            }

            // 廣播完成 — 發彙總報告給每位 Admin（per-user webhook）
            StringBuilder summaryBuilder = new StringBuilder(
                    String.format("%s %s\n成功: %d 人\n失敗: %d 人\n超時: %d 人\n跳過 (無訂閱): %d 人\n跳過 (無 API Key): %d 人\n總計: %d 人",
                    request.getSymbol(), request.getAction(),
                    successCount.get(), failCount.get(), totalCancelledCount,
                    skippedNoSubscription, skippedNoApiKey, activeUsers.size()));
            if (skippedNotAssigned > 0) {
                summaryBuilder.append(String.format("\n未綁定來源: %d 人", skippedNotAssigned));
            }
            if (skippedNotTargeted > 0) {
                summaryBuilder.append(String.format("\n非指定用戶: %d 人", skippedNotTargeted));
            }

            // CLOSE: 附上 PnL 彙總（總損益 + 平均）
            if (isCloseAction && pnlCount.get() > 0) {
                double pnlTotal = totalPnl.sum();
                double pnlAvg = pnlTotal / pnlCount.get();
                summaryBuilder.append(String.format("\n\n總損益: %+.2f USDT\n平均: %+.2f USDT",
                        pnlTotal, pnlAvg));
            }

            // AI 評分（如有）
            if (finalScore != null) {
                summaryBuilder.append(String.format("\n\n🤖 AI 評分: %d/100 %s %s\n%s",
                        finalScore.getConfidence(),
                        finalScore.getRiskEmoji(),
                        finalScore.getRiskLevelDisplay(),
                        finalScore.getReasoning()));
            }

            // 附上成交明細（最多 10 筆）
            if (!successDetails.isEmpty()) {
                summaryBuilder.append(isCloseAction ? "\n\n平倉明細:" : "\n\n成交明細:");
                for (String detail : successDetails) {
                    summaryBuilder.append("\n- ").append(detail);
                }
                int totalSuccess = successCount.get();
                if (totalSuccess > successDetails.size()) {
                    summaryBuilder.append(String.format("\n...及其他 %d 人", totalSuccess - successDetails.size()));
                }
            }

            // 附上失敗明細（最多 5 筆）
            if (!failDetails.isEmpty()) {
                summaryBuilder.append("\n\n失敗明細:");
                for (String detail : failDetails) {
                    summaryBuilder.append("\n- ").append(detail);
                }
                int totalFails = failCount.get();
                if (totalFails > failDetails.size()) {
                    summaryBuilder.append(String.format("\n...及其他 %d 人", totalFails - failDetails.size()));
                }
            }

            String summary = summaryBuilder.toString();
            String summaryTitle = isCloseAction ? "📊 廣播平倉報告" : "📊 廣播跟單報告";
            int summaryColor = failCount.get() > 0 || totalCancelledCount > 0
                    ? DiscordWebhookService.COLOR_YELLOW
                    : DiscordWebhookService.COLOR_GREEN;
            for (User admin : adminUsers) {
                discordWebhookService.sendNotificationToUser(
                        admin.getUserId(),
                        summaryTitle,
                        summary,
                        summaryColor);
            }

            // 持久化廣播紀錄（save 失敗不影響交易主流程）
            saveBroadcastLog(request, activeUsers.size(), successCount.get(), failCount.get(),
                    skippedNoSubscription, skippedNoApiKey, skippedNotAssigned,
                    resolvedSourceId, "COMPLETED", finalScore,
                    userResultsLog, broadcastStartTime);

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "COMPLETED");
            resultMap.put("totalUsers", activeUsers.size());
            resultMap.put("successCount", successCount.get());
            resultMap.put("failCount", failCount.get());
            resultMap.put("skippedNoSubscription", skippedNoSubscription);
            resultMap.put("skippedNoApiKey", skippedNoApiKey);
            resultMap.put("skippedNotAssigned", skippedNotAssigned);
            resultMap.put("skippedNotTargeted", skippedNotTargeted);
            return resultMap;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("廣播跟單中斷: {}", e.getMessage());
            return Map.of(
                    "status", "INTERRUPTED",
                    "error", e.getMessage());
        }
    }

    /**
     * 格式化用戶顯示名稱：name (email)
     * name 為空時 fallback 到 email
     */
    private String formatUserDisplay(User user) {
        String name = user.getName();
        String email = user.getEmail();
        if (name != null && !name.isBlank()) {
            return name + " (" + email + ")";
        }
        return email;
    }

    /**
     * 格式化用戶成交通知（enriched，含實際成交價/PnL）
     */
    private String formatUserResultBody(TradeRequest request, OrderResult result, String userDisplay, SignalScore score) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.getSymbol());

        String action = request.getAction() != null ? request.getAction().toUpperCase() : "";
        switch (action) {
            case "ENTRY" -> {
                if (request.getSide() != null) sb.append(" ").append(request.getSide());
                sb.append("\n");
                if (result != null && result.getPrice() > 0) {
                    sb.append("成交: ").append(result.getPrice()).append("\n");
                    if (result.getQuantity() > 0) {
                        sb.append("數量: ").append(result.getQuantity()).append("\n");
                    }
                    if (result.getCommission() > 0) {
                        sb.append("手續費: ").append(String.format("%.4f", result.getCommission())).append(" USDT\n");
                    }
                } else {
                    // fallback：無成交結果時顯示請求價
                    sb.append("入場: ").append(request.getEntryPrice()).append("\n");
                }
            }
            case "CLOSE" -> {
                sb.append("\n");
                // 顯示平倉比例，讓用戶區分部分平倉 vs 全部平倉
                boolean isPartial = request.getCloseRatio() != null && request.getCloseRatio() < 1.0;
                sb.append("類型: ").append(isPartial
                        ? String.format("部分平倉 (%.0f%%)", request.getCloseRatio() * 100)
                        : "全部平倉").append("\n");
                if (result != null) {
                    if (result.getPrice() > 0) {
                        sb.append("成交: ").append(result.getPrice()).append("\n");
                    }
                    if (result.getNetProfit() != null) {
                        sb.append("已實現損益: ").append(String.format("%+.2f", result.getNetProfit())).append(" USDT\n");
                    }
                    if (result.getTotalCommission() != null) {
                        sb.append("手續費: ").append(String.format("%.4f", result.getTotalCommission())).append(" USDT\n");
                    }
                }
            }
            case "MOVE_SL" -> {
                sb.append("\n動作: 移動止損\n");
                if (request.getNewStopLoss() != null) sb.append("新止損: ").append(request.getNewStopLoss()).append("\n");
                if (request.getNewTakeProfit() != null) sb.append("新止盈: ").append(request.getNewTakeProfit()).append("\n");
            }
            case "CANCEL" -> sb.append("\n已取消所有掛單\n");
            default -> sb.append("\n");
        }

        sb.append("用戶: ").append(userDisplay);

        // AI 評分（若已就緒）
        if (score != null) {
            sb.append(String.format("\n🤖 AI: %d/100 %s %s — %s",
                    score.getConfidence(),
                    score.getRiskEmoji(),
                    score.getRiskLevelDisplay(),
                    score.getReasoning()));
        }

        return sb.toString();
    }

    /**
     * 格式化 Admin 報告的單行明細
     */
    private String formatAdminDetailLine(TradeRequest request, OrderResult result, String userDisplay) {
        String action = request.getAction() != null ? request.getAction().toUpperCase() : "";
        return switch (action) {
            case "ENTRY" -> {
                if (result.getPrice() > 0 && result.getQuantity() > 0) {
                    yield userDisplay + ": " + result.getPrice() + " × " + result.getQuantity();
                }
                yield userDisplay + ": 成功";
            }
            case "CLOSE" -> {
                if (result.getNetProfit() != null) {
                    yield userDisplay + ": " + String.format("%+.2f USDT", result.getNetProfit());
                }
                yield userDisplay + ": 成功";
            }
            default -> userDisplay + ": 成功";
        };
    }

    /**
     * 持久化廣播紀錄 — save 失敗只 log warning，不影響交易主流程
     */
    private void saveBroadcastLog(TradeRequest request, int totalUsers, int successCount, int failCount,
                                   int skippedNoSub, int skippedNoKey, int skippedNotAssigned,
                                   Long sourceId, String status, SignalScore score,
                                   ConcurrentLinkedQueue<UserResultData> userResults, LocalDateTime startTime) {
        try {
            String userResultsJson = null;
            if (!userResults.isEmpty()) {
                userResultsJson = objectMapper.writeValueAsString(new ArrayList<>(userResults));
            }

            long durationMs = Duration.between(startTime, LocalDateTime.now(AppConstants.ZONE_ID)).toMillis();

            BroadcastLog logEntry = BroadcastLog.builder()
                    .signalAction(request.getAction())
                    .symbol(request.getSymbol())
                    .side(request.getSide())
                    .entryPrice(request.getEntryPrice())
                    .stopLoss(request.getStopLoss())
                    .takeProfit(request.getTakeProfit())
                    .closeRatio(request.getCloseRatio())
                    .newStopLoss(request.getNewStopLoss())
                    .newTakeProfit(request.getNewTakeProfit())
                    .isDca(request.getIsDca())
                    .sourceAuthor(request.getSource() != null ? request.getSource().getAuthorName() : null)
                    .totalUsers(totalUsers)
                    .successCount(successCount)
                    .failCount(failCount)
                    .skippedNoSub(skippedNoSub)
                    .skippedNoKey(skippedNoKey)
                    .skippedNotAssigned(skippedNotAssigned)
                    .sourceId(sourceId)
                    .status(status)
                    .userResults(userResultsJson)
                    .aiConfidence(score != null ? score.getConfidence() : null)
                    .aiReasoning(score != null ? score.getReasoning() : null)
                    .durationMs(durationMs)
                    .build();

            broadcastLogRepository.save(logEntry);
            log.debug("廣播紀錄已儲存: id={} action={} symbol={}", logEntry.getId(), request.getAction(), request.getSymbol());
        } catch (Exception e) {
            log.warn("廣播紀錄儲存失敗（不影響交易主流程）: {}", e.getMessage());
        }
    }

    /**
     * 組裝廣播訊號詳情（發給 Admin 的 per-user webhook）
     * 包含：action、symbol、side、入場價、止損、止盈、來源、目標用戶數
     */
    private String formatBroadcastSignalForAdmin(TradeRequest request, int targetUserCount,
                                                   int skippedNoSubscription, int skippedNoApiKey) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.getSymbol());

        String action = request.getAction() != null ? request.getAction().toUpperCase() : "UNKNOWN";
        switch (action) {
            case "ENTRY" -> {
                if (request.getSide() != null) sb.append(" ").append(request.getSide());
                sb.append("\n");
                if (request.getEntryPrice() != null) {
                    sb.append("入場: ").append(request.getEntryPrice()).append("\n");
                }
                if (request.getStopLoss() != null) {
                    sb.append("止損: ").append(request.getStopLoss());
                }
                if (request.getTakeProfit() != null) {
                    sb.append(" | 止盈: ").append(request.getTakeProfit());
                }
                sb.append("\n");
                if (Boolean.TRUE.equals(request.getIsDca())) {
                    sb.append("類型: DCA 補倉\n");
                }
            }
            case "CLOSE" -> {
                sb.append("\n動作: 平倉\n");
                if (request.getCloseRatio() != null) {
                    sb.append("比例: ").append(String.format("%.0f%%", request.getCloseRatio() * 100)).append("\n");
                }
            }
            case "MOVE_SL" -> {
                sb.append("\n動作: 移動止損\n");
                if (request.getNewStopLoss() != null) {
                    sb.append("新止損: ").append(request.getNewStopLoss()).append("\n");
                }
                if (request.getNewTakeProfit() != null) {
                    sb.append("新止盈: ").append(request.getNewTakeProfit()).append("\n");
                }
            }
            case "CANCEL" -> {
                sb.append("\n動作: 取消掛單\n");
            }
            default -> {
                sb.append("\n動作: ").append(action).append("\n");
            }
        }

        if (request.getSource() != null) {
            sb.append("來源: ").append(request.getSource()).append("\n");
        }
        sb.append("目標用戶: ").append(targetUserCount).append(" 人");
        if (skippedNoSubscription > 0) {
            sb.append("\n跳過 (無訂閱): ").append(skippedNoSubscription).append(" 人");
        }
        if (skippedNoApiKey > 0) {
            sb.append("\n跳過 (無 API Key): ").append(skippedNoApiKey).append(" 人");
        }
        return sb.toString();
    }
}
