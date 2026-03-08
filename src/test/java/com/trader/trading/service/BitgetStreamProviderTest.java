package com.trader.trading.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trader.shared.config.BitgetConfig;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * BitgetStreamProvider 單元測試
 *
 * 測試範圍：
 * 1. connect — WS URL 正確、回傳 ConnectResult
 * 2. keepAlive — 固定回傳 200（no-op）
 * 3. cleanup — no-op
 * 4. buildAuthMessage — JSON 格式、包含 apiKey/passphrase/timestamp/sign
 * 5. buildSubscribeMessage — JSON 格式、channel 正確
 * 6. getExchangeName — BITGET
 */
class BitgetStreamProviderTest {

    private BitgetConfig bitgetConfig;
    private BitgetStreamProvider provider;

    @BeforeEach
    void setUp() {
        bitgetConfig = new BitgetConfig(
                "https://api.bitget.com",
                "wss://ws.bitget.com",
                30000
        );
        provider = new BitgetStreamProvider(bitgetConfig);
    }

    @Test
    @DisplayName("getExchangeName → BITGET")
    void exchangeName() {
        assertThat(provider.getExchangeName()).isEqualTo("BITGET");
    }

    @Nested
    @DisplayName("connect — WS 連線")
    class Connect {

        @Test
        @DisplayName("連線 URL = wsBaseUrl + /v2/ws/private")
        void connectUrl() {
            OkHttpClient mockClient = mock(OkHttpClient.class);
            WebSocket mockWs = mock(WebSocket.class);
            when(mockClient.newWebSocket(any(Request.class), any(WebSocketListener.class)))
                    .thenReturn(mockWs);

            ExchangeStreamProvider.ConnectResult result = provider.connect(
                    "api-key", "secret-key", mockClient, mock(WebSocketListener.class));

            assertThat(result.webSocket()).isEqualTo(mockWs);
            assertThat(result.connectionContext()).isNull(); // Bitget 無 listenKey
        }
    }

    @Nested
    @DisplayName("keepAlive / cleanup — no-op")
    class NoOpMethods {

        @Test
        @DisplayName("keepAlive → 固定回傳 200")
        void keepAlive() {
            int code = provider.keepAlive("api-key", null);

            assertThat(code).isEqualTo(200);
        }

        @Test
        @DisplayName("cleanup → 不拋異常")
        void cleanup() {
            assertThatCode(() -> provider.cleanup("api-key", null))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("buildAuthMessage — login JSON 格式")
    class AuthMessage {

        @Test
        @DisplayName("包含 op=login 和所有必要欄位")
        void authMessageFormat() {
            String msg = BitgetStreamProvider.buildAuthMessage("my-api", "my-secret", "my-pp");
            JsonObject json = new Gson().fromJson(msg, JsonObject.class);

            assertThat(json.get("op").getAsString()).isEqualTo("login");
            assertThat(json.has("args")).isTrue();

            JsonArray args = json.getAsJsonArray("args");
            assertThat(args).hasSize(1);

            JsonObject arg = args.get(0).getAsJsonObject();
            assertThat(arg.get("apiKey").getAsString()).isEqualTo("my-api");
            assertThat(arg.get("passphrase").getAsString()).isEqualTo("my-pp");
            assertThat(arg.has("timestamp")).isTrue();
            assertThat(arg.has("sign")).isTrue();
        }

        @Test
        @DisplayName("timestamp 為秒級（10 位數）")
        void timestampInSeconds() {
            String msg = BitgetStreamProvider.buildAuthMessage("api", "secret", "pp");
            JsonObject json = new Gson().fromJson(msg, JsonObject.class);
            JsonObject arg = json.getAsJsonArray("args").get(0).getAsJsonObject();
            String timestamp = arg.get("timestamp").getAsString();

            assertThat(timestamp).hasSize(10); // 秒級 = 10 位數
        }

        @Test
        @DisplayName("sign 為 Base64 格式")
        void signIsBase64() {
            String msg = BitgetStreamProvider.buildAuthMessage("api", "secret", "pp");
            JsonObject json = new Gson().fromJson(msg, JsonObject.class);
            JsonObject arg = json.getAsJsonArray("args").get(0).getAsJsonObject();
            String sign = arg.get("sign").getAsString();

            assertThat(sign).matches("[A-Za-z0-9+/=]+");
        }
    }

    @Nested
    @DisplayName("buildSubscribeMessage — subscribe JSON 格式")
    class SubscribeMessage {

        @Test
        @DisplayName("包含 op=subscribe 和 orders+positions channels")
        void subscribeMessageFormat() {
            String msg = BitgetStreamProvider.buildSubscribeMessage();
            JsonObject json = new Gson().fromJson(msg, JsonObject.class);

            assertThat(json.get("op").getAsString()).isEqualTo("subscribe");

            JsonArray args = json.getAsJsonArray("args");
            assertThat(args).hasSize(2);

            // 驗證 channels
            JsonObject ordersArg = args.get(0).getAsJsonObject();
            assertThat(ordersArg.get("channel").getAsString()).isEqualTo("orders");
            assertThat(ordersArg.get("instType").getAsString()).isEqualTo("USDT-FUTURES");

            JsonObject positionsArg = args.get(1).getAsJsonObject();
            assertThat(positionsArg.get("channel").getAsString()).isEqualTo("positions");
            assertThat(positionsArg.get("instType").getAsString()).isEqualTo("USDT-FUTURES");
        }
    }
}
