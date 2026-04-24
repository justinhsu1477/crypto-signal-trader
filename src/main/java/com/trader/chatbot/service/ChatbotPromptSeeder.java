package com.trader.chatbot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Chatbot Prompt 啟動種子（W6a）
 *
 * 應用程式啟動完成時：
 * - 檢查 DB 有沒有對應 prompt name 的資料
 * - 沒有 → 從 code 裡的 default（ChatbotService.SYSTEM_PROMPT 等）seed 一份 active 版本
 *
 * 目的：
 * - 首次部署自動初始化，不需手動 INSERT
 * - 新增 prompt name 時自動 seed
 * - 既有生產資料不受影響（已存在就跳過）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatbotPromptSeeder {

    private final ChatbotPromptService promptService;

    @EventListener(ApplicationReadyEvent.class)
    public void seedDefaultPrompts() {
        try {
            promptService.seedIfAbsent(
                    ChatbotService.PROMPT_NAME_SYSTEM_USER,
                    ChatbotService.SYSTEM_PROMPT,
                    "Default user chatbot system prompt (auto-seeded from code)");

            promptService.seedIfAbsent(
                    ChatbotService.PROMPT_NAME_SYSTEM_ADMIN,
                    ChatbotService.ADMIN_SYSTEM_PROMPT,
                    "Default admin chatbot system prompt (auto-seeded from code)");

            log.info("Chatbot prompt seeding 完成");
        } catch (Exception e) {
            log.warn("Chatbot prompt seeding 失敗（非致命，ChatbotService 會用 code fallback）: {}",
                    e.getMessage());
        }
    }
}
