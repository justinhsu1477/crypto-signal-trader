package com.trader.trading.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.dto.SignalScore;
import com.trader.advisor.service.SignalScoringService;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.NotificationService;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeRequest;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.dto.EffectiveTradeConfig;
import com.trader.trading.entity.BroadcastLog;
import com.trader.trading.exchange.ExchangeAdapter;
import com.trader.trading.exchange.ExchangeAdapterFactory;
import com.trader.trading.exchange.ExchangeCredentials;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.ExchangeKeys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

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
    private final ExchangeAdapterFactory exchangeAdapterFactory;
    private final TradingOrchestrator orchestrator;
    private final TradeConfigResolver tradeConfigResolver;
    private final TradeRecordService tradeRecordService;
    private final SignalDeduplicationService deduplicationService;
    private final SymbolLockRegistry symbolLockRegistry;
    private final NotificationService discordWebhookService;
    private final UserApiKeyService userApiKeyService;
    private final SubscriptionRepository subscriptionRepository;
    private final SignalScoringService signalScoringService;
    private final TradeRepository tradeRepository;
    private final BroadcastLogRepository broadcastLogRepository;
    private final ObjectMapper objectMapper;
    private final ExecutorService broadcastExecutor;

    private static final long TASK_TIMEOUT_SECONDS = 30;

    /** 結構化用戶結果（供持久化用） */
    record UserResultData(String userId, String email, boolean success, String errorMessage) {}

    public BroadcastTradeService(
            UserRepository userRepository,
            ExchangeAdapterFactory exchangeAdapterFactory,
            TradingOrchestrator orchestrator,
            TradeConfigResolver tradeConfigResolver,
            TradeRecordService tradeRecordService,
            SignalDeduplicationService deduplicationService,
            SymbolLockRegistry symbolLockRegistry,
            NotificationService discordWebhookService,
            UserApiKeyService userApiKeyService,
            SubscriptionRepository subscriptionRepository,
            SignalScoringService signalScoringService,
            TradeRepository tradeRepository,
            BroadcastLogRepository broadcastLogRepository,
            ObjectMapper objectMapper,
            @Qualifier("broadcastExecutor") ExecutorService broadcastExecutor) {
        this.userRepository = userRepository;
        this.exchangeAdapterFactory = exchangeAdapterFactory;
        this.orchestrator = orchestrator;
        this.tradeConfigResolver = tradeConfigResolver;
        this.tradeRecordService = tradeRecordService;
        this.deduplicationService = deduplicationService;
        this.symbolLockRegistry = symbolLockRegistry;
        this.discordWebhookService = discordWebhookService;
        this.userApiKeyService = userApiKeyService;
        this.subscriptionRepository = subscriptionRepository;
        this.signalScoringService = signalScoringService;
        this.tradeRepository = tradeRepository;
        this.broadcastLogRepository = broadcastLogRepository;
        this.objectMapper = objectMapper;
        this.broadcastExecutor = broadcastExecutor;
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

        // Batch 查詢：取得所有用戶的交易所配對（避免 N+1，支援多交易所路由）
        Map<String, Set<String>> userExchangeMap = userApiKeyService.getUserExchangeMap();
        Set<String> userIdsWithApiKey = userExchangeMap.keySet();

        // 過濾：已設定 API Key
        List<User> activeUsers = subscribedUsers.stream()
                .filter(u -> userIdsWithApiKey.contains(u.getUserId()))
                .toList();

        int skippedNoApiKey = subscribedUsers.size() - activeUsers.size();
        if (skippedNoApiKey > 0) {
            log.warn("廣播跟單: 跳過 {} 個用戶 (無 API Key)", skippedNoApiKey);
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

        log.info("廣播跟單: 找到 {} 個有效用戶 (跳過無訂閱={}, 跳過無API Key={}, 非指定用戶={}), action={} symbol={}",
                activeUsers.size(), skippedNoSubscription, skippedNoApiKey, skippedNotTargeted, request.getAction(), request.getSymbol());

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
                    skippedNoSubscription, skippedNoApiKey, "COMPLETED", null,
                    new ConcurrentLinkedQueue<>(), broadcastStartTime);
            Map<String, Object> emptyResult = new HashMap<>();
            emptyResult.put("status", "COMPLETED");
            emptyResult.put("totalUsers", 0);
            emptyResult.put("successCount", 0);
            emptyResult.put("failCount", 0);
            emptyResult.put("skippedNoSubscription", skippedNoSubscription);
            emptyResult.put("skippedNoApiKey", skippedNoApiKey);
            emptyResult.put("skippedNotTargeted", skippedNotTargeted);
            emptyResult.put("message", message);
            return emptyResult;
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
                // 設入 ThreadLocal，讓 BinanceFuturesService.notifyGlobal() 也能讀到
                TradeRecordService.setCurrentUserDisplayName(userDisplay);
                try {
                    String exchange = resolveUserExchange(user.getUserId(), userExchangeMap);
                    ExchangeAdapter adapter = exchangeAdapterFactory.getAdapter(exchange);
                    List<OrderResult> results = executeSignalForUser(request, user.getUserId(), adapter, exchange);
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
                    TradeRecordService.clearCurrentUserDisplayName();
                }
                return null;
            });
        }

        try {
            // invokeAll：全部提交，等待全部完成（或超時）
            List<Future<Void>> futures = broadcastExecutor.invokeAll(tasks, TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 檢查是否有任務超時被取消
            long cancelledCount = futures.stream().filter(Future::isCancelled).count();
            if (cancelledCount > 0) {
                log.warn("廣播跟單: {} 個任務超時被取消", cancelledCount);
            }

            log.info("廣播跟單完成: 成功={} 失敗={} 超時取消={}",
                    successCount.get(), failCount.get(), cancelledCount);

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
                    successCount.get(), failCount.get(), cancelledCount,
                    skippedNoSubscription, skippedNoApiKey, activeUsers.size()));
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
            int summaryColor = failCount.get() > 0 || cancelledCount > 0
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
                    skippedNoSubscription, skippedNoApiKey, "COMPLETED", finalScore,
                    userResultsLog, broadcastStartTime);

            Map<String, Object> resultMap = new HashMap<>();
            resultMap.put("status", "COMPLETED");
            resultMap.put("totalUsers", activeUsers.size());
            resultMap.put("successCount", successCount.get());
            resultMap.put("failCount", failCount.get());
            resultMap.put("skippedNoSubscription", skippedNoSubscription);
            resultMap.put("skippedNoApiKey", skippedNoApiKey);
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

    // ==================== 多交易所路由 ====================

    /**
     * 決定用戶使用哪個交易所
     * 只有一個 → 用那個；多個 → 預設 BINANCE（Phase 5 加前端選擇器）
     */
    private String resolveUserExchange(String userId, Map<String, Set<String>> userExchangeMap) {
        Set<String> exchanges = userExchangeMap.getOrDefault(userId, Set.of());
        if (exchanges.isEmpty()) return "BINANCE";
        if (exchanges.size() == 1) return exchanges.iterator().next();
        return exchanges.contains("BINANCE") ? "BINANCE" : exchanges.iterator().next();
    }

    /**
     * 執行單個用戶的廣播跟單
     *
     * 從 BinanceFuturesService.executeSignalForBroadcast 搬移過來，
     * 通用化為支援任意交易所。
     *
     * 4 個 ThreadLocal：adapter.setCredentials / setCurrentUserId / setBroadcastContext / setCurrentUserDisplayName
     * 全部在 finally 中清除，防止線程池復用時洩漏。
     */
    private List<OrderResult> executeSignalForUser(TradeRequest request, String userId,
                                                    ExchangeAdapter adapter, String exchange) {
        log.info("廣播跟單執行: userId={} exchange={} action={} symbol={}",
                userId, exchange, request.getAction(), request.getSymbol());

        String action = request.getAction();
        if (action == null) {
            throw new IllegalArgumentException("action 不可為空");
        }

        String symbol = request.getSymbol();
        EffectiveTradeConfig broadcastConfig = tradeConfigResolver.resolve(userId);
        if (symbol == null || !broadcastConfig.isSymbolAllowed(symbol)) {
            throw new IllegalArgumentException("交易對不在白名單: " + symbol);
        }

        // 取得 per-user API Key — 未設定則拒絕執行
        var userKeysOpt = userApiKeyService.getUserExchangeKeys(userId, exchange);
        if (userKeysOpt.isEmpty()) {
            throw new IllegalStateException(
                    "用戶 " + userId + " 未設定 " + exchange + " API Key，無法執行廣播跟單");
        }

        // 設入 adapter credentials + ThreadLocal context
        ExchangeKeys keys = userKeysOpt.get();
        adapter.setCredentials(new ExchangeCredentials(keys.apiKey(), keys.secretKey()));
        TradeRecordService.setCurrentUserId(userId);
        TradingOrchestrator.setBroadcastContext(true);
        log.info("廣播跟單: userId={} 使用 per-user {} API Key", userId, exchange);

        List<OrderResult> broadcastResults = List.of();
        try {
            switch (action.toUpperCase()) {
                case "ENTRY" -> {
                    boolean isDca = request.getIsDca() != null && request.getIsDca();

                    if (!isDca && request.getSide() == null) {
                        throw new IllegalArgumentException("ENTRY 需要 side");
                    }
                    if (request.getEntryPrice() == null) {
                        throw new IllegalArgumentException("ENTRY 需要 entry_price");
                    }
                    if (request.getStopLoss() == null && !isDca) {
                        throw new IllegalArgumentException("ENTRY 必須包含 stop_loss");
                    }

                    if (isDca && request.getNewStopLoss() == null && request.getStopLoss() != null) {
                        request.setNewStopLoss(request.getStopLoss());
                    }

                    TradeSignal.TradeSignalBuilder builder = TradeSignal.builder()
                            .symbol(symbol)
                            .entryPriceLow(request.getEntryPrice())
                            .entryPriceHigh(request.getEntryPrice())
                            .signalType(TradeSignal.SignalType.ENTRY)
                            .isDca(isDca)
                            .newStopLoss(request.getNewStopLoss())
                            .newTakeProfit(request.getNewTakeProfit())
                            .source(request.getSource())
                            .exchange(exchange);

                    if (request.getSide() != null) {
                        builder.side(TradeSignal.Side.valueOf(request.getSide().toUpperCase()));
                    }
                    if (isDca) {
                        builder.stopLoss(request.getNewStopLoss() != null ? request.getNewStopLoss() : 0);
                    } else {
                        builder.stopLoss(request.getStopLoss());
                    }

                    TradeSignal signal = builder.build();
                    if (request.getTakeProfit() != null) {
                        signal.setTakeProfits(List.of(request.getTakeProfit()));
                    }

                    List<OrderResult> results = orchestrator.executeSignal(signal, adapter);
                    boolean ok = results.stream().anyMatch(r -> r.isSuccess() && r.getOrderId() != null);
                    if (!ok) {
                        String errors = results.stream()
                                .filter(r -> !r.isSuccess())
                                .map(OrderResult::getErrorMessage)
                                .collect(java.util.stream.Collectors.joining("; "));
                        throw new RuntimeException("ENTRY 失敗: " + errors);
                    }
                    broadcastResults = results;
                }
                case "CLOSE" -> {
                    TradeSignal signal = TradeSignal.builder()
                            .symbol(symbol)
                            .signalType(TradeSignal.SignalType.CLOSE)
                            .closeRatio(request.getCloseRatio())
                            .newStopLoss(request.getNewStopLoss())
                            .newTakeProfit(request.getNewTakeProfit())
                            .exchange(exchange)
                            .build();

                    List<OrderResult> results = orchestrator.executeClose(signal, adapter);
                    boolean ok = !results.isEmpty() && results.get(0).isSuccess();
                    if (!ok) {
                        String msg = results.isEmpty() ? "CLOSE 失敗"
                                : results.get(0).getErrorMessage();
                        throw new RuntimeException(msg + ": " + symbol);
                    }
                    broadcastResults = results;
                }
                case "MOVE_SL" -> {
                    TradeSignal signal = TradeSignal.builder()
                            .symbol(symbol)
                            .signalType(TradeSignal.SignalType.MOVE_SL)
                            .newStopLoss(request.getNewStopLoss())
                            .newTakeProfit(request.getNewTakeProfit())
                            .exchange(exchange)
                            .build();

                    List<OrderResult> results = orchestrator.executeMoveSL(signal, adapter);
                    boolean ok = results.stream().allMatch(OrderResult::isSuccess);
                    if (!ok) {
                        throw new RuntimeException("MOVE_SL 失敗: " + symbol);
                    }
                    broadcastResults = results;
                }
                case "CANCEL" -> {
                    if (deduplicationService.isCancelDuplicate(symbol, userId)) {
                        log.warn("廣播跟單: 重複取消跳過 userId={} symbol={}", userId, symbol);
                        return List.of();
                    }
                    java.util.concurrent.locks.ReentrantLock cancelLock = symbolLockRegistry.getLock(symbol);
                    cancelLock.lock();
                    try {
                        adapter.cancelAllOrders(symbol);
                        try {
                            tradeRecordService.recordCancel(symbol, userId);
                        } catch (Exception e) {
                            log.error("取消紀錄寫入失敗（不影響實際取消結果）: {}", e.getMessage());
                        }
                    } finally {
                        cancelLock.unlock();
                    }
                }
                default -> throw new IllegalArgumentException("不支援的 action: " + action);
            }

            log.info("廣播跟單完成: userId={} exchange={} action={} symbol={}",
                    userId, exchange, action, symbol);
            return broadcastResults;
        } finally {
            // 一定要清除 ThreadLocal，避免線程池復用時 key 洩漏給其他用戶
            adapter.clearCredentials();
            TradeRecordService.clearCurrentUserId();
            TradeRecordService.clearCurrentUserDisplayName();
            TradingOrchestrator.clearBroadcastContext();
        }
    }

    // ==================== 格式化工具 ====================

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
                                   int skippedNoSub, int skippedNoKey, String status, SignalScore score,
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
