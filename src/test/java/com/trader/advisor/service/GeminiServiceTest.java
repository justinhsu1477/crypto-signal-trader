package com.trader.advisor.service;

import com.trader.advisor.config.AdvisorConfig;
import com.trader.shared.config.AiConfig;
import okhttp3.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * GeminiService 單元測試
 *
 * 覆蓋：API Key 未設定、成功呼叫、HTTP 錯誤、IO 例外、回覆解析
 */
class GeminiServiceTest {

    private OkHttpClient httpClient;
    private AiConfig aiConfig;
    private AdvisorConfig advisorConfig;
    private com.trader.shared.llm.LlmMetrics llmMetrics;
    private GeminiService geminiService;
    private Call mockCall;

    @BeforeEach
    void setUp() {
        httpClient = mock(OkHttpClient.class);
        aiConfig = mock(AiConfig.class);
        advisorConfig = mock(AdvisorConfig.class);
        llmMetrics = mock(com.trader.shared.llm.LlmMetrics.class);

        OkHttpClient.Builder mockBuilder = mock(OkHttpClient.Builder.class);
        when(httpClient.newBuilder()).thenReturn(mockBuilder);
        when(mockBuilder.readTimeout(anyLong(), any())).thenReturn(mockBuilder);
        when(mockBuilder.build()).thenReturn(httpClient);

        mockCall = mock(Call.class);
        when(httpClient.newCall(any())).thenReturn(mockCall);

        // 預設 config
        when(aiConfig.getApiKey()).thenReturn("test-api-key");
        when(aiConfig.getDefaultModel()).thenReturn("gemini-2.5-flash-lite");
        when(advisorConfig.getMaxResponseTokens()).thenReturn(1024);
        when(advisorConfig.getTemperatureValue()).thenReturn(0.7);

        geminiService = new GeminiService(httpClient, aiConfig, advisorConfig, llmMetrics);
    }

    @Nested
    @DisplayName("API Key 檢查")
    class ApiKeyCheckTests {

        @Test
        @DisplayName("API Key 為 null — 回傳 empty")
        void nullApiKeyReturnsEmpty() {
            when(aiConfig.getApiKey()).thenReturn(null);
            geminiService = new GeminiService(httpClient, aiConfig, advisorConfig, llmMetrics);

            Optional<String> result = geminiService.generateContent("system", "user");

            assertThat(result).isEmpty();
            verify(httpClient, never()).newCall(any());
        }

        @Test
        @DisplayName("API Key 為空白 — 回傳 empty")
        void blankApiKeyReturnsEmpty() {
            when(aiConfig.getApiKey()).thenReturn("  ");
            geminiService = new GeminiService(httpClient, aiConfig, advisorConfig, llmMetrics);

            Optional<String> result = geminiService.generateContent("system", "user");

            assertThat(result).isEmpty();
            verify(httpClient, never()).newCall(any());
        }
    }

    @Nested
    @DisplayName("成功呼叫")
    class SuccessTests {

        @Test
        @DisplayName("正確回覆 — 解析 text")
        void successfulResponseReturnsText() throws Exception {
            String jsonResponse = """
                    {
                      "candidates": [{
                        "content": {
                          "parts": [{"text": "AI 分析結果"}]
                        }
                      }]
                    }
                    """;

            Response response = buildResponse(200, jsonResponse);
            when(mockCall.execute()).thenReturn(response);

            Optional<String> result = geminiService.generateContent("system prompt", "user content");

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("AI 分析結果");
        }

        @Test
        @DisplayName("請求 URL 包含 model 和 API key")
        void requestUrlContainsModelAndKey() throws Exception {
            String jsonResponse = """
                    {"candidates": [{"content": {"parts": [{"text": "ok"}]}}]}
                    """;
            Response response = buildResponse(200, jsonResponse);
            when(mockCall.execute()).thenReturn(response);

            geminiService.generateContent("sys", "usr");

            ArgumentCaptor<Request> captor = ArgumentCaptor.forClass(Request.class);
            verify(httpClient).newCall(captor.capture());
            String url = captor.getValue().url().toString();
            assertThat(url).contains("gemini-2.5-flash-lite");
            assertThat(url).contains("test-api-key");
            assertThat(captor.getValue().method()).isEqualTo("POST");
        }

        @Test
        @DisplayName("回覆前後空白被 trim")
        void responseTextTrimmed() throws Exception {
            String jsonResponse = """
                    {"candidates": [{"content": {"parts": [{"text": "  hello  "}]}}]}
                    """;
            Response response = buildResponse(200, jsonResponse);
            when(mockCall.execute()).thenReturn(response);

            Optional<String> result = geminiService.generateContent("sys", "usr");

            assertThat(result).isPresent();
            assertThat(result.get()).isEqualTo("hello");
        }
    }

    @Nested
    @DisplayName("錯誤處理")
    class ErrorTests {

        @Test
        @DisplayName("HTTP 非 200 — 回傳 empty")
        void nonSuccessStatusReturnsEmpty() throws Exception {
            Response response = buildResponse(429, "{\"error\": \"rate limited\"}");
            when(mockCall.execute()).thenReturn(response);

            Optional<String> result = geminiService.generateContent("sys", "usr");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("IOException — 回傳 empty")
        void ioExceptionReturnsEmpty() throws Exception {
            when(mockCall.execute()).thenThrow(new IOException("Connection refused"));

            Optional<String> result = geminiService.generateContent("sys", "usr");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("回覆無 candidates — 回傳 empty")
        void noCandidatesReturnsEmpty() throws Exception {
            Response response = buildResponse(200, "{\"candidates\": []}");
            when(mockCall.execute()).thenReturn(response);

            Optional<String> result = geminiService.generateContent("sys", "usr");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("回覆無 content — 回傳 empty")
        void noContentReturnsEmpty() throws Exception {
            Response response = buildResponse(200, "{\"candidates\": [{}]}");
            when(mockCall.execute()).thenReturn(response);

            Optional<String> result = geminiService.generateContent("sys", "usr");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("回覆無 parts — 回傳 empty")
        void noPartsReturnsEmpty() throws Exception {
            Response response = buildResponse(200,
                    "{\"candidates\": [{\"content\": {\"parts\": []}}]}");
            when(mockCall.execute()).thenReturn(response);

            Optional<String> result = geminiService.generateContent("sys", "usr");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("回覆 JSON 格式錯誤 — 回傳 empty")
        void invalidJsonReturnsEmpty() throws Exception {
            Response response = buildResponse(200, "not json");
            when(mockCall.execute()).thenReturn(response);

            Optional<String> result = geminiService.generateContent("sys", "usr");

            assertThat(result).isEmpty();
        }
    }

    // ========== helper ==========

    private Response buildResponse(int code, String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://example.com").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("OK")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }
}
