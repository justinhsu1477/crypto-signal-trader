package com.trader.trading.service;

import com.trader.shared.config.AppConstants;
import com.trader.shared.model.DiscordRawMessageRequest;
import com.trader.trading.entity.DiscordRawMessage;
import com.trader.trading.repository.DiscordRawMessageRepository;
import com.trader.trading.repository.SignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * Discord 原始訊息封存服務 — 用於 audit / 漏單偵測 / eval-harness 訓練資料。
 *
 * <p>每則通過 channel/guild/author 過濾的訊息由 Python discord-monitor 透過
 * /api/discord-messages 上報，不論最終是否被判讀為訊號。</p>
 *
 * <p>upsert 語意：以 message_id 為唯一鍵；同一 message_id 重複 POST 會更新 parser 結果欄位。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DiscordRawMessageService {

    /** AI 判讀為這些 action 時，視為「應該存在對應 Signal」，會嘗試連結 signals.signal_id */
    private static final Set<String> SIGNAL_ACTIONS = Set.of("ENTRY", "CLOSE", "MOVE_SL", "CANCEL");

    private final DiscordRawMessageRepository repository;
    private final SignalRepository signalRepository;

    /**
     * 上報一則 Discord 訊息（UPSERT 語意）。
     *
     * @param req 來自 Python 端的請求
     * @return 寫入或更新後的實體
     */
    @Transactional
    public DiscordRawMessage recordMessage(DiscordRawMessageRequest req) {
        DiscordRawMessage existing = repository.findByMessageId(req.getMessageId()).orElse(null);
        if (existing != null) {
            // UPSERT：更新 parser 結果欄位（其餘以首次寫入為準）
            if (req.getParserAction() != null) {
                existing.setParserAction(req.getParserAction());
            }
            if (req.getParserSkippedReason() != null) {
                existing.setParserSkippedReason(req.getParserSkippedReason());
            }
            // 若尚未連結 signal_id 且 action 屬訊號類，嘗試從 signals 表查回連結
            if (existing.getSignalId() == null && isSignalAction(existing.getParserAction())) {
                signalRepository.findFirstBySourceMessageId(req.getMessageId())
                        .ifPresent(s -> existing.setSignalId(s.getSignalId()));
            }
            return repository.save(existing);
        }

        DiscordRawMessage entity = DiscordRawMessage.builder()
                .messageId(req.getMessageId())
                .sourcePlatform("DISCORD")
                .sourceChannelId(req.getChannelId())
                .sourceChannelName(req.getChannelName())
                .sourceGuildId(req.getGuildId())
                .sourceAuthorName(req.getAuthorName())
                .messageTimestamp(req.getMessageTimestamp() != null
                        ? req.getMessageTimestamp().atZoneSameInstant(AppConstants.ZONE_ID).toLocalDateTime()
                        : LocalDateTime.now(AppConstants.ZONE_ID))
                .content(req.getContent())
                .hasAttachments(Boolean.TRUE.equals(req.getHasAttachments()))
                .attachmentCount(req.getAttachmentCount() != null ? req.getAttachmentCount() : 0)
                .attachmentSha256(req.getAttachmentSha256())
                .hasEmbedImages(Boolean.TRUE.equals(req.getHasEmbedImages()))
                .hasReference(Boolean.TRUE.equals(req.getHasReference()))
                .parserAction(req.getParserAction())
                .parserSkippedReason(req.getParserSkippedReason())
                .build();

        // 初次寫入：若 Signal 已先一步建立（broadcast-trade 在 archive POST 之前），即可正向連結
        if (isSignalAction(entity.getParserAction())) {
            signalRepository.findFirstBySourceMessageId(req.getMessageId())
                    .ifPresent(s -> entity.setSignalId(s.getSignalId()));
        }

        return repository.save(entity);
    }

    /**
     * 漏單偵測：指定 author 在最近 N 天內，尚未被處理（無 signal_id 且無 parser_action）的訊息。
     */
    public List<DiscordRawMessage> findMissedSignals(String authorName, int daysWindow) {
        LocalDateTime since = LocalDateTime.now(AppConstants.ZONE_ID).minusDays(daysWindow);
        return repository.findUnprocessedByAuthorSince(authorName, since);
    }

    private boolean isSignalAction(String action) {
        return action != null && SIGNAL_ACTIONS.contains(action);
    }
}
