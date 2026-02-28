package com.trader.notification.controller;

import com.trader.notification.service.LineLinkingService;
import com.trader.shared.config.LineConfig;
import org.junit.jupiter.api.*;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * LineWebhookController 單元測試
 *
 * 覆蓋：簽名驗證、空事件 200、follow/unfollow/message 路由
 */
class LineWebhookControllerTest {

    private LineConfig lineConfig;
    private LineLinkingService lineLinkingService;
    private LineWebhookController controller;

    private static final String CHANNEL_SECRET = "test-channel-secret-12345";

    @BeforeEach
    void setUp() {
        lineConfig = mock(LineConfig.class);
        lineLinkingService = mock(LineLinkingService.class);

        when(lineConfig.getChannelSecret()).thenReturn(CHANNEL_SECRET);

        controller = new LineWebhookController(lineConfig, lineLinkingService);
    }

    /**
     * 計算正確的 HMAC-SHA256 簽名
     */
    private String computeSignature(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(CHANNEL_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    // ==================== 簽名驗證 ====================

    @Nested
    @DisplayName("簽名驗證")
    class SignatureVerificationTests {

        @Test
        @DisplayName("無效簽名 → 400")
        void invalidSignatureReturns400() {
            String body = "{\"events\":[]}";

            ResponseEntity<Void> response = controller.handleWebhook("invalid-signature", body);

            assertThat(response.getStatusCode().value()).isEqualTo(400);
        }

        @Test
        @DisplayName("有效簽名 + 空事件 → 200（驗證 ping）")
        void validSignatureEmptyEventsReturns200() throws Exception {
            String body = "{\"events\":[]}";
            String signature = computeSignature(body);

            ResponseEntity<Void> response = controller.handleWebhook(signature, body);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verifyNoInteractions(lineLinkingService);
        }
    }

    // ==================== 事件路由 ====================

    @Nested
    @DisplayName("事件路由")
    class EventRoutingTests {

        @Test
        @DisplayName("follow 事件 → 呼叫 handleFollow")
        void followEventRoutes() throws Exception {
            String body = """
                    {"events":[{
                      "type": "follow",
                      "source": {"userId": "Uabc123"},
                      "replyToken": "reply-token-1"
                    }]}""";
            String signature = computeSignature(body);

            ResponseEntity<Void> response = controller.handleWebhook(signature, body);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(lineLinkingService).handleFollow("Uabc123", "reply-token-1");
        }

        @Test
        @DisplayName("unfollow 事件 → 呼叫 handleUnfollow")
        void unfollowEventRoutes() throws Exception {
            String body = """
                    {"events":[{
                      "type": "unfollow",
                      "source": {"userId": "Uabc123"}
                    }]}""";
            String signature = computeSignature(body);

            ResponseEntity<Void> response = controller.handleWebhook(signature, body);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(lineLinkingService).handleUnfollow("Uabc123");
        }

        @Test
        @DisplayName("text message 事件 → 呼叫 handleMessage")
        void textMessageEventRoutes() throws Exception {
            String body = """
                    {"events":[{
                      "type": "message",
                      "source": {"userId": "Uabc123"},
                      "replyToken": "reply-token-2",
                      "message": {"type": "text", "text": "ABCD1234"}
                    }]}""";
            String signature = computeSignature(body);

            ResponseEntity<Void> response = controller.handleWebhook(signature, body);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            verify(lineLinkingService).handleMessage("Uabc123", "ABCD1234", "reply-token-2");
        }
    }
}
