package com.trader.chatbot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Chatbot 回覆過濾器 — 擋住未被 LLM 消化的原始工具輸出 / 錯誤訊息外洩。
 *
 * 背景：
 * ChatbotService 的 Multi-tool Chaining fallback 路徑（Gemini 在中途失敗時）
 * 會直接把 tool 執行結果字串回傳給用戶。若 tool 拋例外或回傳 raw JSON，
 * 用戶會看到像 {@code [{"source_name":"chenge"}]} 或 {@code ClassCastException...}
 * 這類內部細節 — 體驗極差且易被誤解為 AI 瞎編資料。
 *
 * 設計原則：
 * - heuristic 偵測（regex / prefix），不依賴 LLM
 * - 偵測到即取代為 safeFallback，同時 log 警告以利後續 debug
 * - 保守：有疑慮時優先放行（避免誤擋合法結構化 markdown 回覆）
 *
 * 單一職責：純偵測 + 替換，不動 session / log / audit 等 side effect。
 */
@Slf4j
@Service
public class ResponseGuard {

    /**
     * 過濾 LLM / fallback 回覆，避免直接把 raw tool output 或 error message 回給用戶。
     *
     * @param text         原始回覆
     * @param safeFallback 偵測到異常時回傳此字串
     * @return 安全的回覆文字
     */
    public String sanitize(String text, String safeFallback) {
        if (text == null || text.isBlank()) {
            return safeFallback;
        }
        String trimmed = text.trim();

        if (looksLikeRawJsonOutput(trimmed)) {
            log.warn("ResponseGuard: 偵測到 raw JSON 輸出，以 fallback 取代: snippet={}",
                    preview(trimmed));
            return safeFallback;
        }
        if (looksLikeErrorMessage(trimmed)) {
            log.warn("ResponseGuard: 偵測到錯誤訊息外洩，以 fallback 取代: snippet={}",
                    preview(trimmed));
            return safeFallback;
        }
        return text;
    }

    /**
     * 偵測 raw JSON pattern（array / object 起頭，且沒有自然語言包裝）。
     *
     * 命中：
     *   [{"source_name":"chenge"}]
     *   {"error": "..."}
     *
     * 不命中（合法情境）：
     *   ### 查詢結果\n{"...": ...}            ← 有 markdown 外層
     *   已為您查到以下資訊：{"...": ...}        ← 有中文前綴
     */
    boolean looksLikeRawJsonOutput(String text) {
        if (text.startsWith("[{") || text.startsWith("[\"")) {
            return true;
        }
        // 純 JSON object，且沒有換行或任何自然語言特徵
        if (text.startsWith("{\"") && !text.contains("\n") && !hasChineseChar(text.substring(0, Math.min(80, text.length())))) {
            return true;
        }
        return false;
    }

    /**
     * 偵測 Java 例外 / 內部錯誤字串特徵
     */
    boolean looksLikeErrorMessage(String text) {
        if (text.contains("Exception:") || text.contains("Exception ")) {
            return true;
        }
        if (text.contains("\n\tat ") || text.contains("Caused by:")) {
            return true;
        }
        // Tool catch block 常見字串
        if (text.contains("[績效資料載入失敗]")) {
            return true;
        }
        // "取得XXX失敗:" 這類從 log.warn 漏到 response 的模式
        if (text.startsWith("取得") && text.contains("失敗")) {
            return true;
        }
        return false;
    }

    private static boolean hasChineseChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)) {
                return true;
            }
        }
        return false;
    }

    private static String preview(String text) {
        String s = text.replaceAll("\\s+", " ");
        return s.length() > 100 ? s.substring(0, 100) + "..." : s;
    }
}
