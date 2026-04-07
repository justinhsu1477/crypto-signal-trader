package com.trader.trading.service;

import com.trader.notification.service.NotificationService;
import com.trader.shared.model.OrderResult;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.model.TradeContext;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.entity.User;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * 未保護倉位自動恢復排程
 *
 * 每 5 分鐘掃描所有 OPEN Trade，檢查 Binance 持倉是否有 SL 保護。
 * 若發現持倉存在但無 SL，自動使用 Trade.stopLoss 補掛止損單。
 *
 * 此排程與 Fail-Safe（BinanceFuturesService L807-848）互補：
 * - Fail-Safe：即時保護，Entry 成功但 SL 失敗時立刻處理
 * - 此排程：定期巡檢，捕捉 Fail-Safe 遺漏的案例（如三重失敗後殘留倉位）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnprotectedPositionRecoveryTask {

    private final TradeRepository tradeRepository;
    private final BinanceFuturesService binanceFuturesService;
    private final TradeRecordService tradeRecordService;
    private final NotificationService notificationService;
    private final MultiUserConfig multiUserConfig;
    private final UserApiKeyService userApiKeyService;
    private final UserRepository userRepository;
    private final SymbolLockRegistry symbolLockRegistry;

    // in-memory retry 計數（重啟歸零）
    private final ConcurrentHashMap<String, Integer> recoveryAttempts = new ConcurrentHashMap<>();
    static final int MAX_RECOVERY_ATTEMPTS = 3;
    private static final long LOCK_TIMEOUT_MS = 500;

    @Scheduled(fixedRate = 5 * 60 * 1000, initialDelay = 3 * 60 * 1000)
    public void scheduledRecoveryCheck() {
        try {
            if (multiUserConfig.isEnabled()) {
                checkForAllUsers();
            } else {
                checkGlobal();
            }
        } catch (Exception e) {
            log.error("SL 保護檢查排程異常: {}", e.getMessage(), e);
        }
    }

    private void checkGlobal() {
        List<Trade> openTrades = tradeRepository.findByStatus("OPEN");
        RecoverySummary summary = checkTradesForUser(openTrades, null);
        logSummary(summary);
        cleanupStaleAttempts(openTrades);
    }

    private void checkForAllUsers() {
        List<User> users = userRepository.findAll().stream()
                .filter(User::isEnabled)
                .toList();

        int totalRecovered = 0;
        int totalFailed = 0;
        List<Trade> allOpenTrades = new java.util.ArrayList<>();

        for (User user : users) {
            String userId = user.getUserId();
            try {
                Optional<BinanceKeys> keysOpt = userApiKeyService.getUserBinanceKeys(userId);
                if (keysOpt.isEmpty()) {
                    continue;
                }

                BinanceFuturesService.setCurrentUserKeys(keysOpt.get());
                TradeContext ctx = TradeContext.forScheduledTask(userId);
                ctx.installThreadLocals();

                try {
                    List<Trade> openTrades = tradeRepository.findByUserIdAndStatus(userId, "OPEN");
                    allOpenTrades.addAll(openTrades);
                    RecoverySummary summary = checkTradesForUser(openTrades, userId);
                    totalRecovered += summary.recovered;
                    totalFailed += summary.failed;
                } finally {
                    BinanceFuturesService.clearCurrentUserKeys();
                    TradeContext.clearThreadLocals();
                }
            } catch (Exception e) {
                log.error("用戶 {} SL 保護檢查失敗: {}", userId, e.getMessage());
            }
        }

        if (totalRecovered > 0 || totalFailed > 0) {
            notificationService.sendNotificationToAdmins(
                    "🔧 SL 保護檢查報告",
                    String.format("恢復成功: %d 筆 | 恢復失敗: %d 筆\n來源: 定期排程 (每 5 分鐘)",
                            totalRecovered, totalFailed),
                    NotificationService.COLOR_BLUE);
        }

        cleanupStaleAttempts(allOpenTrades);
    }

    RecoverySummary checkTradesForUser(List<Trade> openTrades, String userId) {
        if (openTrades.isEmpty()) {
            return new RecoverySummary(0, 0, 0, 0);
        }

        Map<String, Double> positionMap;
        try {
            positionMap = binanceFuturesService.getAllPositionAmountsCached();
        } catch (Exception e) {
            log.error("查詢持倉失敗，跳過本輪 SL 檢查: {}", e.getMessage());
            return new RecoverySummary(openTrades.size(), 0, 0, openTrades.size());
        }

        int checked = 0;
        int recovered = 0;
        int failed = 0;
        int skipped = 0;

        for (Trade trade : openTrades) {
            checked++;
            String symbol = trade.getSymbol();
            String tradeId = trade.getTradeId();

            // 1. 無持倉 → skip（殭屍清理是 DailyReportService 的工作）
            Double positionAmt = positionMap.get(symbol);
            if (positionAmt == null || positionAmt == 0) {
                skipped++;
                continue;
            }

            // 2. 查 SL 是否存在
            double[] sltp;
            try {
                sltp = binanceFuturesService.getCurrentSLTPPrices(symbol);
            } catch (Exception e) {
                log.warn("查詢 {symbol} SL/TP 失敗: {}", symbol, e.getMessage());
                skipped++;
                continue;
            }

            if (sltp[0] != 0) {
                // 有 SL 保護 → OK
                continue;
            }

            // 3. 有持倉但無 SL → 嘗試恢復
            log.warn("發現未保護倉位: {} {} (userId={})", symbol, trade.getSide(), userId);

            // 3a. Trade.stopLoss 為 null → 無法恢復
            if (trade.getStopLoss() == null) {
                log.warn("{} 的 Trade.stopLoss 為 null，無法自動恢復 SL", symbol);
                if (userId != null) {
                    notificationService.sendNotificationToUser(userId,
                            "⚠️ 持倉缺少止損保護",
                            String.format("%s %s\nDB 中無 SL 價格紀錄，無法自動恢復\n請手動設定止損",
                                    symbol, trade.getSide()),
                            NotificationService.COLOR_YELLOW);
                }
                skipped++;
                continue;
            }

            // 3b. 超過最大重試次數
            int attempts = recoveryAttempts.getOrDefault(tradeId, 0);
            if (attempts >= MAX_RECOVERY_ATTEMPTS) {
                log.error("SL 恢復已達上限 ({} 次): {} (userId={})", MAX_RECOVERY_ATTEMPTS, symbol, userId);
                notificationService.sendNotificationToAdmins(
                        "🚨 SL 恢復失敗（已達上限）",
                        String.format("%s %s (userId: %s)\n已嘗試 %d 次均失敗\n需人工介入",
                                symbol, trade.getSide(), userId, MAX_RECOVERY_ATTEMPTS),
                        NotificationService.COLOR_RED);
                skipped++;
                continue;
            }

            // 3c. 嘗試取鎖
            ReentrantLock lock = symbolLockRegistry.getLock(symbol);
            boolean lockAcquired;
            try {
                lockAcquired = lock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                skipped++;
                continue;
            }

            if (!lockAcquired) {
                log.info("SL 恢復跳過 {}：訊號處理中，下次再試", symbol);
                skipped++;
                continue;
            }

            try {
                // 3d. lock 內 double-check：再查一次持倉 + SL
                double currentAmt = binanceFuturesService.getCurrentPositionAmount(symbol);
                if (currentAmt == 0) {
                    skipped++;
                    continue;
                }

                double[] currentSltp = binanceFuturesService.getCurrentSLTPPrices(symbol);
                if (currentSltp[0] != 0) {
                    // SL 已被補上（race condition 解決）
                    skipped++;
                    continue;
                }

                // 3e. 執行 SL 補掛
                String closeSide = "LONG".equals(trade.getSide()) ? "SELL" : "BUY";
                OrderResult slResult = binanceFuturesService.placeStopLoss(
                        symbol, closeSide, trade.getStopLoss(), Math.abs(currentAmt));

                if (slResult.isSuccess()) {
                    recovered++;
                    recoveryAttempts.remove(tradeId);
                    log.info("SL 自動恢復成功: {} @ {} (userId={})", symbol, trade.getStopLoss(), userId);
                    tradeRecordService.recordOrderEvent(symbol, "SL_RECOVERY", slResult, null);
                    if (userId != null) {
                        notificationService.sendNotificationToUser(userId,
                                "🔧 SL 自動恢復成功",
                                String.format("%s %s\nSL: %s\n來源: 定期保護檢查",
                                        symbol, trade.getSide(), trade.getStopLoss()),
                                NotificationService.COLOR_BLUE);
                    } else {
                        notificationService.sendNotification(
                                "🔧 SL 自動恢復成功",
                                String.format("%s %s\nSL: %s\n來源: 定期保護檢查",
                                        symbol, trade.getSide(), trade.getStopLoss()),
                                NotificationService.COLOR_BLUE);
                    }
                } else {
                    failed++;
                    int newCount = recoveryAttempts.merge(tradeId, 1, Integer::sum);
                    log.error("SL 恢復失敗 (第 {} 次): {} 錯誤: {} (userId={})",
                            newCount, symbol, slResult.getErrorMessage(), userId);
                    if (userId != null) {
                        notificationService.sendNotificationToUser(userId,
                                "⚠️ SL 恢復失敗",
                                String.format("%s %s\n嘗試次數: %d/%d\n錯誤: %s\n將在 5 分鐘後重試",
                                        symbol, trade.getSide(), newCount, MAX_RECOVERY_ATTEMPTS,
                                        slResult.getErrorMessage()),
                                NotificationService.COLOR_YELLOW);
                    }
                }
            } catch (Exception e) {
                failed++;
                int newCount = recoveryAttempts.merge(tradeId, 1, Integer::sum);
                log.error("SL 恢復異常 (第 {} 次): {} (userId={}): {}", newCount, symbol, userId, e.getMessage());
            } finally {
                lock.unlock();
            }
        }

        return new RecoverySummary(checked, recovered, failed, skipped);
    }

    private void logSummary(RecoverySummary summary) {
        if (summary.recovered > 0 || summary.failed > 0) {
            log.info("SL 保護檢查完成: 檢查={}, 恢復={}, 失敗={}, 跳過={}",
                    summary.checked, summary.recovered, summary.failed, summary.skipped);
        }
    }

    void cleanupStaleAttempts(List<Trade> openTrades) {
        Set<String> activeTradeIds = openTrades.stream()
                .map(Trade::getTradeId)
                .collect(Collectors.toSet());
        recoveryAttempts.keySet().removeIf(tradeId -> !activeTradeIds.contains(tradeId));
    }

    // 暴露 recoveryAttempts 供測試使用
    ConcurrentHashMap<String, Integer> getRecoveryAttempts() {
        return recoveryAttempts;
    }

    record RecoverySummary(int checked, int recovered, int failed, int skipped) {}
}
