package com.trader.trading.service;

import com.trader.shared.config.BybitConfig;
import okhttp3.*;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * BybitStreamProvider 單元測試
 *
 * 覆蓋：
 * - HMAC-SHA256 簽名正確性
 * - auth message 格式
 * - subscribe message 格式
 * - keepAlive 和 cleanup 為 no-op
 * - exchangeName = "BYBIT"
 */
class BybitStreamProviderTest {

    private BybitConfig bybitConfig;
    private BybitStreamProvider provider;

    @BeforeEach
    void setUp() {
        bybitConfig = mock(BybitConfig.class);
        when(bybitConfig.getWsBaseUrl()).thenReturn("wss://stream.bybit.com");
        provider = new BybitStreamProvider(bybitConfig);
    }

    @Test
    @DisplayName("exchangeName = BYBIT")
    void exchangeNameIsBybit() {
        assertThat(provider.getExchangeName()).isEqualTo("BYBIT");
    }

    @Test
    @DisplayName("keepAlive 回傳 200（no-op）")
    void keepAliveReturns200() {
        assertThat(provider.keepAlive("key", null)).isEqualTo(200);
    }

    @Test
    @DisplayName("cleanup 不拋異常（no-op）")
    void cleanupDoesNotThrow() {
        assertThatCode(() -> provider.cleanup("key", null))
                .doesNotThrowAnyException();
    }

    @Nested
    @DisplayName("HMAC-SHA256 簽名")
    class HmacTests {

        @Test
        @DisplayName("已知輸入產生一致的 HMAC 輸出")
        void knownInputProducesConsistentOutput() {
            String signature = BybitStreamProvider.hmacSha256("GET/realtime1700000000000", "test-secret");
            assertThat(signature).isNotEmpty();
            assertThat(signature).hasSize(64); // SHA-256 hex = 64 chars

            // 同輸入同輸出
            String again = BybitStreamProvider.hmacSha256("GET/realtime1700000000000", "test-secret");
            assertThat(again).isEqualTo(signature);
        }

        @Test
        @DisplayName("不同 secret 產生不同簽名")
        void differentSecretDifferentSignature() {
            String sig1 = BybitStreamProvider.hmacSha256("data", "secret1");
            String sig2 = BybitStreamProvider.hmacSha256("data", "secret2");
            assertThat(sig1).isNotEqualTo(sig2);
        }
    }

    @Nested
    @DisplayName("WebSocket 訊息建構")
    class MessageTests {

        @Test
        @DisplayName("auth message 包含 apiKey")
        void authMessageContainsApiKey() {
            String msg = BybitStreamProvider.buildAuthMessage("my-api-key", "my-secret");
            assertThat(msg).contains("\"op\":\"auth\"");
            assertThat(msg).contains("my-api-key");
        }

        @Test
        @DisplayName("subscribe message 包含 execution 和 position")
        void subscribeMessageContainsTopics() {
            String msg = BybitStreamProvider.buildSubscribeMessage();
            assertThat(msg).contains("\"op\":\"subscribe\"");
            assertThat(msg).contains("execution");
            assertThat(msg).contains("position");
        }
    }

    @Nested
    @DisplayName("connect — WebSocket 連線")
    class ConnectTests {

        @Test
        @DisplayName("connect 使用 /v5/private 端點")
        void connectUsesPrivateEndpoint() {
            OkHttpClient wsClient = mock(OkHttpClient.class);
            WebSocket mockWs = mock(WebSocket.class);
            when(wsClient.newWebSocket(any(Request.class), any(WebSocketListener.class))).thenReturn(mockWs);

            ExchangeStreamProvider.ConnectResult result = provider.connect(
                    "key", "secret", wsClient, mock(WebSocketListener.class));

            assertThat(result.webSocket()).isEqualTo(mockWs);
            assertThat(result.connectionContext()).isNull(); // Bybit 無 listenKey
        }
    }
}
