package com.trader.chatbot.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 意圖分類器 — 關鍵字匹配
 *
 * 根據用戶訊息中的關鍵字判斷意圖類型，
 * 決定需要收集哪些上下文資料給 AI 回覆。
 */
@Component
public class IntentClassifier {

    public enum Intent {
        ACCOUNT_STATUS,    // 帳號、餘額、訂閱
        TRADE_QUERY,       // 交易紀錄、損益
        SIGNAL_EXPLAIN,    // 訊號、跟單解釋
        OPERATION_GUIDE,   // 操作指引
        ANOMALY_REPORT,    // 異常回報
        GENERAL            // 一般對話
    }

    private static final Map<Intent, Set<String>> KEYWORDS = Map.of(
            Intent.ACCOUNT_STATUS, Set.of(
                    "餘額", "帳號", "帳戶", "方案", "訂閱", "balance", "account", "plan",
                    "subscription", "額度", "資產", "錢"
            ),
            Intent.TRADE_QUERY, Set.of(
                    "交易", "上次", "最近", "損益", "pnl", "賺", "虧", "獲利", "虧損",
                    "trade", "profit", "loss", "勝率", "績效", "歷史"
            ),
            Intent.SIGNAL_EXPLAIN, Set.of(
                    "訊號", "跟單", "為什麼", "為何", "沒跟", "止損", "止盈", "signal",
                    "開倉", "平倉", "沒有開", "沒開到"
            ),
            Intent.OPERATION_GUIDE, Set.of(
                    "怎麼", "如何", "設定", "教學", "api key", "apikey", "綁定",
                    "通知", "guide", "help", "教我", "步驟"
            ),
            Intent.ANOMALY_REPORT, Set.of(
                    "問題", "錯誤", "異常", "bug", "壞掉", "失敗", "error",
                    "不正常", "故障", "卡住"
            )
    );

    /**
     * 分類用戶訊息意圖
     */
    public Intent classify(String message) {
        if (message == null || message.isBlank()) {
            return Intent.GENERAL;
        }

        String lower = message.toLowerCase().trim();

        // 按優先順序匹配
        Intent[] priority = {
                Intent.ANOMALY_REPORT,
                Intent.SIGNAL_EXPLAIN,
                Intent.TRADE_QUERY,
                Intent.ACCOUNT_STATUS,
                Intent.OPERATION_GUIDE
        };

        for (Intent intent : priority) {
            Set<String> keywords = KEYWORDS.get(intent);
            if (keywords != null) {
                for (String keyword : keywords) {
                    if (lower.contains(keyword)) {
                        return intent;
                    }
                }
            }
        }

        return Intent.GENERAL;
    }
}
