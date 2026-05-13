package com.trader.trading.repository;

import com.trader.trading.entity.DiscordRawMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DiscordRawMessageRepository extends JpaRepository<DiscordRawMessage, Long> {

    Optional<DiscordRawMessage> findByMessageId(String messageId);

    boolean existsByMessageId(String messageId);

    /**
     * 漏單偵測查詢：指定 author 在 since 之後尚未被處理（無 signal_id 且無 parser_action）
     * 的所有原始訊息。
     */
    @Query(value = """
            SELECT * FROM discord_raw_messages
            WHERE source_author_name = :authorName
              AND signal_id IS NULL
              AND parser_action IS NULL
              AND message_timestamp >= :since
            ORDER BY message_timestamp DESC
            """, nativeQuery = true)
    List<DiscordRawMessage> findUnprocessedByAuthorSince(
            @Param("authorName") String authorName,
            @Param("since") LocalDateTime since
    );
}
