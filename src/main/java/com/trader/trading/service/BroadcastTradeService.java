package com.trader.trading.service;

import com.trader.advisor.dto.SignalScore;
import com.trader.advisor.service.SignalScoringService;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.NotificationService;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeRequest;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.user.service.UserApiKeyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

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
    private final TradeRepository tradeRepository;
    private final ExecutorService broadcastExecutor;

    private static final long TASK_TIMEOUT_SECONDS = 30;

    public BroadcastTradeService(
            UserRepository userRepository,
            BinanceFuturesService binanceFuturesService,
            NotificationService discordWebhookService,
            UserApiKeyService userApiKeyService,
            SubscriptionRepository subscriptionRepository,
            SignalScoringService signalScoringService,
            TradeRepository tradeRepository,
            @Qualifier("broadcastExecutor") ExecutorService broadcastExecutor) {
        this.userRepository = userRepository;
        this.binanceFuturesService = binanceFuturesService;
        this.discordWebhookService = discordWebhookService;
        this.userApiKeyService = userApiKeyService;
        this.subscriptionRepository = subscriptionRepository;
        this.signalScoringService = signalScoringService;
        this.tradeRepository = tradeRepository;
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
        LocalDateTime broadcastStartTime = LocalDateTime.now();
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

        log.info("廣播跟單: 找到 {} 個有效用戶 (跳過無訂閱={}, 跳過無API Key={}), action={} symbol={}",
                activeUsers.size(), skippedNoSubscription, skippedNoApiKey, request.getAction(), request.getSymbol());

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
            return Map.of(
                    "status", "COMPLETED",
                    "totalUsers", 0,
                    "successCount", 0,
                    "failCount", 0,
                    "skippedNoSubscription", skippedNoSubscription,
                    "skippedNoApiKey", skippedNoApiKey,
                    "message", message);
        }

        // 用共享線程池並行執行（不排隊，全員同時下單）
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        // Thread-safe 收集明細（成交限 10 筆、失敗限 5 筆，避免訊息過長）
        ConcurrentLinkedQueue<String> successDetails = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<String> failDetails = new ConcurrentLinkedQueue<>();
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
                    List<OrderResult> results = binanceFuturesService.executeSignalForBroadcast(request, user.getUserId());
                    successCount.incrementAndGet();
                    log.debug("跟單成功: userId={}", user.getUserId());

                    // 找到主要成交結果
                    OrderResult mainResult = (results != null && !results.isEmpty())
                            ? results.stream()
                                .filter(r -> r.isSuccess() && r.getOrderId() != null)
                                .findFirst().orElse(results.get(0))
                            : null;

                    // 非阻塞檢查：AI 分數是否已就緒？
                    SignalScore score = scoreFuture.getNow(null);

                    // 發送 enriched 成功通知給用戶（含實際成交價/PnL/AI 評分）
                    String successTitle = isCloseAction ? "✅ 廣播平倉已執行" : "✅ 廣播跟單已執行";
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

            // 取得最終 AI 評分（短暫等待，大部分情況分數早已就緒）
            SignalScore finalScore = null;
            try {
                finalScore = scoreFuture.get(3, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.debug("AI 評分未及時完成，跳過");
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

            return Map.of(
                    "status", "COMPLETED",
                    "totalUsers", activeUsers.size(),
                    "successCount", successCount.get(),
                    "failCount", failCount.get(),
                    "skippedNoSubscription", skippedNoSubscription,
                    "skippedNoApiKey", skippedNoApiKey);
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
                sb.append("\n");
                if (request.getNewStopLoss() != null) sb.append("新止損: ").append(request.getNewStopLoss()).append("\n");
                if (request.getNewTakeProfit() != null) sb.append("新止盈: ").append(request.getNewTakeProfit()).append("\n");
            }
            case "CANCEL" -> sb.append("\n已取消所有掛單\n");
            default -> sb.append("\n");
        }

        sb.append("用戶: ").append(userDisplay);
        sb.append("\n訊號來源: 廣播");

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
