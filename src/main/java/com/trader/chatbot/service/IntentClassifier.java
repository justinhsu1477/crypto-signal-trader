package com.trader.chatbot.service;

import com.trader.advisor.service.GeminiService;
import com.trader.chatbot.config.ChatbotConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 意圖分類器 — 混合策略（Keyword 優先 + Gemini AI fallback）
 *
 * 1. 先用 keyword matching（零延遲、確定性高）
 * 2. 若結果為 GENERAL 且 AI 分類開關開啟，呼叫 Gemini 做更精確的分類
 * 3. Gemini 失敗時 graceful degradation 回 GENERAL
 */
@Slf4j
@Component
public class IntentClassifier {

    private final GeminiService geminiService;
    private final ChatbotConfig chatbotConfig;

    public IntentClassifier(GeminiService geminiService, ChatbotConfig chatbotConfig) {
        this.geminiService = geminiService;
        this.chatbotConfig = chatbotConfig;
    }

    public enum Intent {
        ACCOUNT_STATUS,    // 帳號、餘額、訂閱
        TRADE_QUERY,       // 交易紀錄、損益
        SIGNAL_EXPLAIN,    // 訊號、跟單解釋
        SETTING_CHANGE,    // 修改交易設定（觸發 Function Calling）
        MARKET_DATA,       // 市場行情、BTC 價格、持倉
        OPERATION_GUIDE,   // 操作指引
        ANOMALY_REPORT,    // 異常回報
        GENERAL            // 一般對話
    }

    private static final Map<Intent, Set<String>> KEYWORDS = Map.ofEntries(
            Map.entry(Intent.ACCOUNT_STATUS, Set.of(
                    "餘額", "帳號", "帳戶", "方案", "訂閱", "balance", "account", "plan",
                    "subscription", "額度", "資產", "錢"
            )),
            Map.entry(Intent.TRADE_QUERY, Set.of(
                    "交易", "上次", "最近", "損益", "pnl", "賺", "虧", "獲利", "虧損",
                    "trade", "profit", "loss", "勝率", "績效", "歷史"
            )),
            Map.entry(Intent.SIGNAL_EXPLAIN, Set.of(
                    "訊號", "跟單", "為什麼", "為何", "沒跟", "止損", "止盈", "signal",
                    "開倉", "平倉", "沒有開", "沒開到"
            )),
            Map.entry(Intent.SETTING_CHANGE, Set.of(
                    "改", "修改", "調整", "風險", "槓桿", "dca", "層數",
                    "risk", "leverage", "改成", "設為", "調成"
            )),
            Map.entry(Intent.MARKET_DATA, Set.of(
                    "行情", "市場", "btc", "比特幣", "bitcoin", "多少錢", "價格", "price",
                    "做多", "做空", "漲", "跌", "趨勢", "funding", "費率",
                    "恐懼", "貪婪", "fear", "greed", "持倉", "倉位", "我的單",
                    "日報", "報告", "report"
            )),
            Map.entry(Intent.OPERATION_GUIDE, Set.of(
                    "怎麼", "如何", "設定", "教學", "api key", "apikey", "綁定",
                    "通知", "guide", "help", "教我", "步驟"
            )),
            Map.entry(Intent.ANOMALY_REPORT, Set.of(
                    "問題", "錯誤", "異常", "bug", "壞掉", "失敗", "error",
                    "不正常", "故障", "卡住"
            ))
    );

    private static final String CLASSIFICATION_PROMPT = """
            你是意圖分類器。根據用戶訊息，判斷最符合的意圖類別。
            只回傳類別名稱，不要任何其他文字。

            類別說明：
            - ACCOUNT_STATUS：查詢帳號、餘額、訂閱相關
            - TRADE_QUERY：查詢交易紀錄、損益、績效、特定頻道/來源的交易
            - SIGNAL_EXPLAIN：詢問訊號、跟單原因
            - SETTING_CHANGE：要求修改交易設定
            - MARKET_DATA：查詢市場行情、BTC 價格、持倉
            - OPERATION_GUIDE：詢問操作步驟、教學
            - ANOMALY_REPORT：回報問題、異常
            - GENERAL：一般對話、閒聊

            用戶訊息：
            """;

    /**
     * 分類用戶訊息意圖（混合策略）
     *
     * Keyword 匹配到具體意圖 → 直接回傳（零延遲）
     * Keyword 結果為 GENERAL → 嘗試 Gemini AI 分類（更精確）
     */
    public Intent classify(String message) {
        Intent keywordResult = classifyByKeyword(message);

        if (keywordResult != Intent.GENERAL) {
            return keywordResult;
        }

        // Keyword 沒匹配到，嘗試 AI 分類
        if (chatbotConfig.isAiClassificationEnabled()) {
            Intent aiResult = classifyWithAI(message);
            if (aiResult != Intent.GENERAL) {
                log.info("AI 意圖分類覆蓋 keyword 結果: message={} intent={}",
                        message.length() > 30 ? message.substring(0, 30) + "..." : message, aiResult);
            }
            return aiResult;
        }

        return Intent.GENERAL;
    }

    /**
     * 關鍵字匹配分類（原有邏輯）
     */
    Intent classifyByKeyword(String message) {
        if (message == null || message.isBlank()) {
            return Intent.GENERAL;
        }

        String lower = message.toLowerCase().trim();

        // 按優先順序匹配
        Intent[] priority = {
                Intent.ANOMALY_REPORT,
                Intent.SETTING_CHANGE,
                Intent.SIGNAL_EXPLAIN,
                Intent.TRADE_QUERY,
                Intent.MARKET_DATA,
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

    /**
     * Gemini AI 意圖分類
     *
     * 用最小 token 呼叫 Gemini，解析回傳的意圖名稱。
     * 失敗時 graceful degradation 回 GENERAL。
     */
    Intent classifyWithAI(String message) {
        try {
            Optional<String> result = geminiService.generateContentWithHistory(
                    CLASSIFICATION_PROMPT,
                    Collections.emptyList(),
                    message,
                    20,    // maxTokens：只需要一個類別名稱
                    0.1,   // temperature：越低越確定
                    chatbotConfig.getGeminiModel()
            );

            if (result.isEmpty()) {
                return Intent.GENERAL;
            }

            String intentStr = result.get().trim().toUpperCase()
                    .replaceAll("[^A-Z_]", ""); // 移除非字母字元

            return Intent.valueOf(intentStr);
        } catch (IllegalArgumentException e) {
            log.debug("AI 分類結果無法解析為 Intent: message={}", message);
            return Intent.GENERAL;
        } catch (Exception e) {
            log.warn("AI 意圖分類失敗，fallback GENERAL: {}", e.getMessage());
            return Intent.GENERAL;
        }
    }
}
