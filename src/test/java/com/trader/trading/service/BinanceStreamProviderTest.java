package com.trader.trading.service;

import com.trader.shared.config.BinanceConfig;
import okhttp3.*;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;

import java.io.IOException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * BinanceStreamProvider 單元測試
 *
 * 覆蓋：
 * - createListenKey — 成功/失敗
 * - keepAlive — 成功/失敗
 * - connect — WebSocket 建立
 * - exchangeName = "BINANCE"
 */
class BinanceStreamProviderTest {

    private BinanceConfig binanceConfig;
    private BinanceStreamProvider provider;
    private MockWebServer mockServer;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();
        String baseUrl = mockServer.url("").toString();
        // 去掉尾端的 /
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        binanceConfig = mock(BinanceConfig.class);
        when(binanceConfig.getBaseUrl()).thenReturn(baseUrl);
        when(binanceConfig.getWsBaseUrl()).thenReturn("wss://fstream.binance.com/ws/");

        provider = new BinanceStreamProvider(new OkHttpClient(), binanceConfig);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    @DisplayName("exchangeName = BINANCE")
    void exchangeNameIsBinance() {
        assertThat(provider.getExchangeName()).isEqualTo("BINANCE");
    }

    @Nested
    @DisplayName("createListenKey")
    class CreateListenKeyTests {

        @Test
        @DisplayName("成功 — 回傳 listenKey")
        void success() {
            mockServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"listenKey\":\"test-listen-key-123\"}"));

            String listenKey = provider.createListenKey("my-api-key");

            assertThat(listenKey).isEqualTo("test-listen-key-123");
        }

        @Test
        @DisplayName("失敗 — 拋 RuntimeException")
        void failure() {
            mockServer.enqueue(new MockResponse()
                    .setResponseCode(400)
                    .setBody("{\"code\":-1102,\"msg\":\"bad request\"}"));

            assertThatThrownBy(() -> provider.createListenKey("bad-key"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("建立 listenKey 失敗");
        }
    }

    @Nested
    @DisplayName("keepAlive")
    class KeepAliveTests {

        @Test
        @DisplayName("成功 — 回傳 200")
        void successReturns200() {
            mockServer.enqueue(new MockResponse().setResponseCode(200));

            int code = provider.keepAliveListenKey("api-key", "listen-key");

            assertThat(code).isEqualTo(200);
        }

        @Test
        @DisplayName("API Key 無效 — 回傳 401")
        void invalidKeyReturns401() {
            mockServer.enqueue(new MockResponse().setResponseCode(401));

            int code = provider.keepAliveListenKey("bad-key", "listen-key");

            assertThat(code).isEqualTo(401);
        }

        @Test
        @DisplayName("connectionContext 為 null — keepAlive 回傳 -1")
        void nullContextReturns() {
            int code = provider.keepAlive("api-key", null);
            assertThat(code).isEqualTo(-1);
        }
    }

    @Nested
    @DisplayName("connect — WebSocket 連線")
    class ConnectTests {

        @Test
        @DisplayName("connect 使用 listenKey 建立 WS URL")
        void connectBuildsWsUrl() {
            // 先 enqueue listenKey 回應
            mockServer.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("{\"listenKey\":\"abc123\"}"));

            OkHttpClient wsClient = mock(OkHttpClient.class);
            WebSocket mockWs = mock(WebSocket.class);
            when(wsClient.newWebSocket(any(Request.class), any(WebSocketListener.class))).thenReturn(mockWs);

            ExchangeStreamProvider.ConnectResult result = provider.connect(
                    "key", "secret", wsClient, mock(WebSocketListener.class));

            assertThat(result.webSocket()).isEqualTo(mockWs);
            assertThat(result.connectionContext()).isEqualTo("abc123");
        }
    }
}
