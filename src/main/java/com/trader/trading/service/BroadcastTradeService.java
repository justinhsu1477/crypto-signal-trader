package com.trader.trading.service;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.referral.repository.UserExchangeReferralLinkRepository;
import com.trader.shared.model.TradeRequest;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 廣播跟單服務 (共享線程池版本)
 * - 查詢所有啟用自動跟單且已設定 API Key 的用戶
 * - 用共享線程池（core=10, max=50）並行執行，不排隊
 */
@Slf4j
@Service
public class BroadcastTradeService {

    private final UserRepository userRepository;
    private final BinanceFuturesService binanceFuturesService;
    private final DiscordWebhookService discordWebhookService;
    private final UserApiKeyService userApiKeyService;
    private final UserExchangeReferralLinkRepository referralLinkRepository;
    private final ExecutorService broadcastExecutor;

    private static final long TASK_TIMEOUT_SECONDS = 30;

    public BroadcastTradeService(
            UserRepository userRepository,
            BinanceFuturesService binanceFuturesService,
            DiscordWebhookService discordWebhookService,
            UserApiKeyService userApiKeyService,
            UserExchangeReferralLinkRepository referralLinkRepository,
            @Qualifier("broadcastExecutor") ExecutorService broadcastExecutor) {
        this.userRepository = userRepository;
        this.binanceFuturesService = binanceFuturesService;
        this.discordWebhookService = discordWebhookService;
        this.userApiKeyService = userApiKeyService;
        this.referralLinkRepository = referralLinkRepository;
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

        // Batch 查詢：一次取得所有已設定 API Key 的 userId（避免 N+1）
        Set<String> userIdsWithApiKey = userApiKeyService.getUserIdsWithApiKey("BINANCE");

        // Batch 查詢：一次取得所有已驗證推薦碼的 userId（避免 N+1）
        Set<String> verifiedUserIds = new HashSet<>(
                referralLinkRepository.findVerifiedUserIds("BINANCE"));

        // 過濾：已設定 API Key + 已驗證推薦碼（全部用 Set.contains，O(1) 查找）
        List<User> activeUsers = enabledUsers.stream()
                .filter(u -> userIdsWithApiKey.contains(u.getUserId()))
                .filter(u -> verifiedUserIds.contains(u.getUserId()))
                .toList();

        int skippedCount = enabledUsers.size() - activeUsers.size();
        if (skippedCount > 0) {
            int noApiKey = (int) enabledUsers.stream()
                    .filter(u -> !userIdsWithApiKey.contains(u.getUserId())).count();
            int noReferral = (int) enabledUsers.stream()
                    .filter(u -> userIdsWithApiKey.contains(u.getUserId()))
                    .filter(u -> !verifiedUserIds.contains(u.getUserId())).count();
            log.warn("廣播跟單: 跳過 {} 個用戶 (無 API Key: {}, 未驗證推薦碼: {})",
                    skippedCount, noApiKey, noReferral);
        }

        log.info("廣播跟單: 找到 {} 個有效用戶 (跳過 {}), action={} symbol={}",
                activeUsers.size(), skippedCount, request.getAction(), request.getSymbol());

        // 廣播前 — 發訊號詳情通知給每位 Admin（per-user webhook）
        String signalDetail = formatBroadcastSignalForAdmin(request, activeUsers.size());
        for (User admin : adminUsers) {
            discordWebhookService.sendNotificationToUser(
                    admin.getUserId(),
                    "📡 廣播訊號已發送",
                    signalDetail,
                    DiscordWebhookService.COLOR_BLUE);
        }

        if (activeUsers.isEmpty()) {
            return Map.of(
                    "status", "COMPLETED",
                    "totalUsers", 0,
                    "successCount", 0,
                    "failCount", 0,
                    "skippedNoApiKey", skippedCount,
                    "message", enabledUsers.isEmpty() && skippedCount == 0
                            ? "無啟用用戶" : "所有用戶均未設定 API Key 或未驗證推薦碼");
        }

        // 用共享線程池並行執行（不排隊，全員同時下單）
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 為每個用戶建立 Callable 任務
        List<Callable<Void>> tasks = new ArrayList<>();
        for (User user : activeUsers) {
            tasks.add(() -> {
                try {
                    binanceFuturesService.executeSignalForBroadcast(request, user.getUserId());
                    successCount.incrementAndGet();
                    log.debug("跟單成功: userId={}", user.getUserId());

                    // 發送成功通知給用戶（使用用戶自定義 webhook）
                    discordWebhookService.sendNotificationToUser(
                            user.getUserId(),
                            "✅ 廣播跟單已執行",
                            String.format("%s %s\n入場: %s\n用戶: %s\n訊號來源: 廣播",
                                    request.getSymbol(),
                                    request.getSide(),
                                    request.getEntryPrice(),
                                    user.getUserId()),
                            DiscordWebhookService.COLOR_GREEN);
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("跟單失敗: userId={} error={}", user.getUserId(), e.getMessage());

                    // 發送失敗通知給用戶
                    discordWebhookService.sendNotificationToUser(
                            user.getUserId(),
                            "❌ 廣播跟單失敗",
                            String.format("%s\n用戶: %s\n錯誤: %s",
                                    request.getSymbol(),
                                    user.getUserId(),
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

            // 廣播完成 — 發摘要通知給每位 Admin（per-user webhook）
            String summary = String.format("%s %s\n成功: %d 人\n失敗: %d 人\n超時: %d 人\n總計: %d 人",
                    request.getSymbol(), request.getAction(),
                    successCount.get(), failCount.get(), cancelledCount, activeUsers.size());
            int summaryColor = failCount.get() > 0 || cancelledCount > 0
                    ? DiscordWebhookService.COLOR_YELLOW
                    : DiscordWebhookService.COLOR_GREEN;
            for (User admin : adminUsers) {
                discordWebhookService.sendNotificationToUser(
                        admin.getUserId(),
                        "📊 廣播跟單摘要",
                        summary,
                        summaryColor);
            }

            return Map.of(
                    "status", "COMPLETED",
                    "totalUsers", activeUsers.size(),
                    "successCount", successCount.get(),
                    "failCount", failCount.get(),
                    "skippedNoApiKey", skippedCount);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("廣播跟單中斷: {}", e.getMessage());
            return Map.of(
                    "status", "INTERRUPTED",
                    "error", e.getMessage());
        }
    }

    /**
     * 組裝廣播訊號詳情（發給 Admin 的 per-user webhook）
     * 包含：action、symbol、side、入場價、止損、止盈、來源、目標用戶數
     */
    private String formatBroadcastSignalForAdmin(TradeRequest request, int targetUserCount) {
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
        return sb.toString();
    }
}
