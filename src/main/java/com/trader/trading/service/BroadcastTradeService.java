package com.trader.trading.service;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.shared.model.TradeRequest;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final ExecutorService broadcastExecutor;

    private static final long TASK_TIMEOUT_SECONDS = 30;

    public BroadcastTradeService(
            UserRepository userRepository,
            BinanceFuturesService binanceFuturesService,
            DiscordWebhookService discordWebhookService,
            UserApiKeyService userApiKeyService,
            @Qualifier("broadcastExecutor") ExecutorService broadcastExecutor) {
        this.userRepository = userRepository;
        this.binanceFuturesService = binanceFuturesService;
        this.discordWebhookService = discordWebhookService;
        this.userApiKeyService = userApiKeyService;
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
        // 查詢所有啟用自動跟單的用戶
        List<User> enabledUsers = userRepository.findAll().stream()
                .filter(User::isAutoTradeEnabled)
                .filter(User::isEnabled)
                .toList();

        // 過濾：只保留已設定 Binance API Key 的用戶
        List<User> activeUsers = enabledUsers.stream()
                .filter(u -> userApiKeyService.hasApiKey(u.getUserId()))
                .toList();

        int skippedCount = enabledUsers.size() - activeUsers.size();
        if (skippedCount > 0) {
            log.warn("廣播跟單: {} 個用戶未設定 API Key，已跳過", skippedCount);
        }

        log.info("廣播跟單: 找到 {} 個有效用戶 (跳過 {} 個無 API Key), action={} symbol={}",
                activeUsers.size(), skippedCount, request.getAction(), request.getSymbol());

        if (activeUsers.isEmpty()) {
            return Map.of(
                    "status", "COMPLETED",
                    "totalUsers", 0,
                    "successCount", 0,
                    "failCount", 0,
                    "skippedNoApiKey", skippedCount,
                    "message", activeUsers.isEmpty() && skippedCount > 0
                            ? "所有用戶均未設定 API Key" : "無啟用用戶");
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
                            String.format("%s %s\n入場: %s\n訊號來源: 廣播",
                                    request.getSymbol(),
                                    request.getSide(),
                                    request.getEntryPrice()),
                            DiscordWebhookService.COLOR_GREEN);
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("跟單失敗: userId={} error={}", user.getUserId(), e.getMessage());

                    // 發送失敗通知給用戶
                    discordWebhookService.sendNotificationToUser(
                            user.getUserId(),
                            "❌ 廣播跟單失敗",
                            String.format("%s\n錯誤: %s",
                                    request.getSymbol(),
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
}
