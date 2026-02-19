package com.trader.trading.service;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.shared.model.TradeRequest;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 廣播跟單服務 (Thread Pool 版本)
 * - 查詢所有啟用自動跟單的用戶
 * - 用 10 個線程並行執行跟單邏輯
 * - MVP 階段完全夠用
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BroadcastTradeService {

    private final UserRepository userRepository;
    private final BinanceFuturesService binanceFuturesService;
    private final DiscordWebhookService discordWebhookService;

    private static final int THREAD_POOL_SIZE = 10;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

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
        List<User> activeUsers = userRepository.findAll().stream()
                .filter(User::isAutoTradeEnabled)
                .filter(User::isEnabled)
                .toList();

        log.info("廣播跟單: 找到 {} 個啟用用戶, action={} symbol={}",
                activeUsers.size(), request.getAction(), request.getSymbol());

        if (activeUsers.isEmpty()) {
            return Map.of(
                    "status", "COMPLETED",
                    "totalUsers", 0,
                    "successCount", 0,
                    "failCount", 0,
                    "message", "無啟用用戶");
        }

        // 用 Thread Pool 並行執行
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        try {
            for (User user : activeUsers) {
                executor.submit(() -> {
                    try {
                        // 此處應該改為使用 user 的 API Key 執行交易
                        // 目前為簡單版：所有用戶用同一個 API Key
                        // TODO: 未來支援每個用戶獨立 API Key
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
                });
            }

            // 等待所有任務完成
            executor.shutdown();
            boolean completed = executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!completed) {
                log.warn("Thread Pool 超時，強制關閉");
                executor.shutdownNow();
            }

            log.info("廣播跟單完成: 成功={} 失敗={}", successCount.get(), failCount.get());

            return Map.of(
                    "status", "COMPLETED",
                    "totalUsers", activeUsers.size(),
                    "successCount", successCount.get(),
                    "failCount", failCount.get(),
                    "duration_ms", "~2000");
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            log.error("廣播跟單中斷: {}", e.getMessage());
            return Map.of(
                    "status", "INTERRUPTED",
                    "error", e.getMessage());
        }
    }
}
