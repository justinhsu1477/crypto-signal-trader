package com.trader.trading.service;

import com.trader.shared.model.DiscordRawMessageRequest;
import com.trader.trading.entity.DiscordRawMessage;
import com.trader.trading.entity.Signal;
import com.trader.trading.entity.SignalSourceMirrorTarget;
import com.trader.trading.repository.DiscordRawMessageRepository;
import com.trader.trading.repository.SignalRepository;
import com.trader.trading.repository.SignalSourceMirrorTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.trader.shared.config.AppConstants;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiscordRawMessageServiceTest {

    private DiscordRawMessageRepository repository;
    private SignalRepository signalRepository;
    private com.trader.notification.service.MirrorWebhookService mirror;
    private com.trader.trading.repository.SignalSourceConfigRepository sourceRepository;
    private SignalSourceMirrorTargetRepository mirrorTargetRepository;
    private DiscordRawMessageService service;

    @BeforeEach
    void setUp() {
        repository = mock(DiscordRawMessageRepository.class);
        signalRepository = mock(SignalRepository.class);
        mirror = mock(com.trader.notification.service.MirrorWebhookService.class);
        sourceRepository = mock(com.trader.trading.repository.SignalSourceConfigRepository.class);
        mirrorTargetRepository = mock(SignalSourceMirrorTargetRepository.class);
        service = new DiscordRawMessageService(
                repository,
                signalRepository,
                mirror,
                sourceRepository,
                mirrorTargetRepository);

        when(repository.save(any(DiscordRawMessage.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sourceRepository.findByChannelIdAndGuildId(any(), any())).thenReturn(Optional.empty());
        when(sourceRepository.findByChannelId(any())).thenReturn(Optional.empty());
        when(mirrorTargetRepository.findBySourceIdAndEnabledTrue(any())).thenReturn(List.of());
    }

    private DiscordRawMessageRequest baseRequest(String msgId) {
        DiscordRawMessageRequest req = new DiscordRawMessageRequest();
        req.setMessageId(msgId);
        req.setChannelId("ch-1");
        req.setChannelName("vip");
        req.setGuildId("g-1");
        req.setAuthorName("陳哥");
        req.setMessageTimestamp(OffsetDateTime.now());
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
    @DisplayName("recordMessage_triggersMirror: 寫入後觸發 MirrorWebhookService（含 attachment_url）")
    void recordMessage_triggersMirror() {
        // arrange — source 存在
        com.trader.trading.entity.SignalSourceConfig src = com.trader.trading.entity.SignalSourceConfig.builder()
                .id(42L).name("chenge").displayName("陳哥")
                .channelId("ch-1").guildId("g-1")
                .build();
        when(sourceRepository.findByChannelIdAndGuildId("ch-1", "g-1")).thenReturn(java.util.Optional.of(src));
        when(repository.findByMessageId("msg-mirror")).thenReturn(java.util.Optional.empty());

        DiscordRawMessageRequest req = baseRequest("msg-mirror");
        req.setAttachmentUrl("https://cdn.discordapp.com/attachments/x/y/banner.png");

        service.recordMessage(req);

        // assert — mirror 被叫，且 attachmentUrl 透傳
        verify(mirror).mirrorAsync(
                eq(src),
                any(DiscordRawMessage.class),
                eq("https://cdn.discordapp.com/attachments/x/y/banner.png"));
    }

    @Test
    @DisplayName("recordMessage_triggersMirrorTargets: 同一 source 會 fan-out 到多個 mirror target")
    void recordMessage_triggersMirrorTargets() {
        com.trader.trading.entity.SignalSourceConfig src = com.trader.trading.entity.SignalSourceConfig.builder()
                .id(42L).name("chenge").displayName("陳哥")
                .channelId("ch-1").guildId("g-1")
                .mirrorEnabled(true)
                .build();
        SignalSourceMirrorTarget targetA = SignalSourceMirrorTarget.builder()
                .source(src)
                .sourceId(42L)
                .targetChannelId("target-a")
                .mirrorWebhookUrl("enc-a")
                .enabled(true)
                .build();
        SignalSourceMirrorTarget targetB = SignalSourceMirrorTarget.builder()
                .source(src)
                .sourceId(42L)
                .targetChannelId("target-b")
                .mirrorWebhookUrl("enc-b")
                .enabled(true)
                .build();
        when(sourceRepository.findByChannelIdAndGuildId("ch-1", "g-1")).thenReturn(java.util.Optional.of(src));
        when(mirrorTargetRepository.findBySourceIdAndEnabledTrue(42L)).thenReturn(List.of(targetA, targetB));
        when(repository.findByMessageId("msg-targets")).thenReturn(java.util.Optional.empty());

        DiscordRawMessageRequest req = baseRequest("msg-targets");
        service.recordMessage(req);

        verify(mirror).mirrorAsync(
                eq("enc-a"),
                eq("陳哥"),
                eq("chenge -> target-a"),
                any(DiscordRawMessage.class),
                isNull());
        verify(mirror).mirrorAsync(
                eq("enc-b"),
                eq("陳哥"),
                eq("chenge -> target-b"),
                any(DiscordRawMessage.class),
                isNull());
    }

    @Test
    @DisplayName("recordMessage_noSourceFound_skipsMirror: 找不到 source 不送（不該丟例外）")
    void recordMessage_noSourceFound_skipsMirror() {
        when(sourceRepository.findByChannelId("unknown-ch")).thenReturn(java.util.Optional.empty());
        when(repository.findByMessageId("msg-no-source")).thenReturn(java.util.Optional.empty());

        DiscordRawMessageRequest req = baseRequest("msg-no-source");
        req.setChannelId("unknown-ch");

        // 不該拋例外
        service.recordMessage(req);

        verify(mirror, org.mockito.Mockito.never()).mirrorAsync(any(), any(), any());
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
        // since 應該是大約 7 天前。Service 用 AppConstants.ZONE_ID (Asia/Taipei) 算 now()，
        // 測試也必須用同一個 ZoneId，否則 CI 跑 UTC 環境會差 8 小時導致 fail。
        LocalDateTime expectedRoughly = LocalDateTime.now(AppConstants.ZONE_ID).minusDays(7);
        assertThat(sinceCaptor.getValue()).isBetween(
                expectedRoughly.minusHours(2),
                expectedRoughly.plusHours(2)
        );
    }
}
