package com.trader.chatbot.service;

import com.trader.chatbot.repository.ChatConversationRepository;
import com.trader.shared.config.AppConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 定期清理過期客服對話（30 天）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatbotCleanupTask {

    private final ChatConversationRepository conversationRepository;

    /**
     * 每天凌晨 3 點清理 30 天前的對話
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupOldConversations() {
        LocalDateTime cutoff = LocalDateTime.now(AppConstants.ZONE_ID).minusDays(30);
        int deleted = conversationRepository.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) {
            log.info("已清理 {} 筆過期客服對話（30 天前）", deleted);
        }
    }
}
