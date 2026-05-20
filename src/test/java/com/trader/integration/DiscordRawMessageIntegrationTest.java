package com.trader.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeRequest;
import com.trader.shared.util.AesEncryptionUtil;
import com.trader.subscription.entity.Subscription;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.trading.entity.DiscordRawMessage;
import com.trader.trading.entity.Signal;
import com.trader.trading.repository.DiscordRawMessageRepository;
import com.trader.trading.repository.SignalRepository;
import com.trader.trading.service.DiscordRawMessageCleanupTask;
import com.trader.trading.service.DiscordRawMessageService;
import com.trader.user.entity.User;
import com.trader.user.entity.UserApiKey;
import com.trader.user.repository.UserApiKeyRepository;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DiscordRawMessage Integration Test — 端到端 audit + 漏單偵測鏈路
 *
 * 涵蓋場景：
 * 1. persistsToDb: POST /api/discord-messages → DB 寫入
 * 2. upsertsOnDuplicate: 同 message_id 第二次 POST → 更新 parser_action（不新增列）
 * 3. linksSignalIfActionMatches: 先 broadcast-trade 建 Signal → archive POST 帶 ENTRY → 自動連結 signal_id
 * 4. reverseLinking: archive 先到 → broadcast-trade 後到 → signal_id 由 SignalRecordService 反向回填
 * 5. findMissedSignalsAuditQuery: 漏單偵測查詢只回傳 author + 無 signal_id + 無 parser_action 的列
 */
@DisplayName("DiscordRawMessage Integration Test — Audit + 漏單偵測鏈路")
class DiscordRawMessageIntegrationTest extends BaseIntegrationTest {

    @Autowired private DiscordRawMessageRepository discordRawMessageRepository;
    @Autowired private SignalRepository signalRepository;
    @Autowired private DiscordRawMessageService discordRawMessageService;
    @Autowired private DiscordRawMessageCleanupTask discordRawMessageCleanupTask;
    @Autowired private UserRepository userRepository;
    @Autowired private UserApiKeyRepository userApiKeyRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private AesEncryptionUtil aesEncryptionUtil;

    private static final String MONITOR_API_KEY = "test-monitor-key";

    // 清庫由 BaseIntegrationTest.@AfterEach 統一處理（TransactionTemplate）

    // ==================== Helpers ====================

    private String archivePayload(String messageId, String parserAction) {
        return """
                {
                  "message_id": "%s",
                  "channel_id": "ch-integration",
                  "channel_name": "vip",
                  "guild_id": "g-1",
                  "author_name": "陳哥",
                  "message_timestamp": "2026-05-11T10:00:00Z",
                  "content": "BTC 多單 60000",
                  "has_attachments": false,
                  "attachment_count": 0,
                  "has_embed_images": false,
                  "has_reference": false,
                  "parser_action": %s
                }
                """.formatted(messageId, parserAction == null ? "null" : "\"" + parserAction + "\"");
    }

    /** Reuse BroadcastTradeServiceIntegrationTest fixture pattern */
    private String loadBroadcastFixture(String name, String overrideMessageId) throws Exception {
        String content = Files.readString(Path.of("tests/fixtures/payloads/" + name));
        JsonNode node = objectMapper.readTree(content);
        ObjectNode root = (ObjectNode) node;
        root.remove("signal_timestamp");
        if (root.has("source")) {
            ObjectNode src = (ObjectNode) root.get("source");
            src.put("message_id", overrideMessageId);
        }
        return objectMapper.writeValueAsString(root);
    }

    private void seedFullUser(String userId, String email) {
        User user = User.builder()
                .userId(userId)
                .email(email)
                .name(email)
                .passwordHash("$2a$10$dummyHashValueForIntegrationTest")
                .role(User.Role.USER)
                .enabled(true)
                .autoTradeEnabled(true)
                .build();
        userRepository.saveAndFlush(user);

        UserApiKey key = UserApiKey.builder()
                .userId(userId)
                .exchange("BINANCE")
                .encryptedApiKey(aesEncryptionUtil.encrypt("dummy-api-key-" + userId))
                .encryptedSecretKey(aesEncryptionUtil.encrypt("dummy-secret-" + userId))
                .build();
        userApiKeyRepository.saveAndFlush(key);

        Subscription sub = Subscription.builder()
                .userId(userId)
                .planId("pro")
                .status(Subscription.Status.ACTIVE)
                .currentPeriodStart(LocalDateTime.now().minusDays(1))
                .currentPeriodEnd(LocalDateTime.now().plusDays(30))
                .build();
        subscriptionRepository.saveAndFlush(sub);
    }

    private OrderResult successResult() {
        return OrderResult.builder()
                .success(true)
                .orderId("ORDER-" + UUID.randomUUID().toString().substring(0, 8))
                .symbol("BTCUSDT")
                .side("SHORT")
                .price(82200.0)
                .quantity(0.01)
                .commission(0.05)
                .build();
    }

    // ==================== Tests ====================

    @Test
    @DisplayName("postDiscordMessage_persistsToDb: 一次 POST → 寫入 discord_raw_messages")
    void postDiscordMessage_persistsToDb() throws Exception {
        String payload = archivePayload("msg-int-001", "INFO");

        mockMvc.perform(post("/api/discord-messages")
                        .header("X-Api-Key", MONITOR_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message_id").value("msg-int-001"));

        List<DiscordRawMessage> all = discordRawMessageRepository.findAll();
        assertThat(all).hasSize(1);
        DiscordRawMessage drm = all.get(0);
        assertThat(drm.getMessageId()).isEqualTo("msg-int-001");
        assertThat(drm.getSourceChannelId()).isEqualTo("ch-integration");
        assertThat(drm.getSourceAuthorName()).isEqualTo("陳哥");
        assertThat(drm.getParserAction()).isEqualTo("INFO");
        assertThat(drm.getSignalId()).isNull();
    }

    @Test
    @DisplayName("postDiscordMessage_upsertsOnDuplicate: 同 message_id 第二次 → UPSERT 更新 parser_action")
    void postDiscordMessage_upsertsOnDuplicate() throws Exception {
        String first = archivePayload("msg-int-dup", null);
        String second = archivePayload("msg-int-dup", "INFO");

        mockMvc.perform(post("/api/discord-messages")
                        .header("X-Api-Key", MONITOR_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(first))
                .andExpect(status().isOk());

        long countAfterFirst = discordRawMessageRepository.count();
        assertThat(countAfterFirst).isEqualTo(1);

        mockMvc.perform(post("/api/discord-messages")
                        .header("X-Api-Key", MONITOR_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(second))
                .andExpect(status().isOk());

        // 仍只有 1 列（UPSERT，非新增）
        assertThat(discordRawMessageRepository.count()).isEqualTo(1);
        DiscordRawMessage drm = discordRawMessageRepository.findByMessageId("msg-int-dup").orElseThrow();
        assertThat(drm.getParserAction()).isEqualTo("INFO");
    }

    @Test
    @DisplayName("postDiscordMessage_linksSignalIfActionMatches: 先 broadcast-trade → archive POST 帶 ENTRY → 連結 signal_id")
    void postDiscordMessage_linksSignalIfActionMatches() throws Exception {
        seedFullUser("user-link-1", "link1@hookfi.com");
        when(binanceFuturesService.executeSignalForBroadcast(any(TradeRequest.class), any(String.class)))
                .thenReturn(List.of(successResult()));

        // Step 1: broadcast-trade 建 Signal
        String messageId = "msg-link-001";
        String broadcastPayload = loadBroadcastFixture("image-entry.json", messageId);
        mockMvc.perform(post("/api/broadcast-trade")
                        .header("X-Api-Key", MONITOR_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(broadcastPayload))
                .andExpect(status().isOk());

        List<Signal> signals = signalRepository.findAll();
        assertThat(signals).hasSize(1);
        String expectedSignalId = signals.get(0).getSignalId();

        // Step 2: archive POST 帶相同 message_id + parser_action=ENTRY → 自動連結
        String archivePayload = archivePayload(messageId, "ENTRY");
        mockMvc.perform(post("/api/discord-messages")
                        .header("X-Api-Key", MONITOR_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archivePayload))
                .andExpect(status().isOk());

        DiscordRawMessage drm = discordRawMessageRepository.findByMessageId(messageId).orElseThrow();
        assertThat(drm.getSignalId()).isEqualTo(expectedSignalId);
    }

    @Test
    @DisplayName("postDiscordMessage_reverseLinking: archive 先到 → broadcast-trade 後到 → signal_id 被反向回填")
    void postDiscordMessage_reverseLinking() throws Exception {
        seedFullUser("user-link-2", "link2@hookfi.com");
        when(binanceFuturesService.executeSignalForBroadcast(any(TradeRequest.class), any(String.class)))
                .thenReturn(List.of(successResult()));

        String messageId = "msg-reverse-001";

        // Step 1: archive 先到（parser_action=ENTRY 但 signals 表還沒有對應）
        String archivePayload = archivePayload(messageId, "ENTRY");
        mockMvc.perform(post("/api/discord-messages")
                        .header("X-Api-Key", MONITOR_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(archivePayload))
                .andExpect(status().isOk());

        DiscordRawMessage drmBefore = discordRawMessageRepository.findByMessageId(messageId).orElseThrow();
        assertThat(drmBefore.getSignalId()).as("archive 先到 → signal_id 應為 null").isNull();

        // Step 2: broadcast-trade 建立 Signal → SignalRecordService 應反向回填 signal_id
        String broadcastPayload = loadBroadcastFixture("image-entry.json", messageId);
        mockMvc.perform(post("/api/broadcast-trade")
                        .header("X-Api-Key", MONITOR_API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(broadcastPayload))
                .andExpect(status().isOk());

        List<Signal> signals = signalRepository.findAll();
        assertThat(signals).hasSize(1);
        String expectedSignalId = signals.get(0).getSignalId();

        // Step 3: 應該被反向連結
        DiscordRawMessage drmAfter = discordRawMessageRepository.findByMessageId(messageId).orElseThrow();
        assertThat(drmAfter.getSignalId())
                .as("SignalRecordService 寫入 Signal 後應反向連結 discord_raw_messages.signal_id")
                .isEqualTo(expectedSignalId);
    }

    @Test
    @DisplayName("findMissedSignalsAuditQuery_returnsCorrectRows: 漏單偵測只返回 author + 無 signal_id + 無 parser_action 的列")
    void findMissedSignalsAuditQuery_returnsCorrectRows() {
        LocalDateTime now = LocalDateTime.now();

        // missed: author=三马哥, signal_id=null, parser_action=null
        DiscordRawMessage missed1 = DiscordRawMessage.builder()
                .messageId("missed-1")
                .sourceChannelId("ch-x")
                .sourceAuthorName("三马哥")
                .messageTimestamp(now.minusDays(1))
                .parserAction(null)
                .signalId(null)
                .build();
        DiscordRawMessage missed2 = DiscordRawMessage.builder()
                .messageId("missed-2")
                .sourceChannelId("ch-x")
                .sourceAuthorName("三马哥")
                .messageTimestamp(now.minusDays(2))
                .parserAction(null)
                .signalId(null)
                .build();
        // processed: 有 parser_action → 不應出現
        DiscordRawMessage processed = DiscordRawMessage.builder()
                .messageId("processed-1")
                .sourceChannelId("ch-x")
                .sourceAuthorName("三马哥")
                .messageTimestamp(now.minusDays(1))
                .parserAction("ENTRY")
                .signalId("sig-1")
                .build();
        // other author: → 不應出現
        DiscordRawMessage other = DiscordRawMessage.builder()
                .messageId("other-1")
                .sourceChannelId("ch-y")
                .sourceAuthorName("陳哥")
                .messageTimestamp(now.minusDays(1))
                .parserAction(null)
                .signalId(null)
                .build();
        // too old: 超過 window → 不應出現
        DiscordRawMessage tooOld = DiscordRawMessage.builder()
                .messageId("too-old-1")
                .sourceChannelId("ch-x")
                .sourceAuthorName("三马哥")
                .messageTimestamp(now.minusDays(30))
                .parserAction(null)
                .signalId(null)
                .build();

        discordRawMessageRepository.saveAll(List.of(missed1, missed2, processed, other, tooOld));

        List<DiscordRawMessage> result = discordRawMessageService.findMissedSignals("三马哥", 7);

        assertThat(result).extracting(DiscordRawMessage::getMessageId)
                .containsExactlyInAnyOrder("missed-1", "missed-2");
    }

    @Test
    @DisplayName("cleanup_deletesOldButKeepsRecent: 清理任務只刪 > 180 天的列，保留近期")
    void cleanup_deletesOldButKeepsRecent() {
        LocalDateTime now = LocalDateTime.now();

        DiscordRawMessage old = DiscordRawMessage.builder()
                .messageId("old-msg")
                .sourceChannelId("ch-cleanup")
                .sourceAuthorName("陳哥")
                .messageTimestamp(now.minusDays(200))
                .build();
        DiscordRawMessage middle = DiscordRawMessage.builder()
                .messageId("middle-msg")
                .sourceChannelId("ch-cleanup")
                .sourceAuthorName("陳哥")
                .messageTimestamp(now.minusDays(100))
                .build();
        DiscordRawMessage recent = DiscordRawMessage.builder()
                .messageId("recent-msg")
                .sourceChannelId("ch-cleanup")
                .sourceAuthorName("陳哥")
                .messageTimestamp(now)
                .build();
        discordRawMessageRepository.saveAllAndFlush(List.of(old, middle, recent));

        discordRawMessageCleanupTask.cleanupExpiredMessages();
        // saveAllAndFlush 已 flush；clear 是為了 evict stale managed entities，
        // 不需 active transaction（純記憶體操作）
        entityManager.clear();

        List<DiscordRawMessage> remaining = discordRawMessageRepository.findAll();
        assertThat(remaining).hasSize(2);
        assertThat(remaining.stream().map(DiscordRawMessage::getMessageId).toList())
                .containsExactlyInAnyOrder("middle-msg", "recent-msg");
    }

    @Test
    @DisplayName("recordMessage_concurrentUpdate_throwsOptimisticLockException: 第二次以 stale version 寫入應拋 OptimisticLockException")
    void recordMessage_concurrentUpdate_throwsOptimisticLockException() {
        // setup: 插入一筆，version 應為 0
        DiscordRawMessage initial = DiscordRawMessage.builder()
                .messageId("msg-optlock-1")
                .sourceChannelId("ch-optlock")
                .sourceAuthorName("陳哥")
                .messageTimestamp(LocalDateTime.now())
                .parserAction(null)
                .build();
        DiscordRawMessage saved = discordRawMessageRepository.saveAndFlush(initial);

        // 模擬 stale read：複製整個物件，version 還是當下的 saved.version
        DiscordRawMessage stale = new DiscordRawMessage();
        BeanUtils.copyProperties(saved, stale);

        // 第一次寫入（fresh）— version 會被 JPA 增加
        saved.setParserAction("ENTRY");
        discordRawMessageRepository.saveAndFlush(saved);

        // 清掉 persistence context，避免 JPA 把 stale 視為已 managed 物件而合併
        // saveAndFlush 已 flush；clear 是純記憶體操作不需 active transaction
        entityManager.clear();

        // 第二次以 stale version 寫入應該失敗
        stale.setParserAction("CLOSE");
        assertThatThrownBy(() -> discordRawMessageRepository.saveAndFlush(stale))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }
}
