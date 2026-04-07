package com.trader.trading.service;

import com.trader.shared.config.AppConstants;
import com.trader.trading.entity.AnalystDailyMessage;
import com.trader.trading.repository.AnalystDailyMessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 分析師訊息收集服務 — 累積每位分析師每日的所有訊息
 *
 * Discord Monitor 會將所有訊息 POST 到 /api/analyst-messages，
 * 本服務以 upsert 方式 append 到 analyst_daily_messages 表。
 *
 * 併發安全：SELECT FOR UPDATE 確保同一分析師同一天的記錄不會 race condition。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalystMessageService {

    private final AnalystDailyMessageRepository repository;

    /**
     * Append 一則訊息到分析師當日記錄（upsert）
     * 使用 pessimistic lock 避免併發 insert UNIQUE constraint 衝突
     */
    @Transactional
    public AnalystDailyMessage appendMessage(String analystName, String channelId, String messageContent) {
        LocalDate today = LocalDate.now(AppConstants.ZONE_ID);

        Optional<AnalystDailyMessage> existing = repository.findWithLockByAnalystNameAndMessageDate(analystName, today);

        if (existing.isPresent()) {
            AnalystDailyMessage msg = existing.get();
            msg.setContent(msg.getContent() + "\n---\n" + messageContent);
            msg.setMessageCount(msg.getMessageCount() + 1);
            repository.save(msg);
            log.debug("分析師訊息 append: {} 第 {} 則", analystName, msg.getMessageCount());
            return msg;
        }

        AnalystDailyMessage msg = AnalystDailyMessage.builder()
                .analystName(analystName)
                .channelId(channelId)
                .messageDate(today)
                .content(messageContent)
                .messageCount(1)
                .build();
        repository.save(msg);
        log.info("分析師訊息新建: {} date={}", analystName, today);
        return msg;
    }

    /**
     * 取得指定日期所有分析師的訊息
     */
    public List<AnalystDailyMessage> getMessagesByDate(LocalDate date) {
        return repository.findByMessageDateOrderByAnalystName(date);
    }
}
