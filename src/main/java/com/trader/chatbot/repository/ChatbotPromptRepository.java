package com.trader.chatbot.repository;

import com.trader.chatbot.entity.ChatbotPrompt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatbotPromptRepository extends JpaRepository<ChatbotPrompt, Long> {

    /** 取得指定 name 的 active 版本（同時最多一筆） */
    Optional<ChatbotPrompt> findFirstByNameAndActiveTrue(String name);

    /** 列出某 name 的所有版本（新 → 舊） */
    List<ChatbotPrompt> findByNameOrderByVersionDesc(String name);

    /** 檢查指定 name 是否已有任何版本（seeder 啟動判斷用） */
    boolean existsByName(String name);
}
