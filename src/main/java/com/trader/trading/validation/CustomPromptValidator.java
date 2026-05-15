package com.trader.trading.validation;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SignalSource.customPrompt 寫入端的 sanitization / 拒絕。
 *
 * <p>規則對應 {@code discord-monitor/docs/PROMPT_ARCHITECTURE.md} 的
 * Safety Constraints 章節。任何不在白名單行為都應該在寫入時 reject，
 * 不依賴 LLM 自律。
 *
 * <p>呼叫方式：
 * <pre>{@code
 * String sanitized = customPromptValidator.sanitizeOrThrow(raw);
 * }</pre>
 */
@Component
public class CustomPromptValidator {

    public static final int MAX_LENGTH = 1500;
    public static final int WARN_LENGTH = 800;

    /** Section marker — 撞 prompt_builder.from_legacy_prompt 切割點，絕對禁止。 */
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "## 規則",
            "## 範例",
            "## 複合動作識別",
            "## Rules",
            "## Examples"
    );

    /** 直接 prompt-injection 攻擊樣本，發現即 reject。比對時忽略大小寫。 */
    private static final List<String> FORBIDDEN_PHRASES = List.of(
            "忽略以上",
            "忽略上述",
            "ignore previous",
            "ignore the above",
            "disregard the above",
            "disregard previous",
            "output plain text",
            "respond with plain",
            "respond in plain",
            "不要輸出 json",
            "不要輸出json",
            "do not output json",
            "stop following",
            "system override"
    );

    /** schema 是不可變的，任何試圖新增 schema 欄位的指令都 reject。 */
    private static final List<String> FORBIDDEN_SCHEMA_VERBS = List.of(
            "add field",
            "add a field",
            "新增欄位",
            "新增一個欄位",
            "output additional",
            "output extra field"
    );

    /**
     * 驗證並回傳 sanitized 字串；違規時拋 IllegalArgumentException。
     *
     * <p>容許 null / 空字串 — 視為「清空 custom_prompt」。
     */
    public String sanitizeOrThrow(String raw) {
        if (raw == null) return "";

        // 1. 控制字元（除 \n \t）一律剝除 — 阻 U+202E、ZWJ、ZWSP 等 trick
        String stripped = stripControlChars(raw);

        // 2. 兩端 trim — 多餘空白不存
        String trimmed = stripped.trim();

        if (trimmed.isEmpty()) {
            return "";
        }

        // 3. 長度上限（hard）
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    String.format("custom_prompt 過長：%d 字元，上限 %d", trimmed.length(), MAX_LENGTH));
        }

        // 4. Section marker 禁止
        String lower = trimmed.toLowerCase();
        for (String marker : FORBIDDEN_MARKERS) {
            if (lower.contains(marker.toLowerCase())) {
                throw new IllegalArgumentException(
                        "custom_prompt 含禁止的 section marker：" + marker
                                + "（會破壞 prompt 結構切割，請改用一般文字描述）");
            }
        }

        // 5. Prompt injection 禁止
        for (String phrase : FORBIDDEN_PHRASES) {
            if (lower.contains(phrase.toLowerCase())) {
                throw new IllegalArgumentException(
                        "custom_prompt 含疑似 prompt injection 樣本：「" + phrase + "」"
                                + "（不允許指令型用語）");
            }
        }

        // 6. Schema 改動禁止
        for (String verb : FORBIDDEN_SCHEMA_VERBS) {
            if (lower.contains(verb.toLowerCase())) {
                throw new IllegalArgumentException(
                        "custom_prompt 試圖修改 schema：「" + verb + "」"
                                + "（schema 是不可變約定，請走 parser release）");
            }
        }

        return trimmed;
    }

    /**
     * 寫入後可選的 soft warning — 長度過長 / 含數字門檻時建議拆分到通用規則或 risk_multiplier。
     *
     * @return warning 訊息（無問題時回傳 null）
     */
    public String softWarning(String sanitized) {
        if (sanitized == null || sanitized.isEmpty()) return null;
        if (sanitized.length() > WARN_LENGTH) {
            return String.format("custom_prompt 長度 %d > %d，建議將通用規則搬到全局 SYSTEM_PROMPT",
                    sanitized.length(), WARN_LENGTH);
        }
        return null;
    }

    /** 保留 \n \t，其他 ISO control char 一律剝除。 */
    private static String stripControlChars(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || c == '\t') {
                sb.append(c);
            } else if (!Character.isISOControl(c)
                    && !isZeroWidth(c)
                    && !isBidiOverride(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isZeroWidth(char c) {
        return c == '​' || c == '‌' || c == '‍' || c == '﻿';
    }

    private static boolean isBidiOverride(char c) {
        // U+202A..U+202E：bidi formatting；U+2066..U+2069：bidi isolate
        return (c >= 0x202A && c <= 0x202E) || (c >= 0x2066 && c <= 0x2069);
    }
}
