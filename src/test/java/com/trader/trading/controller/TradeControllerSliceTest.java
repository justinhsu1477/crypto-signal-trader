package com.trader.trading.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.trader.auth.config.AuthConfig;
import com.trader.auth.filter.JwtAuthenticationFilter;
import com.trader.auth.filter.MonitorApiKeyFilter;
import com.trader.auth.handler.CustomAccessDeniedHandler;
import com.trader.auth.handler.CustomAuthenticationEntryPoint;
import com.trader.auth.service.JwtService;
import com.trader.auth.util.ClientIpResolver;
import com.trader.notification.service.NotificationService;
import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.TradeRequest;
import com.trader.shared.service.AuditService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.BinanceUserDataStreamService;
import com.trader.trading.service.BroadcastTradeService;
import com.trader.trading.service.MonitorHeartbeatService;
import com.trader.trading.service.SignalDeduplicationService;
import com.trader.trading.service.SignalMetrics;
import com.trader.trading.service.SignalParserService;
import com.trader.trading.service.SignalRecordService;
import com.trader.trading.service.SymbolLockRegistry;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TradeController Slice Test
 *
 * 用 @WebMvcTest 只載入 controller + MockMvc + Spring Security 設定，
 * 不啟 Docker、不連 DB，不跑 full application context。
 *
 * 涵蓋三組關注點（單元測試/整合測試之間的中間層）：
 * - Group A Validation: 缺欄位、壞 JSON → 預期 4xx
 * - Group B Auth: 缺 / 錯 X-Api-Key → security filter 攔截
 * - Group C Routing: 合法請求 → 真的進到 service，且 payload 結構保留（含 nested attachment.sha256）
 *
 * 與 SecurityFilterChainSliceTest 不同：那支驗證 path-level rules，這支驗證
 * 「TradeController 端點 wiring 真的正確接到 Spring（routing / Jackson / validation / security）」。
 */
@WebMvcTest(controllers = TradeController.class)
@Import({AuthConfig.class, MonitorApiKeyFilter.class, JwtAuthenticationFilter.class,
        CustomAuthenticationEntryPoint.class, CustomAccessDeniedHandler.class})
@TestPropertySource(properties = {
        "monitor.api-key=test-monitor-key",
        "jwt.secret=test-secret-key-for-slice-test-minimum-256-bits-long-enough",
        "jwt.expiration-ms=1800000",
        "jwt.refresh-expiration-ms=259200000"
})
@DisplayName("TradeController — Slice Test (@WebMvcTest)")
class TradeControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ===== TradeController 依賴 =====
    @MockBean private BinanceFuturesService binanceFuturesService;
    @MockBean private BroadcastTradeService broadcastTradeService;
    @MockBean private SignalParserService signalParserService;
    @MockBean private RiskConfig riskConfig;
    @MockBean private TradeRecordService tradeRecordService;
    @MockBean private SignalDeduplicationService deduplicationService;
    @MockBean private NotificationService webhookService;
    @MockBean private MonitorHeartbeatService heartbeatService;
    @MockBean private BinanceUserDataStreamService userDataStreamService;
    @MockBean private SignalRecordService signalRecordService;
    @MockBean private SymbolLockRegistry symbolLockRegistry;
    @MockBean private MultiUserConfig multiUserConfig;
    @MockBean private SignalMetrics signalMetrics;
    @MockBean private com.trader.trading.service.SuspiciousClosePayloadGuard suspiciousClosePayloadGuard;

    // ===== Filter / Handler 依賴（AuthConfig & JwtAuthenticationFilter 需要） =====
    @MockBean private JwtService jwtService;
    @MockBean private UserRepository userRepository;
    @MockBean private AuditService auditService;
    @MockBean private ClientIpResolver clientIpResolver;

    private static final String MONITOR_API_KEY = "test-monitor-key";

    @BeforeEach
    void setUp() {
        // RiskConfig 白名單預設通過 BTCUSDT（fixture 內用的）
        when(riskConfig.isSymbolAllowed("BTCUSDT")).thenReturn(true);
        when(riskConfig.getAllowedSymbols()).thenReturn(List.of("BTCUSDT", "ETHUSDT"));
        // message_id 永久去重預設不擋
        when(signalRecordService.isMessageIdProcessed(any())).thenReturn(false);
    }

    /**
     * 讀 fixture 並移除 signal_timestamp（fixture 寫死 1747000000000 早已過期 5 分鐘上限）。
     * 否則會被 controller staleness check 攔截，無法觀察到下游 service 行為。
     */
    private String loadFixtureWithoutStaleTimestamp(String name) throws Exception {
        String content = Files.readString(Path.of("tests/fixtures/payloads/" + name));
        JsonNode node = objectMapper.readTree(content);
        ObjectNode root = (ObjectNode) node;
        root.remove("signal_timestamp");
        return objectMapper.writeValueAsString(root);
    }

    // ====================================================================
    // Group A: Request validation
    // ====================================================================

    @Nested
    @DisplayName("Group A: Request Validation")
    class ValidationTests {

        @Test
        @DisplayName("postBroadcastTradeWithMissingActionReturns400()")
        void postBroadcastTradeWithMissingActionReturns400() throws Exception {
            // arrange — 拿掉 action 欄位的合法 JSON
            String payload = """
                    {
                      "symbol": "BTCUSDT",
                      "side": "SHORT",
                      "entry_price": 82200,
                      "stop_loss": 83800
                    }
                    """;

            // act + assert
            mockMvc.perform(post("/api/broadcast-trade")
                            .header("X-Api-Key", MONITOR_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest())
                    .andExpect(result -> {
                        String body = result.getResponse().getContentAsString();
                        assertThat(body)
                                .as("response body should mention 'action'")
                                .containsIgnoringCase("action");
                    });

            // 同時確認 service 沒被誤呼叫
            verify(broadcastTradeService, never()).broadcastTrade(any());
        }

        @Test
        @DisplayName("postBroadcastTradeWithInvalidJsonReturns400()")
        void postBroadcastTradeWithInvalidJsonReturns400() throws Exception {
            // arrange — 故意壞掉的 JSON
            String malformed = "{not json";

            // act + assert: Jackson 反序列化失敗 → Spring MVC 應該回 400
            mockMvc.perform(post("/api/broadcast-trade")
                            .header("X-Api-Key", MONITOR_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(malformed))
                    .andExpect(status().isBadRequest());

            verify(broadcastTradeService, never()).broadcastTrade(any());
        }
    }

    // ====================================================================
    // Group B: Authentication (Security filter chain)
    // ====================================================================

    @Nested
    @DisplayName("Group B: Auth")
    class AuthTests {

        @Test
        @DisplayName("postBroadcastTradeWithoutApiKeyReturns401or403()")
        void postBroadcastTradeWithoutApiKeyReturns401or403() throws Exception {
            String payload = loadFixtureWithoutStaleTimestamp("text-entry.json");

            mockMvc.perform(post("/api/broadcast-trade")
                            // 故意不帶 X-Api-Key
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assertThat(status)
                                .as("missing API key should be 401 or 403, got " + status)
                                .isIn(401, 403);
                    });

            verify(broadcastTradeService, never()).broadcastTrade(any());
        }

        @Test
        @DisplayName("postBroadcastTradeWithWrongApiKeyReturns401or403()")
        void postBroadcastTradeWithWrongApiKeyReturns401or403() throws Exception {
            String payload = loadFixtureWithoutStaleTimestamp("text-entry.json");

            mockMvc.perform(post("/api/broadcast-trade")
                            .header("X-Api-Key", "wrong-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assertThat(status)
                                .as("wrong API key should be 401 or 403, got " + status)
                                .isIn(401, 403);
                    });

            verify(broadcastTradeService, never()).broadcastTrade(any());
        }
    }

    // ====================================================================
    // Group C: Routing + happy path (controller → service)
    // ====================================================================

    @Nested
    @DisplayName("Group C: Routing + Happy Path")
    class RoutingTests {

        @Test
        @DisplayName("postBroadcastTradeWithValidPayloadReachesService()")
        void postBroadcastTradeWithValidPayloadReachesService() throws Exception {
            // arrange
            when(broadcastTradeService.broadcastTrade(any(TradeRequest.class)))
                    .thenReturn(Map.<String, Object>of(
                            "status", "COMPLETED",
                            "totalUsers", 1,
                            "successCount", 1,
                            "failCount", 0));

            String payload = loadFixtureWithoutStaleTimestamp("text-entry.json");

            // act
            mockMvc.perform(post("/api/broadcast-trade")
                            .header("X-Api-Key", MONITOR_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk());

            // assert: service 真的被呼叫一次
            ArgumentCaptor<TradeRequest> captor = ArgumentCaptor.forClass(TradeRequest.class);
            verify(broadcastTradeService, times(1)).broadcastTrade(captor.capture());

            TradeRequest captured = captor.getValue();
            assertThat(captured.getAction()).isEqualTo("ENTRY");
            assertThat(captured.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(captured.getSide()).isEqualTo("SHORT");
            assertThat(captured.getEntryPrice()).isEqualTo(82200.0);
        }

        @Test
        @DisplayName("postBroadcastTradeWithImagePayloadPreservesAttachmentSha256AtControllerLayer()")
        void postBroadcastTradeWithImagePayloadPreservesAttachmentSha256AtControllerLayer() throws Exception {
            // arrange
            when(broadcastTradeService.broadcastTrade(any(TradeRequest.class)))
                    .thenReturn(Map.<String, Object>of(
                            "status", "COMPLETED",
                            "totalUsers", 1,
                            "successCount", 1,
                            "failCount", 0));

            String payload = loadFixtureWithoutStaleTimestamp("image-entry.json");

            // act
            mockMvc.perform(post("/api/broadcast-trade")
                            .header("X-Api-Key", MONITOR_API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isOk());

            // assert: 透過 captor 確認 nested attachment.sha256 在 Spring/Jackson layer 真的被解進來
            ArgumentCaptor<TradeRequest> captor = ArgumentCaptor.forClass(TradeRequest.class);
            verify(broadcastTradeService, times(1)).broadcastTrade(captor.capture());

            TradeRequest captured = captor.getValue();
            assertThat(captured.getSource())
                    .as("source 應該被反序列化出來")
                    .isNotNull();
            assertThat(captured.getSource().getAttachmentSha256())
                    .as("nested attachment.sha256 應該被 SignalSource.setAttachment 拍平進來")
                    .isEqualTo("a3b1c8d5e9f2147ba6c3d8e9f10b21c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9");
        }
    }
}
