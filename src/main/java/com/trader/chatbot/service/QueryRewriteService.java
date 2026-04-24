package com.trader.chatbot.service;

import com.trader.chatbot.dto.ChatTurn;
import com.trader.shared.config.AiConfig;
import com.trader.shared.llm.LlmClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 查詢重寫服務（參考 GenBi QueryPreparationService）
 *
 * 背景：
 * 用戶對話常有上下文依賴，例：
 *   [user] 陳哥最近勝率如何？
 *   [bot]  最近 7 天 60%，30 天 55%。
 *   [user] 那 90 天呢？          ← 「那 90 天呢」本身不完整
 *
 * 原本直接送給 LLM，LLM 可能誤解為「90 天前發生什麼」或反問用戶「你想查什麼？」。
 * Query Rewrite 在送 LLM 前，先把當前短 query 重寫為「陳哥最近 90 天勝率如何」這類自包含語句，
 * 讓下游 intent classification / tool calling 都能正確 routing。
 *
 * 成本控制：
 * - 只在對話歷史非空時呼叫 LLM
 * - 啟發式：長 query（≥ 15 字且含完整語意）直接 passthrough
 * - LLM 失敗 / 超時 → fallback 原 query，不阻塞主流程
 *
 * 依賴：只用 {@link LlmClient}，不依賴 session / DB。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryRewriteService {

    private final LlmClient llmClient;
    private final AiConfig aiConfig;
    private final ChatbotPromptService promptService;

    /** 重寫 LLM 的生成參數：低 temperature（確定性）、低 token（回覆短） */
    private static final int REWRITE_MAX_TOKENS = 120;
    private static final double REWRITE_TEMPERATURE = 0.1;

    /** 短 query 門檻：長度 < 此值才嘗試重寫（長 query 通常自包含） */
    private static final int SHORT_QUERY_THRESHOLD = 15;

    /** 最多取最近 N 輪歷史給 LLM 參考（避免 prompt 過長） */
    private static final int HISTORY_LOOKBACK = 6;

    // package-private — 供 ChatbotPromptSeeder seed
    static final String REWRITE_PROMPT_TEMPLATE = """
            你是查詢重寫助手。根據對話歷史，把「當前查詢」改寫為完整、自包含的問句。

            規則：
            1. 若「當前查詢」已完整（有明確主詞、時間、對象）→ 原樣回傳。
            2. 若「當前查詢」依賴歷史上下文（代名詞「他/這/那」、縮寫、只有時間等）
               → 補上歷史中的主詞 / 對象 / 條件，讓句子獨立成立。
            3. 絕對不新增不存在於歷史的資訊（不要猜測）。
            4. 只回傳改寫後的純文字 query，不要解釋、不要標點包裹、不要 markdown。
            5. 若無法判斷改寫依據，原樣回傳「當前查詢」。

            範例：
            歷史：
              [user] 陳哥最近勝率？
              [model] 7 天 60%。
            當前：90 天呢
            改寫：陳哥最近 90 天勝率如何

            歷史：
              [user] 比特幣持倉多少？
              [model] 目前 0.5 BTC。
            當前：幫我平倉
            改寫：幫我平倉比特幣持倉

            當前輸入：
            """;

    /**
     * 重寫 user query — 歷史為空或 query 已夠完整時 passthrough。
     *
     * @param originalQuery user 原始輸入
     * @param history       最近對話輪次（ChatTurn，role = user / model）
     * @return 重寫後的 query；無法重寫時回傳 originalQuery
     */
    public String rewrite(String originalQuery, List<ChatTurn> history) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return originalQuery;
        }
        String trimmed = originalQuery.trim();

        if (!shouldRewrite(trimmed, history)) {
            return trimmed;
        }

        List<ChatTurn> recentHistory = takeRecent(history, HISTORY_LOOKBACK);
        String historyBlock = formatHistoryForPrompt(recentHistory);
        String fullPrompt = historyBlock + "\n當前：" + trimmed;

        try {
            // W6c: prompt 走 DB，fallback 到 code 內 default
            String activePrompt = promptService.getActivePrompt(
                    ChatbotService.PROMPT_NAME_QUERY_REWRITE, REWRITE_PROMPT_TEMPLATE);
            Optional<String> rewritten = llmClient.generateContentWithHistory(
                    activePrompt,
                    Collections.emptyList(), // history 已經嵌進 user prompt，不另外傳
                    fullPrompt,
                    REWRITE_MAX_TOKENS,
                    REWRITE_TEMPERATURE,
                    aiConfig.getDefaultModel()
            );

            if (rewritten.isEmpty()) {
                log.debug("Query Rewrite LLM 無回應，使用原 query: {}", trimmed);
                return trimmed;
            }

            String result = sanitizeRewrite(rewritten.get());
            if (result.isBlank() || result.length() > 200) {
                // 異常輸出 fallback
                log.warn("Query Rewrite 回傳異常（空或過長），fallback: original={}, rewritten={}",
                        trimmed, preview(result));
                return trimmed;
            }

            if (!result.equals(trimmed)) {
                log.info("Query Rewrite: '{}' → '{}'", trimmed, result);
            }
            return result;
        } catch (Exception e) {
            log.warn("Query Rewrite 異常，fallback 原 query: {} — {}", trimmed, e.getMessage());
            return trimmed;
        }
    }

    /**
     * 決定是否需要重寫 — 成本控制關鍵點
     */
    boolean shouldRewrite(String query, List<ChatTurn> history) {
        // 無歷史 → 無上下文可依 → 不重寫
        if (history == null || history.isEmpty()) {
            return false;
        }
        // 長 query 通常自包含
        if (query.length() >= SHORT_QUERY_THRESHOLD) {
            // 除非含明顯代名詞依賴
            if (!containsPronoun(query)) {
                return false;
            }
        }
        return true;
    }

    private boolean containsPronoun(String query) {
        // 中文代名詞 / 英文代詞
        return query.contains("他") || query.contains("她") || query.contains("它")
                || query.contains("這") || query.contains("那")
                || query.toLowerCase().matches(".*\\b(he|she|it|this|that|they)\\b.*");
    }

    private List<ChatTurn> takeRecent(List<ChatTurn> history, int n) {
        if (history.size() <= n) return history;
        return history.subList(history.size() - n, history.size());
    }

    private String formatHistoryForPrompt(List<ChatTurn> history) {
        StringBuilder sb = new StringBuilder("歷史：\n");
        for (ChatTurn turn : history) {
            sb.append("  [").append(turn.getRole()).append("] ")
                    .append(truncate(turn.getContent(), 100))
                    .append("\n");
        }
        return sb.toString();
    }

    /** 去除常見 LLM 輸出雜訊（開頭的「改寫：」、markdown 反引號、引號） */
    private String sanitizeRewrite(String text) {
        String s = text.trim();
        if (s.startsWith("改寫：")) s = s.substring(3).trim();
        if (s.startsWith("Rewritten:")) s = s.substring(10).trim();
        // 移除包裹引號
        if ((s.startsWith("「") && s.endsWith("」"))
                || (s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'"))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        // 移除 markdown backticks
        s = s.replaceAll("^`+|`+$", "").trim();
        return s;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static String preview(String s) {
        return truncate(s == null ? "" : s.replaceAll("\\s+", " "), 80);
    }
}
