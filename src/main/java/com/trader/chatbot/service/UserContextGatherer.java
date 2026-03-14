package com.trader.chatbot.service;

import com.trader.chatbot.service.IntentClassifier.Intent;
import com.trader.shared.config.AppConstants;
import com.trader.subscription.entity.Subscription;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.trading.entity.Trade;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.entity.User;
import com.trader.user.entity.UserTradeSettings;
import com.trader.user.repository.UserRepository;
import com.trader.user.repository.UserTradeSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * 用戶上下文收集器 — 安全邊界
 *
 * 所有查詢都帶 userId 過濾，確保用戶只能看到自己的資料。
 * 每個方法回傳格式化 String，LLM 不直接碰 DB。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserContextGatherer {

    private final UserRepository userRepository;
    private final TradeRepository tradeRepository;
    private final UserTradeSettingsRepository userTradeSettingsRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final BroadcastLogRepository broadcastLogRepository;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM/dd HH:mm");

    /**
     * Admin 模式：收集全平台資料，讓 Gemini 自己判斷要回答什麼
     */
    public String gatherAdminContext(String message) {
        StringBuilder sb = new StringBuilder();

        try {
            // 全平台用戶列表（含基本資訊）
            sb.append(gatherAllUsersOverview());

            // 嘗試從訊息中找出目標用戶，提供該用戶的詳細資料
            String lowerMsg = message.toLowerCase();
            List<User> allUsers = userRepository.findAll();
            for (User u : allUsers) {
                String name = u.getName() != null ? u.getName() : "";
                String email = u.getEmail() != null ? u.getEmail() : "";
                if (!name.isEmpty() && lowerMsg.contains(name.toLowerCase())
                        || !email.isEmpty() && lowerMsg.contains(email.toLowerCase())) {
                    sb.append(String.format("\n### 用戶「%s」詳細資料\n", name.isEmpty() ? email : name));
                    sb.append(gatherAccountStatus(u.getUserId()));
                    sb.append(gatherRecentTrades(u.getUserId(), 10));
                    sb.append(gatherTradeStats(u.getUserId()));
                    sb.append(gatherTradeSettings(u.getUserId()));
                }
            }

            // 全平台交易統計
            sb.append(gatherPlatformStats());
        } catch (Exception e) {
            log.warn("收集 Admin 上下文失敗: {}", e.getMessage());
            sb.append("\n[部分資料載入失敗]");
        }

        return sb.toString();
    }

    private String gatherAllUsersOverview() {
        StringBuilder sb = new StringBuilder("\n### 全平台用戶列表\n");
        try {
            List<User> users = userRepository.findAll();
            sb.append(String.format("總用戶數：%d\n", users.size()));
            for (User u : users) {
                String name = u.getName() != null ? u.getName() : "未設名";
                String email = u.getEmail() != null ? u.getEmail() : "";
                sb.append(String.format("- %s | %s | %s\n", name, email, u.getRole()));
            }
        } catch (Exception e) {
            sb.append("- [用戶列表載入失敗]\n");
        }
        return sb.toString();
    }

    private String gatherPlatformStats() {
        StringBuilder sb = new StringBuilder("\n### 全平台交易統計\n");
        try {
            List<User> users = userRepository.findAll();
            long totalTrades = 0;
            long totalWins = 0;
            double totalPnl = 0;
            for (User u : users) {
                totalTrades += tradeRepository.countUserClosedTrades(u.getUserId());
                totalWins += tradeRepository.countUserWinningTrades(u.getUserId());
                totalPnl += tradeRepository.sumUserNetProfit(u.getUserId());
            }
            double winRate = totalTrades > 0 ? (double) totalWins / totalTrades * 100 : 0;
            sb.append(String.format("- 總已平倉：%d 筆\n", totalTrades));
            sb.append(String.format("- 整體勝率：%.1f%%\n", winRate));
            sb.append(String.format("- 總損益：%.2f USDT\n", totalPnl));
        } catch (Exception e) {
            sb.append("- [統計載入失敗]\n");
        }
        return sb.toString();
    }

    /**
     * 根據意圖收集對應的上下文
     */
    public String gatherContext(String userId, Intent intent) {
        StringBuilder sb = new StringBuilder();

        try {
            switch (intent) {
                case ACCOUNT_STATUS -> {
                    sb.append(gatherAccountStatus(userId));
                    sb.append(gatherTradeSettings(userId));
                }
                case TRADE_QUERY -> {
                    sb.append(gatherRecentTrades(userId, 10));
                    sb.append(gatherTradeStats(userId));
                }
                case SIGNAL_EXPLAIN -> {
                    sb.append(gatherRecentTrades(userId, 5));
                    sb.append(gatherRecentBroadcastLogs(userId));
                }
                case ANOMALY_REPORT -> {
                    sb.append(gatherAccountStatus(userId));
                    sb.append(gatherRecentTrades(userId, 5));
                    sb.append(gatherRecentBroadcastLogs(userId));
                }
                case OPERATION_GUIDE -> {
                    sb.append(gatherAccountStatus(userId));
                }
                case GENERAL -> {
                    sb.append(gatherAccountStatus(userId));
                    sb.append(gatherTradeStats(userId));
                }
            }
        } catch (Exception e) {
            log.warn("收集用戶上下文失敗: userId={} error={}", userId, e.getMessage());
            sb.append("\n[部分資料載入失敗]");
        }

        return sb.toString();
    }

    private String gatherAccountStatus(String userId) {
        StringBuilder sb = new StringBuilder("\n### 帳號狀態\n");

        try {
            Optional<User> user = userRepository.findById(userId);
            if (user.isPresent()) {
                User u = user.get();
                sb.append("- 帳號：").append(u.getName() != null ? u.getName() : u.getEmail()).append("\n");
                sb.append("- 角色：").append(u.getRole()).append("\n");
            }

            Optional<Subscription> sub = subscriptionRepository.findActiveByUserId(userId);
            if (sub.isPresent()) {
                Subscription s = sub.get();
                sb.append("- 訂閱方案：").append(s.getPlanId()).append("（").append(s.getStatus()).append("）\n");
                if (s.getCurrentPeriodEnd() != null) {
                    sb.append("- 到期日：").append(s.getCurrentPeriodEnd().format(FMT)).append("\n");
                }
            } else {
                sb.append("- 訂閱方案：無有效訂閱\n");
            }
        } catch (Exception e) {
            log.warn("收集帳號狀態失敗: {}", e.getMessage());
            sb.append("- [帳號資料載入失敗]\n");
        }

        return sb.toString();
    }

    private String gatherRecentTrades(String userId, int count) {
        StringBuilder sb = new StringBuilder("\n### 最近交易\n");

        try {
            LocalDateTime since = LocalDateTime.now(AppConstants.ZONE_ID).minusDays(7);
            List<Trade> trades = tradeRepository.findUserClosedTradesAfter(userId, since);

            if (trades.isEmpty()) {
                sb.append("- 近 7 天無已平倉交易\n");
                return sb.toString();
            }

            int limit = Math.min(count, trades.size());
            for (int i = 0; i < limit; i++) {
                Trade t = trades.get(i);
                String time = t.getExitTime() != null ? t.getExitTime().format(FMT) : "N/A";
                sb.append(String.format("- %s %s | PnL: %.2f USDT | %s\n",
                        t.getSymbol(), t.getSide(),
                        t.getNetProfit() != null ? t.getNetProfit() : 0.0, time));
            }
            if (trades.size() > limit) {
                sb.append(String.format("- ...還有 %d 筆\n", trades.size() - limit));
            }
        } catch (Exception e) {
            log.warn("收集交易紀錄失敗: {}", e.getMessage());
            sb.append("- [交易資料載入失敗]\n");
        }

        return sb.toString();
    }

    private String gatherTradeStats(String userId) {
        StringBuilder sb = new StringBuilder("\n### 交易統計\n");

        try {
            long totalClosed = tradeRepository.countUserClosedTrades(userId);
            long totalWins = tradeRepository.countUserWinningTrades(userId);
            double totalPnl = tradeRepository.sumUserNetProfit(userId);
            double winRate = totalClosed > 0 ? (double) totalWins / totalClosed * 100 : 0;

            sb.append(String.format("- 總已平倉：%d 筆\n", totalClosed));
            sb.append(String.format("- 勝率：%.1f%%（%d 勝 / %d 負）\n", winRate, totalWins, totalClosed - totalWins));
            sb.append(String.format("- 總損益：%.2f USDT\n", totalPnl));
        } catch (Exception e) {
            log.warn("收集交易統計失敗: {}", e.getMessage());
            sb.append("- [統計資料載入失敗]\n");
        }

        return sb.toString();
    }

    private String gatherTradeSettings(String userId) {
        StringBuilder sb = new StringBuilder("\n### 交易設定\n");

        try {
            Optional<UserTradeSettings> settings = userTradeSettingsRepository.findById(userId);
            if (settings.isPresent()) {
                UserTradeSettings s = settings.get();
                sb.append(String.format("- 風險比例：%.0f%%\n", s.getRiskPercent() != null ? s.getRiskPercent() * 100 : 0));
                sb.append(String.format("- 槓桿：%dx\n", s.getMaxLeverage() != null ? s.getMaxLeverage() : 20));
                sb.append(String.format("- 最大 DCA 層數：%d\n", s.getMaxDcaLayers() != null ? s.getMaxDcaLayers() : 3));
                sb.append("- 自動止損：").append(s.isAutoSlEnabled() ? "開啟" : "關閉").append("\n");
                sb.append("- 自動止盈：").append(s.isAutoTpEnabled() ? "開啟" : "關閉").append("\n");
            } else {
                sb.append("- 使用全局預設設定\n");
            }
        } catch (Exception e) {
            log.warn("收集交易設定失敗: {}", e.getMessage());
            sb.append("- [設定資料載入失敗]\n");
        }

        return sb.toString();
    }

    private String gatherRecentBroadcastLogs(String userId) {
        StringBuilder sb = new StringBuilder("\n### 最近廣播訊號\n");

        try {
            var logs = broadcastLogRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 5));
            if (logs.isEmpty()) {
                sb.append("- 無近期廣播紀錄\n");
                return sb.toString();
            }

            for (var logEntry : logs) {
                String time = logEntry.getCreatedAt() != null ? logEntry.getCreatedAt().format(FMT) : "N/A";
                sb.append(String.format("- %s %s %s | 來源：%s | 狀態：%s | %s\n",
                        logEntry.getSymbol(),
                        logEntry.getSide() != null ? logEntry.getSide() : "",
                        logEntry.getSignalAction() != null ? logEntry.getSignalAction() : "",
                        logEntry.getSourceAuthor() != null ? logEntry.getSourceAuthor() : "unknown",
                        logEntry.getStatus(),
                        time));
            }
        } catch (Exception e) {
            log.warn("收集廣播紀錄失敗: {}", e.getMessage());
            sb.append("- [廣播資料載入失敗]\n");
        }

        return sb.toString();
    }
}
