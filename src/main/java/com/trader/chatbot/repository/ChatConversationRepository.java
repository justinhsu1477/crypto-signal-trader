package com.trader.chatbot.repository;

import com.trader.chatbot.entity.ChatConversation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    List<ChatConversation> findBySessionIdOrderByCreatedAtAsc(String sessionId);

    Optional<ChatConversation> findTopByUserIdOrderByCreatedAtDesc(String userId);

    @Modifying
    @Query("DELETE FROM ChatConversation c WHERE c.createdAt < :cutoff")
    int deleteByCreatedAtBefore(LocalDateTime cutoff);

    long countByUserIdAndCreatedAtAfter(String userId, LocalDateTime since);

    /**
     * Admin：按用戶分組的最新對話（分頁）
     */
    @Query("SELECT c FROM ChatConversation c WHERE c.role = 'user' ORDER BY c.createdAt DESC")
    Page<ChatConversation> findLatestUserMessages(Pageable pageable);

    /**
     * Admin：統計意圖分佈
     */
    @Query("SELECT c.intentType, COUNT(c) FROM ChatConversation c " +
            "WHERE c.role = 'user' AND c.intentType IS NOT NULL " +
            "GROUP BY c.intentType")
    List<Object[]> countByIntentType();

    /**
     * Admin：統計總對話數
     */
    @Query("SELECT COUNT(DISTINCT c.sessionId) FROM ChatConversation c")
    long countDistinctSessions();
}
