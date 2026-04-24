package com.trader.chatbot.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Chatbot 業務層面 Micrometer metrics（W7 Observability）
 *
 * 暴露的 metric：
 * - chatbot_messages_total{intent, channel}       — 每 intent / channel 收到訊息數
 * - chatbot_guard_sanitized_total{reason}         — ResponseGuard 攔截次數
 * - chatbot_disambiguation_total                  — NER disambiguation 觸發次數
 * - chatbot_rewrite_total{triggered}              — Query Rewrite 觸發 / 跳過計數
 *
 * 用途：
 * - 觀察 intent 分佈，找出 keyword 分類器盲區
 * - 監控 Guard 攔截頻率，若突增暗示 LLM 或 tool 出現新失敗模式
 * - 判斷 rewrite 是否真的解決 follow-up query 問題
 */
@Component
public class ChatbotMetrics {

    private final MeterRegistry registry;

    public ChatbotMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 每接收到一則 chatbot 訊息就記一次（依 intent / channel 分） */
    public void recordMessage(String intent, String channel) {
        Counter.builder("chatbot.messages")
                .tag("intent", nullSafe(intent))
                .tag("channel", nullSafe(channel))
                .register(registry)
                .increment();
    }

    /** ResponseGuard 偵測到 raw output 並 sanitize */
    public void recordGuardSanitized(String reason) {
        Counter.builder("chatbot.guard.sanitized")
                .tag("reason", nullSafe(reason))
                .register(registry)
                .increment();
    }

    /** Entity Disambiguation 被觸發（直接回問用戶不走 LLM）*/
    public void recordDisambiguation() {
        Counter.builder("chatbot.disambiguation")
                .register(registry)
                .increment();
    }

    /**
     * Query Rewrite 的統計 — 知道比例是否合理（triggered 太多表示成本高，太少表示啟發式過嚴）
     *
     * @param triggered true = 進 LLM 重寫；false = heuristic 跳過
     */
    public void recordRewrite(boolean triggered) {
        Counter.builder("chatbot.rewrite")
                .tag("triggered", String.valueOf(triggered))
                .register(registry)
                .increment();
    }

    private static String nullSafe(String s) {
        return s == null ? "unknown" : s;
    }
}
