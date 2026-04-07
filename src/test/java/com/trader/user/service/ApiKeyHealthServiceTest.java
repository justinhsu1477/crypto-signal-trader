package com.trader.user.service;

import com.trader.shared.config.BinanceConfig;
import com.trader.user.dto.ApiKeyHealthResponse;
import com.trader.user.service.UserApiKeyService.BinanceKeys;
import okhttp3.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApiKeyHealthServiceTest {

    private UserApiKeyService userApiKeyService;
    private OkHttpClient httpClient;
    private BinanceConfig binanceConfig;
    private ApiKeyHealthService service;

    @BeforeEach
    void setUp() {
        userApiKeyService = mock(UserApiKeyService.class);
        httpClient = mock(OkHttpClient.class);
        binanceConfig = mock(BinanceConfig.class);
        when(binanceConfig.getBaseUrl()).thenReturn("https://fapi.binance.com");
        service = new ApiKeyHealthService(userApiKeyService, httpClient, binanceConfig);
    }

    @Test
    @DisplayName("未設定 API Key → valid=false")
    void noApiKey() {
        when(userApiKeyService.getUserBinanceKeys("user-1")).thenReturn(Optional.empty());

        ApiKeyHealthResponse resp = service.testApiKey("user-1");

        assertThat(resp.isValid()).isFalse();
        assertThat(resp.getMessage()).contains("尚未設定");
    }

    @Test
    @DisplayName("Binance 回傳 200 → valid=true, canTrade=true")
    void successfulResponse() throws IOException {
        when(userApiKeyService.getUserBinanceKeys("user-1"))
                .thenReturn(Optional.of(new BinanceKeys("apiKey123", "secretKey456")));

        mockHttpResponse(200, "[]");

        ApiKeyHealthResponse resp = service.testApiKey("user-1");

        assertThat(resp.isValid()).isTrue();
        assertThat(resp.isCanTrade()).isTrue();
        assertThat(resp.isFuturesEnabled()).isTrue();
    }

    @Test
    @DisplayName("Binance 回傳 -2015 → API Key 無效")
    void invalidApiKey() throws IOException {
        when(userApiKeyService.getUserBinanceKeys("user-1"))
                .thenReturn(Optional.of(new BinanceKeys("bad", "key")));

        mockHttpResponse(401, "{\"code\":-2015,\"msg\":\"Invalid API-key\"}");

        ApiKeyHealthResponse resp = service.testApiKey("user-1");

        assertThat(resp.isValid()).isFalse();
        assertThat(resp.getMessage()).contains("無效或已過期");
    }

    @Test
    @DisplayName("Binance 回傳 -1022 → 簽名無效")
    void invalidSignature() throws IOException {
        when(userApiKeyService.getUserBinanceKeys("user-1"))
                .thenReturn(Optional.of(new BinanceKeys("api", "bad-secret")));

        mockHttpResponse(400, "{\"code\":-1022,\"msg\":\"Signature invalid\"}");

        ApiKeyHealthResponse resp = service.testApiKey("user-1");

        assertThat(resp.isValid()).isFalse();
        assertThat(resp.getMessage()).contains("簽名無效");
    }

    @Test
    @DisplayName("Binance 回傳 -4004 → 未開通合約")
    void noFutures() throws IOException {
        when(userApiKeyService.getUserBinanceKeys("user-1"))
                .thenReturn(Optional.of(new BinanceKeys("api", "secret")));

        mockHttpResponse(400, "{\"code\":-4004,\"msg\":\"No futures account\"}");

        ApiKeyHealthResponse resp = service.testApiKey("user-1");

        assertThat(resp.isValid()).isTrue();
        assertThat(resp.isFuturesEnabled()).isFalse();
        assertThat(resp.isCanTrade()).isFalse();
    }

    @Test
    @DisplayName("網路異常 → valid=false, 連線失敗")
    void networkError() throws IOException {
        when(userApiKeyService.getUserBinanceKeys("user-1"))
                .thenReturn(Optional.of(new BinanceKeys("api", "secret")));

        Call call = mock(Call.class);
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenThrow(new IOException("Connection refused"));

        ApiKeyHealthResponse resp = service.testApiKey("user-1");

        assertThat(resp.isValid()).isFalse();
        assertThat(resp.getMessage()).contains("連線失敗");
    }

    @Test
    @DisplayName("Binance 回傳 -2014 → API Key 格式錯誤")
    void badFormat() throws IOException {
        when(userApiKeyService.getUserBinanceKeys("user-1"))
                .thenReturn(Optional.of(new BinanceKeys("api", "secret")));

        mockHttpResponse(400, "{\"code\":-2014,\"msg\":\"API-key format invalid\"}");

        ApiKeyHealthResponse resp = service.testApiKey("user-1");

        assertThat(resp.isValid()).isFalse();
        assertThat(resp.getMessage()).contains("格式錯誤");
    }

    private void mockHttpResponse(int code, String body) throws IOException {
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://fapi.binance.com/test").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.parse("application/json")))
                .build();
        Call call = mock(Call.class);
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);
    }
}
