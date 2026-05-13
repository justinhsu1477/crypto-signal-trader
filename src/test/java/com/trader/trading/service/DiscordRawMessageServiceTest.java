package com.trader.trading.service;

import com.trader.shared.model.DiscordRawMessageRequest;
import com.trader.trading.entity.DiscordRawMessage;
import com.trader.trading.entity.Signal;
import com.trader.trading.repository.DiscordRawMessageRepository;
import com.trader.trading.repository.SignalRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordRawMessageServiceTest {

    private DiscordRawMessageRepository repository;
    private SignalRepository signalRepository;
    private DiscordRawMessageService service;

    @BeforeEach
    void setUp() {
        repository = mock(DiscordRawMessageRepository.class);
        signalRepository = mock(SignalRepository.class);
        service = new DiscordRawMessageService(repository, signalRepository);

        when(repository.save(any(DiscordRawMessage.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private DiscordRawMessageRequest baseRequest(String msgId) {
        DiscordRawMessageRequest req = new DiscordRawMessageRequest();
        req.setMessageId(msgId);
        req.setChannelId("ch-1");
        req.setChannelName("vip");
        req.setGuildId("g-1");
        req.setAuthorName("陳哥");
        req.setMessageTimestamp(LocalDateTime.now());
        req.setContent("BTC 多單 進場 60000");
        req.setHasAttachments(false);
        req.setAttachmentCount(0);
        req.setHasEmbedImages(false);
        req.setHasReference(false);
        return req;
    }

    @Test
    @DisplayName("recordMessage_newEntry_inserts: 新 message_id → 寫入新列")
    void recordMessage_newEntry_inserts() {
        when(repository.findByMessageId("msg-new")).thenReturn(Optional.empty());
        DiscordRawMessageRequest req = baseRequest("msg-new");
        req.setParserAction("INFO");

        DiscordRawMessage saved = service.recordMessage(req);

        assertThat(saved.getMessageId()).isEqualTo("msg-new");
        assertThat(saved.getSourcePlatform()).isEqualTo("DISCORD");
        assertThat(saved.getSourceChannelId()).isEqualTo("ch-1");
        assertThat(saved.getSourceAuthorName()).isEqualTo("陳哥");
        assertThat(saved.getParserAction()).isEqualTo("INFO");
        verify(repository).save(any(DiscordRawMessage.class));
    }

    @Test
    @DisplayName("recordMessage_existingEntry_updatesParserFields: 重複 → UPSERT 更新 parser 結果")
    void recordMessage_existingEntry_updatesParserFields() {
        DiscordRawMessage existing = DiscordRawMessage.builder()
                .id(1L)
                .messageId("msg-dup")
                .sourceChannelId("ch-1")
                .messageTimestamp(LocalDateTime.now())
                .parserAction(null)
                .parserSkippedReason(null)
                .build();
        when(repository.findByMessageId("msg-dup")).thenReturn(Optional.of(existing));

        DiscordRawMessageRequest req = baseRequest("msg-dup");
        req.setParserAction("CLOSE");
        req.setParserSkippedReason(null);

        DiscordRawMessage result = service.recordMessage(req);

        assertThat(result.getParserAction()).isEqualTo("CLOSE");
        // signal lookup runs because action is CLOSE (signal-class)
        verify(signalRepository).findFirstBySourceMessageId("msg-dup");
    }

    @Test
    @DisplayName("recordMessage_signalActionLinksSignalId_whenSignalExists: action 屬訊號類 → 連結 signal_id")
    void recordMessage_signalActionLinksSignalId_whenSignalExists() {
        when(repository.findByMessageId("msg-signal")).thenReturn(Optional.empty());

        Signal preexisting = Signal.builder()
                .signalId("sig-abc-123")
                .sourceMessageId("msg-signal")
                .build();
        when(signalRepository.findFirstBySourceMessageId("msg-signal"))
                .thenReturn(Optional.of(preexisting));

        DiscordRawMessageRequest req = baseRequest("msg-signal");
        req.setParserAction("ENTRY");

        DiscordRawMessage saved = service.recordMessage(req);

        assertThat(saved.getSignalId()).isEqualTo("sig-abc-123");
        assertThat(saved.getParserAction()).isEqualTo("ENTRY");
    }

    @Test
    @DisplayName("recordMessage_infoAction_doesNotLinkSignal: INFO action → 不查 signals")
    void recordMessage_infoAction_doesNotLinkSignal() {
        when(repository.findByMessageId("msg-info")).thenReturn(Optional.empty());

        DiscordRawMessageRequest req = baseRequest("msg-info");
        req.setParserAction("INFO");

        DiscordRawMessage saved = service.recordMessage(req);

        assertThat(saved.getSignalId()).isNull();
        verify(signalRepository, never()).findFirstBySourceMessageId(any());
    }

    @Test
    @DisplayName("recordMessage_signalActionNoMatchingSignal_leavesSignalIdNull: action 屬訊號類但 signals 表無 match → signal_id 為 null")
    void recordMessage_signalActionNoMatchingSignal_leavesSignalIdNull() {
        when(repository.findByMessageId("msg-orphan")).thenReturn(Optional.empty());
        when(signalRepository.findFirstBySourceMessageId("msg-orphan"))
                .thenReturn(Optional.empty());

        DiscordRawMessageRequest req = baseRequest("msg-orphan");
        req.setParserAction("ENTRY");

        DiscordRawMessage saved = service.recordMessage(req);

        assertThat(saved.getSignalId()).isNull();
        assertThat(saved.getParserAction()).isEqualTo("ENTRY");
    }

    @Test
    @DisplayName("findMissedSignals_returnsUnprocessedFromAuthor: 漏單偵測委派 repo 查詢")
    void findMissedSignals_returnsUnprocessedFromAuthor() {
        DiscordRawMessage drm1 = DiscordRawMessage.builder()
                .id(10L).messageId("m1").sourceAuthorName("三马哥").build();
        DiscordRawMessage drm2 = DiscordRawMessage.builder()
                .id(11L).messageId("m2").sourceAuthorName("三马哥").build();
        when(repository.findUnprocessedByAuthorSince(any(), any()))
                .thenReturn(List.of(drm1, drm2));

        List<DiscordRawMessage> result = service.findMissedSignals("三马哥", 7);

        assertThat(result).hasSize(2);
        ArgumentCaptor<String> authorCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).findUnprocessedByAuthorSince(authorCaptor.capture(), sinceCaptor.capture());
        assertThat(authorCaptor.getValue()).isEqualTo("三马哥");
        // since 應該是大約 7 天前（驗證在 6.5~7.5 天範圍內，避免時間差脆性）
        LocalDateTime expectedRoughly = LocalDateTime.now().minusDays(7);
        assertThat(sinceCaptor.getValue()).isBetween(
                expectedRoughly.minusHours(2),
                expectedRoughly.plusHours(2)
        );
    }
}
