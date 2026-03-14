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
     *
     * 用戶比對邏輯：
     * 1. 名字長度 >= 2 才做子字串比對（避免短名字誤匹配）
     * 2. 多個用戶匹配 → 列出候選名單，提示 Admin 指定
     * 3. 精確匹配 1 人 → 載入該用戶完整詳細資料
     */
    public String gatherAdminContext(String message) {
        StringBuilder sb = new StringBuilder();

        try {
            // 全平台用戶列表（含基本資訊）
            sb.append(gatherAllUsersOverview());

            // 嘗試從訊息中找出目標用戶
            List<User> matchedUsers = findMatchedUsers(message);

            if (matchedUsers.size() == 1) {
                // 精確匹配 1 人 → 載入完整資料
                User u = matchedUsers.get(0);
                String displayName = u.getName() != null && !u.getName().isEmpty() ? u.getName() : u.getEmail();
                sb.append(String.format("\n### 用戶「%s」詳細資料\n", displayName));
                sb.append(gatherAccountStatus(u.getUserId()));
                sb.append(gatherRecentTrades(u.getUserId(), 10));
                sb.append(gatherTradeStats(u.getUserId()));
                sb.append(gatherTradeSettings(u.getUserId()));
            } else if (matchedUsers.size() > 1) {
                // 多人匹配 → 列出候選，讓 Gemini 提示 Admin 指定
                sb.append("\n### ⚠️ 多位用戶匹配，請指定\n");
                sb.append("以下用戶名稱與訊息相似，請管理員指定全名或 email：\n");
                for (User u : matchedUsers) {
                    String name = u.getName() != null ? u.getName() : "未設名";
                    String email = u.getEmail() != null ? u.getEmail() : "";
                    sb.append(String.format("- %s（%s）\n", name, email));
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

    /**
     * 從 Admin 訊息中比對用戶
     *
     * 雙向比對策略：
     * 1. 訊息包含用戶全名（如「蘇小明最近交易如何」匹配「蘇小明」）
     * 2. 用戶名包含訊息中的關鍵字（如「小明最近交易如何」匹配「蘇小明」和「王小明」）
     *
     * 過濾規則：
     * - email 需完整出現在訊息中
     * - 含中文的名字：長度 >= 1 即可（中文 1 字就有意義）
     * - 純英文名字：長度 >= 2（避免 "ok"、"li" 誤觸）
     */
    private List<User> findMatchedUsers(String message) {
        String lowerMsg = message.toLowerCase();
        List<User> allUsers = userRepository.findAll();
        List<User> matched = new java.util.ArrayList<>();

        for (User u : allUsers) {
            String name = u.getName() != null ? u.getName().trim() : "";
            String email = u.getEmail() != null ? u.getEmail().trim() : "";

            // email 完整匹配
            if (!email.isEmpty() && lowerMsg.contains(email.toLowerCase())) {
                matched.add(u);
                continue;
            }

            if (name.isEmpty() || !isNameLongEnough(name)) continue;

            String lowerName = name.toLowerCase();

            // 雙向比對：訊息包含全名 OR 全名包含訊息中的關鍵字
            if (lowerMsg.contains(lowerName) || lowerName.contains(extractNameKeyword(lowerMsg))) {
                matched.add(u);
            }
        }

        return matched;
    }

    /**
     * 從 Admin 訊息中擷取可能的用戶名關鍵字
     *
     * 移除常見的查詢用語，保留可能是人名的部分。
     * 例如「用戶 蘇 最近交易如何」→「蘇」
     */
    private String extractNameKeyword(String lowerMsg) {
        String keyword = lowerMsg
                .replaceAll("用戶|用户|使用者|帳號|帐号|account", "")
                .replaceAll("最近|交易|狀況|状况|如何|怎麼|怎么|績效|绩效|查詢|查询|情況|情况", "")
                .replaceAll("的|了|嗎|吗|呢|啊|是|有", "")
                .trim();
        // 取第一段非空白字串作為關鍵字
        String[] parts = keyword.split("\\s+");
        for (String part : parts) {
            if (!part.isEmpty()) return part;
        }
        return "";
    }

    /**
     * 判斷名字是否夠長可做子字串比對
     * 含中文字 → 1 字以上即可（中文 1 字就有意義）
     * 純英文 → 需 >= 2 字元（避免 "ok"、"li" 誤觸）
     */
    private boolean isNameLongEnough(String name) {
        boolean hasCjk = name.codePoints().anyMatch(cp ->
                Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
        return hasCjk || name.length() >= 2;
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
            // 使用批次聚合查詢（1 次 SQL 取代 N 次 per-user 查詢）
            List<Object[]> stats = tradeRepository.aggregateStatsPerUser();
            long totalTrades = 0;
            long totalWins = 0;
            double totalPnl = 0;
            for (Object[] row : stats) {
                totalTrades += ((Number) row[1]).longValue();
                totalWins += ((Number) row[2]).longValue();
                totalPnl += ((Number) row[3]).doubleValue();
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
                case SETTING_CHANGE -> {
                    sb.append(gatherAccountStatus(userId));
                    sb.append(gatherTradeSettings(userId));
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
