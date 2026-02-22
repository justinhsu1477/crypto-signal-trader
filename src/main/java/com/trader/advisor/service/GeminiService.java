package com.trader.advisor.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trader.advisor.config.AdvisorConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Gemini REST API 客戶端
 *
 * 直接透過 OkHttp 呼叫 Gemini generateContent API，
 * 不引入額外 SDK，保持與專案一致的 HTTP 呼叫模式。
 */
@Slf4j
@Service
public class GeminiService {

    private static final String GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient aiHttpClient;
    private final AdvisorConfig advisorConfig;
    private final Gson gson = new Gson();

    public GeminiService(OkHttpClient httpClient, AdvisorConfig advisorConfig) {
        // AI 回應較慢，延長 readTimeout 到 30 秒
        this.aiHttpClient = httpClient.newBuilder()
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        this.advisorConfig = advisorConfig;
    }

    /**
     * 呼叫 Gemini generateContent API
     *
     * @param systemPrompt 系統指令
     * @param userContent  使用者內容（交易 context）
     * @return AI 回覆文字，失敗時回傳 empty
     */
    public Optional<String> generateContent(String systemPrompt, String userContent) {
        String apiKey = advisorConfig.getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API Key 未設定，跳過 AI 分析");
            return Optional.empty();
        }

        String model = advisorConfig.getGeminiModel();
        String url = GEMINI_API_BASE + model + ":generateContent?key=" + apiKey;

        // 建構 request body
        String requestBody = buildRequestBody(systemPrompt, userContent);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody, JSON_MEDIA))
                .build();

        try (Response response = aiHttpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.warn("Gemini API 回應異常: HTTP {} - {}", response.code(), body);
                return Optional.empty();
            }

            return parseResponseText(body);
        } catch (IOException e) {
            log.warn("Gemini API 呼叫失敗: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 建構 Gemini API request body
     *
     * {
     *   "system_instruction": { "parts": [{"text": "..."}] },
     *   "contents": [{ "parts": [{"text": "..."}] }],
     *   "generationConfig": { "maxOutputTokens": 1024, "temperature": 0.7 }
     * }
     */
    private String buildRequestBody(String systemPrompt, String userContent) {
        // system_instruction
        JsonObject systemPart = new JsonObject();
        systemPart.addProperty("text", systemPrompt);
        JsonArray systemParts = new JsonArray();
        systemParts.add(systemPart);
        JsonObject systemInstruction = new JsonObject();
        systemInstruction.add("parts", systemParts);

        // contents
        JsonObject userPart = new JsonObject();
        userPart.addProperty("text", userContent);
        JsonArray userParts = new JsonArray();
        userParts.add(userPart);
        JsonObject content = new JsonObject();
        content.add("parts", userParts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        // generationConfig
        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("maxOutputTokens", advisorConfig.getMaxResponseTokens());
        generationConfig.addProperty("temperature", advisorConfig.getTemperatureValue());

        // 組裝完整 body
        JsonObject body = new JsonObject();
        body.add("system_instruction", systemInstruction);
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);

        return gson.toJson(body);
    }

    /**
     * 解析 Gemini API 回覆
     * 路徑: candidates[0].content.parts[0].text
     */
    private Optional<String> parseResponseText(String responseBody) {
        try {
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);

            JsonArray candidates = json.getAsJsonArray("candidates");
            if (candidates == null || candidates.isEmpty()) {
                log.warn("Gemini 回覆無 candidates");
                return Optional.empty();
            }

            JsonObject firstCandidate = candidates.get(0).getAsJsonObject();
            JsonObject contentObj = firstCandidate.getAsJsonObject("content");
            if (contentObj == null) {
                log.warn("Gemini 回覆無 content");
                return Optional.empty();
            }

            JsonArray parts = contentObj.getAsJsonArray("parts");
            if (parts == null || parts.isEmpty()) {
                log.warn("Gemini 回覆無 parts");
                return Optional.empty();
            }

            String text = parts.get(0).getAsJsonObject().get("text").getAsString();

            // 記錄 token 用量（Gemini API 回覆自帶 usageMetadata）
            JsonObject usageMeta = json.getAsJsonObject("usageMetadata");
            if (usageMeta != null) {
                int promptTokens = usageMeta.has("promptTokenCount")
                        ? usageMeta.get("promptTokenCount").getAsInt() : 0;
                int candidatesTokens = usageMeta.has("candidatesTokenCount")
                        ? usageMeta.get("candidatesTokenCount").getAsInt() : 0;
                int totalTokens = usageMeta.has("totalTokenCount")
                        ? usageMeta.get("totalTokenCount").getAsInt() : 0;
                log.info("Gemini token 用量: prompt={}, response={}, total={}",
                        promptTokens, candidatesTokens, totalTokens);
            }

            return Optional.of(text.trim());
        } catch (Exception e) {
            log.warn("解析 Gemini 回覆失敗: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
