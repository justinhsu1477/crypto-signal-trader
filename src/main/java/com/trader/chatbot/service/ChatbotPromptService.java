package com.trader.chatbot.service;

import com.trader.chatbot.entity.ChatbotPrompt;
import com.trader.chatbot.repository.ChatbotPromptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chatbot Prompt 管理服務（W6a — Prompt 資料化）
 *
 * 職責：
 * - 從 DB 取得 active prompt
 * - DB 無資料時 fallback 到 code 內 default（向後相容保證）
 * - In-memory cache（TTL 不長，避免 Admin 熱改後生效過慢）
 * - Admin 建立新版本、切換 active
 *
 * 設計原則：
 * - Fallback 優先保證服務不中斷（DB 掛、migration 還沒跑都能活）
 * - Admin 熱改不需重部署
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatbotPromptService {

    private final ChatbotPromptRepository promptRepository;

    /** Prompt 快取 — key = name, value = content；60s TTL 用最後 load 時間戳比對 */
    private final Map<String, CachedPrompt> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 60_000;

    /**
     * 取得 name 對應的 active prompt
     * 順序：
     * 1. In-memory cache（未過期）
     * 2. DB active 版本
     * 3. Fallback default（code 內 hardcoded）
     *
     * @param name           prompt 識別名稱（如 system_user）
     * @param fallbackDefault 若 DB 無資料或查詢失敗，用此值
     * @return prompt content
     */
    public String getActivePrompt(String name, String fallbackDefault) {
        CachedPrompt cached = cache.get(name);
        if (cached != null && !cached.isExpired()) {
            return cached.content;
        }

        try {
            Optional<ChatbotPrompt> fromDb = promptRepository.findFirstByNameAndActiveTrue(name);
            String content = fromDb.map(ChatbotPrompt::getContent).orElse(fallbackDefault);
            cache.put(name, new CachedPrompt(content));
            return content;
        } catch (Exception e) {
            log.warn("取得 chatbot prompt 失敗，使用 fallback: name={} err={}", name, e.getMessage());
            return fallbackDefault;
        }
    }

    /**
     * Admin 建立新版本 — 不自動 active，要手動 activate 才生效
     */
    @Transactional
    public ChatbotPrompt createVersion(String name, String content, String description) {
        int nextVersion = promptRepository.findByNameOrderByVersionDesc(name).stream()
                .findFirst()
                .map(p -> p.getVersion() + 1)
                .orElse(1);

        ChatbotPrompt prompt = ChatbotPrompt.builder()
                .name(name)
                .version(nextVersion)
                .content(content)
                .description(description)
                .active(false)
                .build();
        ChatbotPrompt saved = promptRepository.save(prompt);
        log.info("Chatbot prompt 新版本建立: name={} v={}", name, saved.getVersion());
        return saved;
    }

    /**
     * Activate 指定版本 — 同 name 的其他版本會被 deactivate
     */
    @Transactional
    public void activate(Long id) {
        ChatbotPrompt target = promptRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Prompt id 不存在: " + id));

        promptRepository.findByNameOrderByVersionDesc(target.getName())
                .forEach(p -> {
                    if (p.isActive() && !p.getId().equals(id)) {
                        p.setActive(false);
                        promptRepository.save(p);
                    }
                });

        target.setActive(true);
        promptRepository.save(target);

        // 清除該 name 的 cache，強制重新讀 DB
        cache.remove(target.getName());
        log.info("Chatbot prompt 已啟用: name={} v={} id={}", target.getName(), target.getVersion(), id);
    }

    /**
     * 啟動時若 DB 尚無該 name 的 prompt，seed 一個 default（active）版本
     * 目的：首次部署 / 新 prompt name 新增時不需手動初始化
     */
    @Transactional
    public void seedIfAbsent(String name, String content, String description) {
        if (promptRepository.existsByName(name)) {
            return;
        }
        ChatbotPrompt prompt = ChatbotPrompt.builder()
                .name(name)
                .version(1)
                .content(content)
                .description(description)
                .active(true)
                .build();
        promptRepository.save(prompt);
        log.info("Chatbot prompt seed（首次部署）: name={}", name);
    }

    /** Admin 管理頁面用 — 列出某 name 的所有歷史版本 */
    public java.util.List<ChatbotPrompt> listVersions(String name) {
        return promptRepository.findByNameOrderByVersionDesc(name);
    }

    /** 測試 / Admin 用：手動失效 cache */
    public void invalidateCache() {
        cache.clear();
    }

    // ==== 內部 cache 結構 ====

    private static class CachedPrompt {
        final String content;
        final long loadedAt;

        CachedPrompt(String content) {
            this.content = content;
            this.loadedAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - loadedAt > CACHE_TTL_MS;
        }
    }
}
