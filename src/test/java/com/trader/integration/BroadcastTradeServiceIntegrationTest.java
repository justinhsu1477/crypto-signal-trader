package com.trader.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.trader.shared.model.OrderResult;
import com.trader.shared.model.TradeRequest;
import com.trader.shared.util.AesEncryptionUtil;
import com.trader.subscription.entity.Subscription;
import com.trader.subscription.repository.SubscriptionRepository;
import com.trader.trading.entity.BroadcastLog;
import com.trader.trading.entity.Signal;
import com.trader.trading.repository.BroadcastLogRepository;
import com.trader.trading.repository.SignalRepository;
import com.trader.trading.repository.TradeRepository;
import com.trader.user.entity.User;
import com.trader.user.entity.UserApiKey;
import com.trader.user.repository.UserApiKeyRepository;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BroadcastTradeService 整合測試 — 多用戶廣播跟單核心鏈路
 *
 * 涵蓋場景：
 * 1. Happy Path: 3 用戶全部成功廣播
 * 2. Audit Chain: 圖訊號 sha256 端到端持久化
 * 3. Skip / Failure Isolation: 缺 API Key 跳過、單一失敗不影響其他人
 * 4. Dedup: 同 message_id 兩次請求 → L1 永久去重攔截
 *
 * 真實連線：Controller → Service → JPA → PostgreSQL（Testcontainers）
 * 唯一 Mock：BinanceFuturesService（避免打到真 Binance）
 */
@DisplayName("BroadcastTradeService Integration Test — 廣播跟單鏈路")
class BroadcastTradeServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private UserApiKeyRepository userApiKeyRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SignalRepository signalRepository;
    @Autowired private BroadcastLogRepository broadcastLogRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired private AesEncryptionUtil aesEncryptionUtil;

    /** 已驗證 Monitor API key — 走 X-Api-Key header 即可拿到 ROLE_ADMIN */
    private static final String MONITOR_API_KEY = "test-monitor-key";

    /** 既有所有測試使用同一交易對；以 BTCUSDT 確保通過 RiskConfig 白名單 */
    private static final String SYMBOL = "BTCUSDT";

    @BeforeEach
    @Transactional
    void resetState() {
        // 反相依序清理：trades → broadcast_logs → signals → user_api_keys → subscriptions → users
        entityManager.createNativeQuery("DELETE FROM trades").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM broadcast_logs").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM signals").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM user_api_keys").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM subscriptions").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM users").executeUpdate();
        entityManager.flush();
    }

    @AfterEach
    @Transactional
    void tearDown() {
        // 同樣反相依序，避免殘留影響下個測試
        entityManager.createNativeQuery("DELETE FROM trades").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM broadcast_logs").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM signals").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM user_api_keys").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM subscriptions").executeUpdate();
        entityManager.createNativeQuery("DELETE FROM users").executeUpdate();
        entityManager.flush();
    }

    // ==================== Helpers ====================

    /**
     * 讀取 fixture，並把 message_id 換成隨機值（避免測試之間互相干擾）。
     * 同時移除 signal_timestamp（fixture 寫死的 timestamp 早已過期 5 分鐘上限）。
     */
    private String loadFixture(String name) throws Exception {
        String content = Files.readString(Path.of("tests/fixtures/payloads/" + name));
        JsonNode node = objectMapper.readTree(content);
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) node;
        // 去掉 signal_timestamp（fixture 寫死 1747000000000 早已過期）
        root.remove("signal_timestamp");
        // 為 message_id 套上隨機後綴避免污染
        if (root.has("source")) {
            com.fasterxml.jackson.databind.node.ObjectNode src =
                    (com.fasterxml.jackson.databind.node.ObjectNode) root.get("source");
            String original = src.path("message_id").asText("msg");
            src.put("message_id", original + "-" + UUID.randomUUID().toString().substring(0, 8));
        }
        return objectMapper.writeValueAsString(root);
    }

    /** 建立完整用戶（含 API Key + ACTIVE Subscription），確保通過所有 BroadcastTradeService 篩選 */
    private User seedFullUser(String userId, String email) {
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
        return user;
    }

    /** 建立有訂閱但沒 API Key 的用戶（測 skipping API Key 邏輯） */
    private User seedUserWithoutApiKey(String userId, String email) {
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

        Subscription sub = Subscription.builder()
                .userId(userId)
                .planId("pro")
                .status(Subscription.Status.ACTIVE)
                .currentPeriodStart(LocalDateTime.now().minusDays(1))
                .currentPeriodEnd(LocalDateTime.now().plusDays(30))
                .build();
        subscriptionRepository.saveAndFlush(sub);
        return user;
    }

    private OrderResult successResult() {
        return OrderResult.builder()
                .success(true)
                .orderId("ORDER-" + UUID.randomUUID().toString().substring(0, 8))
                .symbol(SYMBOL)
                .side("SHORT")
                .price(82200.0)
                .quantity(0.01)
                .commission(0.05)
                .build();
    }

    // ==================== Group 1: Happy Path ====================

    @Nested
    @DisplayName("Group 1: Happy Path")
    class HappyPathTests {

        @Test
        @DisplayName("multiUserBroadcastAllSucceed: 3 用戶全部成功 → DB 寫入 signals + broadcast_logs")
        void multiUserBroadcastAllSucceed() throws Exception {
            // Arrange
            seedFullUser("user-1", "u1@hookfi.com");
            seedFullUser("user-2", "u2@hookfi.com");
            seedFullUser("user-3", "u3@hookfi.com");

            when(binanceFuturesService.executeSignalForBroadcast(any(TradeRequest.class), any(String.class)))
                    .thenReturn(List.of(successResult()));

            String payload = loadFixture("text-entry.json");

            // Act
            MvcResult result = mockMvc.perform(post("/api/broadcast-trade")
                            .header("X-Api-Key", MONITOR_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.totalUsers").value(3))
                    .andExpect(jsonPath("$.successCount").value(3))
                    .andExpect(jsonPath("$.failCount").value(0))
                    .andReturn();

            // Assert: Binance 真的被呼叫 3 次（每用戶一次）
            verify(binanceFuturesService, times(3))
                    .executeSignalForBroadcast(any(TradeRequest.class), any(String.class));

            // Assert: signals 表寫入 1 筆
            String requestedMessageId = objectMapper.readTree(payload)
                    .path("source").path("message_id").asText();
            List<Signal> signals = signalRepository.findAll();
            assertThat(signals).hasSize(1);
            Signal signal = signals.get(0);
            assertThat(signal.getSymbol()).isEqualTo(SYMBOL);
            assertThat(signal.getAction()).isEqualTo("ENTRY");
            assertThat(signal.getSourceMessageId()).isEqualTo(requestedMessageId);
            assertThat(signal.getExecutionStatus()).isEqualTo("EXECUTED");

            // Assert: broadcast_logs 寫入 1 筆 with successCount=3
            List<BroadcastLog> logs = broadcastLogRepository.findAll();
            assertThat(logs).hasSize(1);
            BroadcastLog log = logs.get(0);
            assertThat(log.getSuccessCount()).isEqualTo(3);
            assertThat(log.getFailCount()).isEqualTo(0);
            assertThat(log.getTotalUsers()).isEqualTo(3);
            assertThat(log.getSkippedNoSub()).isEqualTo(0);
            assertThat(log.getSkippedNoKey()).isEqualTo(0);
            assertThat(log.getStatus()).isEqualTo("COMPLETED");
            assertThat(log.getSymbol()).isEqualTo(SYMBOL);
        }

        @Test
        @DisplayName("imageEntryPreservesSha256AuditChain: 圖訊號 sha256 端到端持久化")
        void imageEntryPreservesSha256AuditChain() throws Exception {
            // Arrange
            seedFullUser("user-image", "image@hookfi.com");

            when(binanceFuturesService.executeSignalForBroadcast(any(TradeRequest.class), any(String.class)))
                    .thenReturn(List.of(successResult()));

            String payload = loadFixture("image-entry.json");

            // Act
            mockMvc.perform(post("/api/broadcast-trade")
                            .header("X-Api-Key", MONITOR_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.successCount").value(1));

            // Assert: signals 表 attachment_sha256 = fixture 內固定值（驗證從 Python 端到 DB 全程不掉）
            List<Signal> signals = signalRepository.findAll();
            assertThat(signals).hasSize(1);
            assertThat(signals.get(0).getAttachmentSha256())
                    .isEqualTo("a3b1c8d5e9f2147ba6c3d8e9f10b21c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9");
        }
    }

    // ==================== Group 2: Skip / Failure Isolation ====================

    @Nested
    @DisplayName("Group 2: Skip / Failure Isolation")
    class SkipAndFailureTests {

        @Test
        @DisplayName("userWithoutApiKeyIsSkipped: 沒設 API Key 的用戶被跳過")
        void userWithoutApiKeyIsSkipped() throws Exception {
            // Arrange — 2 個有 key、1 個沒 key
            seedFullUser("user-with-key-1", "key1@hookfi.com");
            seedFullUser("user-with-key-2", "key2@hookfi.com");
            seedUserWithoutApiKey("user-no-key", "nokey@hookfi.com");

            when(binanceFuturesService.executeSignalForBroadcast(any(TradeRequest.class), any(String.class)))
                    .thenReturn(List.of(successResult()));

            String payload = loadFixture("text-entry.json");

            // Act
            mockMvc.perform(post("/api/broadcast-trade")
                            .header("X-Api-Key", MONITOR_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.successCount").value(2))
                    .andExpect(jsonPath("$.skippedNoApiKey").value(1));

            // Assert: Binance 只被呼叫 2 次（跳過無 key 用戶）
            verify(binanceFuturesService, times(2))
                    .executeSignalForBroadcast(any(TradeRequest.class), any(String.class));

            // Assert: broadcast_logs skippedNoKey = 1, successCount = 2
            List<BroadcastLog> logs = broadcastLogRepository.findAll();
            assertThat(logs).hasSize(1);
            BroadcastLog log = logs.get(0);
            assertThat(log.getSkippedNoKey()).isEqualTo(1);
            assertThat(log.getSuccessCount()).isEqualTo(2);
            assertThat(log.getFailCount()).isEqualTo(0);
            assertThat(log.getTotalUsers()).isEqualTo(2);
        }

        @Test
        @DisplayName("singleUserFailureDoesNotBreakOthers: 單一用戶失敗不影響其他人")
        void singleUserFailureDoesNotBreakOthers() throws Exception {
            // Arrange — 3 個用戶全部 fully-configured
            seedFullUser("user-ok-1", "ok1@hookfi.com");
            seedFullUser("user-fail", "fail@hookfi.com");
            seedFullUser("user-ok-2", "ok2@hookfi.com");

            // Mock: user-fail 拋例外，其他人成功
            when(binanceFuturesService.executeSignalForBroadcast(any(TradeRequest.class), eq("user-fail")))
                    .thenThrow(new RuntimeException("Binance API error"));
            when(binanceFuturesService.executeSignalForBroadcast(any(TradeRequest.class), eq("user-ok-1")))
                    .thenReturn(List.of(successResult()));
            when(binanceFuturesService.executeSignalForBroadcast(any(TradeRequest.class), eq("user-ok-2")))
                    .thenReturn(List.of(successResult()));

            String payload = loadFixture("text-entry.json");

            // Act
            mockMvc.perform(post("/api/broadcast-trade")
                            .header("X-Api-Key", MONITOR_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.successCount").value(2))
                    .andExpect(jsonPath("$.failCount").value(1));

            // Assert: broadcast_logs 記錄 2 成功 1 失敗
            List<BroadcastLog> logs = broadcastLogRepository.findAll();
            assertThat(logs).hasSize(1);
            BroadcastLog log = logs.get(0);
            assertThat(log.getSuccessCount()).isEqualTo(2);
            assertThat(log.getFailCount()).isEqualTo(1);
            assertThat(log.getTotalUsers()).isEqualTo(3);

            // Assert: userResults JSON 包含 user-fail 的失敗原因
            assertThat(log.getUserResults()).isNotNull();
            assertThat(log.getUserResults()).contains("user-fail");
            assertThat(log.getUserResults()).contains("Binance API error");
        }
    }

    // ==================== Group 3: Dedup ====================

    @Nested
    @DisplayName("Group 3: Dedup")
    class DedupTests {

        @Test
        @DisplayName("sameMessageIdBlockedByL1Dedup: 同 message_id 二次請求 → 不執行廣播")
        void sameMessageIdBlockedByL1Dedup() throws Exception {
            // Arrange
            seedFullUser("user-dedup", "dedup@hookfi.com");

            when(binanceFuturesService.executeSignalForBroadcast(any(TradeRequest.class), any(String.class)))
                    .thenReturn(List.of(successResult()));

            // 注意：兩次 request 使用「同一」payload — 同 message_id
            String payload = loadFixture("text-entry.json");

            // Act 1: 第一次廣播 → 成功
            mockMvc.perform(post("/api/broadcast-trade")
                            .header("X-Api-Key", MONITOR_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.successCount").value(1));

            // 第一次後快照（baseline）
            long signalsAfterFirst = signalRepository.count();
            long broadcastLogsAfterFirst = broadcastLogRepository.count();
            assertThat(signalsAfterFirst).isEqualTo(1);
            assertThat(broadcastLogsAfterFirst).isEqualTo(1);

            // Act 2: 第二次 — 同 message_id，預期 L1 message_id 去重攔截
            mockMvc.perform(post("/api/broadcast-trade")
                            .header("X-Api-Key", MONITOR_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SKIPPED"));

            // Assert: 第二次「沒有」新增 signal、沒有新增 broadcast_log
            assertThat(signalRepository.count()).isEqualTo(signalsAfterFirst);
            assertThat(broadcastLogRepository.count()).isEqualTo(broadcastLogsAfterFirst);

            // Assert: Binance 只被呼叫一次 — 第二次完全被攔截
            verify(binanceFuturesService, times(1))
                    .executeSignalForBroadcast(any(TradeRequest.class), any(String.class));
        }
    }
}
