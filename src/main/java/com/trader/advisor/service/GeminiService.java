package com.trader.advisor.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.trader.advisor.config.AdvisorConfig;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import com.trader.chatbot.dto.ChatTurn;
import com.trader.chatbot.dto.GeminiFunctionCall;
import com.trader.chatbot.dto.GeminiResponse;

import java.io.IOException;
import java.util.List;
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
     * 呼叫 Gemini API（多輪對話版本）
     *
     * 支援 conversation history，用於客服場景。
     * contents 中包含多個 turn（role: user/model）。
     *
     * @param systemPrompt 系統指令
     * @param history      對話歷史（可為空）
     * @param userMessage  當前使用者訊息
     * @param maxTokens    最大回覆 token 數
     * @param temperature  溫度（0.0-1.0）
     * @return AI 回覆文字，失敗時回傳 empty
     */
    public Optional<String> generateContentWithHistory(String systemPrompt,
                                                        List<ChatTurn> history,
                                                        String userMessage,
                                                        int maxTokens,
                                                        double temperature,
                                                        String model) {
        String apiKey = advisorConfig.getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API Key 未設定，跳過 AI 客服回覆");
            return Optional.empty();
        }

        String effectiveModel = (model != null && !model.isBlank()) ? model : advisorConfig.getGeminiModel();
        String url = GEMINI_API_BASE + effectiveModel + ":generateContent?key=" + apiKey;

        String requestBody = buildMultiTurnRequestBody(systemPrompt, history, userMessage, maxTokens, temperature);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody, JSON_MEDIA))
                .build();

        try (Response response = aiHttpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.warn("Gemini API（客服）回應異常: HTTP {} - {}", response.code(), body);
                return Optional.empty();
            }

            return parseResponseText(body);
        } catch (IOException e) {
            log.warn("Gemini API（客服）呼叫失敗: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 呼叫 Gemini API（支援 Function Calling）
     *
     * 當 Gemini 判斷用戶需要執行操作時，回傳 functionCall 而非 text。
     * 呼叫端需檢查 GeminiResponse.hasFunctionCall() 來決定後續流程。
     *
     * @param systemPrompt 系統指令
     * @param history      對話歷史
     * @param userMessage  當前使用者訊息
     * @param maxTokens    最大回覆 token 數
     * @param temperature  溫度
     * @param model        模型名稱
     * @param tools        Gemini tools schema（function declarations）
     * @return GeminiResponse（可能是 text 或 functionCall）
     */
    public Optional<GeminiResponse> generateContentWithTools(String systemPrompt,
                                                               List<ChatTurn> history,
                                                               String userMessage,
                                                               int maxTokens,
                                                               double temperature,
                                                               String model,
                                                               JsonObject tools) {
        String apiKey = advisorConfig.getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API Key 未設定");
            return Optional.empty();
        }

        String effectiveModel = (model != null && !model.isBlank()) ? model : advisorConfig.getGeminiModel();
        String url = GEMINI_API_BASE + effectiveModel + ":generateContent?key=" + apiKey;

        String requestBody = buildMultiTurnRequestBodyWithTools(systemPrompt, history, userMessage, maxTokens, temperature, tools);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody, JSON_MEDIA))
                .build();

        try (Response response = aiHttpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.warn("Gemini API（Function Calling）回應異常: HTTP {} - {}", response.code(), body);
                return Optional.empty();
            }

            return parseGeminiResponse(body);
        } catch (IOException e) {
            log.warn("Gemini API（Function Calling）呼叫失敗: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Function Call 結果回傳給 Gemini 取得最終回覆
     *
     * 流程：原始 contents + model functionCall + user functionResponse → Gemini → 最終文字回覆
     */
    public Optional<String> sendFunctionResult(String systemPrompt,
                                                 List<ChatTurn> history,
                                                 String userMessage,
                                                 String functionName,
                                                 JsonObject functionCallArgs,
                                                 String functionResult,
                                                 int maxTokens,
                                                 double temperature,
                                                 String model,
                                                 JsonObject tools) {
        String apiKey = advisorConfig.getGeminiApiKey();
        if (apiKey == null || apiKey.isBlank()) return Optional.empty();

        String effectiveModel = (model != null && !model.isBlank()) ? model : advisorConfig.getGeminiModel();
        String url = GEMINI_API_BASE + effectiveModel + ":generateContent?key=" + apiKey;

        String requestBody = buildFunctionResultRequestBody(
                systemPrompt, history, userMessage, functionName, functionCallArgs, functionResult, maxTokens, temperature, tools);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(requestBody, JSON_MEDIA))
                .build();

        try (Response response = aiHttpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                log.warn("Gemini API（Function Result）回應異常: HTTP {} - {}", response.code(), body);
                return Optional.empty();
            }

            return parseResponseText(body);
        } catch (IOException e) {
            log.warn("Gemini API（Function Result）呼叫失敗: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 建構含 tools 的多輪對話 request body
     */
    private String buildMultiTurnRequestBodyWithTools(String systemPrompt,
                                                        List<ChatTurn> history,
                                                        String userMessage,
                                                        int maxTokens,
                                                        double temperature,
                                                        JsonObject tools) {
        String base = buildMultiTurnRequestBody(systemPrompt, history, userMessage, maxTokens, temperature);
        JsonObject body = gson.fromJson(base, JsonObject.class);

        // 加入 tools
        if (tools != null) {
            JsonArray toolsArray = new JsonArray();
            toolsArray.add(tools);
            body.add("tools", toolsArray);
        }

        return gson.toJson(body);
    }

    /**
     * 建構 function result 回傳的 request body
     *
     * contents 結構：
     * [history...] + [user message] + [model functionCall] + [user functionResponse]
     */
    private String buildFunctionResultRequestBody(String systemPrompt,
                                                    List<ChatTurn> history,
                                                    String userMessage,
                                                    String functionName,
                                                    JsonObject functionCallArgs,
                                                    String functionResult,
                                                    int maxTokens,
                                                    double temperature,
                                                    JsonObject tools) {
        // system_instruction
        JsonObject systemPart = new JsonObject();
        systemPart.addProperty("text", systemPrompt);
        JsonArray systemParts = new JsonArray();
        systemParts.add(systemPart);
        JsonObject systemInstruction = new JsonObject();
        systemInstruction.add("parts", systemParts);

        // contents
        JsonArray contents = new JsonArray();

        // 歷史對話
        if (history != null) {
            for (ChatTurn turn : history) {
                JsonObject part = new JsonObject();
                part.addProperty("text", turn.getContent());
                JsonArray parts = new JsonArray();
                parts.add(part);
                JsonObject content = new JsonObject();
                content.addProperty("role", turn.getRole());
                content.add("parts", parts);
                contents.add(content);
            }
        }

        // 使用者訊息
        JsonObject userPart = new JsonObject();
        userPart.addProperty("text", userMessage);
        JsonArray userParts = new JsonArray();
        userParts.add(userPart);
        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");
        userContent.add("parts", userParts);
        contents.add(userContent);

        // Model 的 functionCall 回覆
        JsonObject fcPart = new JsonObject();
        JsonObject fcObj = new JsonObject();
        fcObj.addProperty("name", functionName);
        fcObj.add("args", functionCallArgs != null ? functionCallArgs : new JsonObject());
        fcPart.add("functionCall", fcObj);
        JsonArray modelParts = new JsonArray();
        modelParts.add(fcPart);
        JsonObject modelContent = new JsonObject();
        modelContent.addProperty("role", "model");
        modelContent.add("parts", modelParts);
        contents.add(modelContent);

        // User 的 functionResponse
        JsonObject frPart = new JsonObject();
        JsonObject frObj = new JsonObject();
        frObj.addProperty("name", functionName);
        JsonObject frResponse = new JsonObject();
        frResponse.addProperty("result", functionResult);
        frObj.add("response", frResponse);
        frPart.add("functionResponse", frObj);
        JsonArray frParts = new JsonArray();
        frParts.add(frPart);
        JsonObject frContent = new JsonObject();
        frContent.addProperty("role", "user");
        frContent.add("parts", frParts);
        contents.add(frContent);

        // generationConfig
        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("maxOutputTokens", maxTokens);
        generationConfig.addProperty("temperature", temperature);

        // 組裝
        JsonObject body = new JsonObject();
        body.add("system_instruction", systemInstruction);
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);

        if (tools != null) {
            JsonArray toolsArray = new JsonArray();
            toolsArray.add(tools);
            body.add("tools", toolsArray);
        }

        return gson.toJson(body);
    }

    /**
     * 解析 Gemini 回覆（支援 text 和 functionCall 兩種模式）
     */
    private Optional<GeminiResponse> parseGeminiResponse(String responseBody) {
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

            JsonObject firstPart = parts.get(0).getAsJsonObject();

            // 檢查是否為 functionCall
            if (firstPart.has("functionCall")) {
                JsonObject fc = firstPart.getAsJsonObject("functionCall");
                String name = fc.get("name").getAsString();
                JsonObject args = fc.has("args") ? fc.getAsJsonObject("args") : new JsonObject();

                logTokenUsage(json);

                return Optional.of(GeminiResponse.builder()
                        .functionCall(GeminiFunctionCall.builder()
                                .functionName(name)
                                .args(args)
                                .build())
                        .rawResponseBody(responseBody)
                        .build());
            }

            // 純文字回覆
            String text = firstPart.get("text").getAsString();
            logTokenUsage(json);

            return Optional.of(GeminiResponse.builder()
                    .text(text.trim())
                    .rawResponseBody(responseBody)
                    .build());

        } catch (Exception e) {
            log.warn("解析 Gemini Function Calling 回覆失敗: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 記錄 token 用量
     */
    private void logTokenUsage(JsonObject json) {
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
    }

    /**
     * 建構多輪對話 request body
     */
    private String buildMultiTurnRequestBody(String systemPrompt,
                                              List<ChatTurn> history,
                                              String userMessage,
                                              int maxTokens,
                                              double temperature) {
        // system_instruction
        JsonObject systemPart = new JsonObject();
        systemPart.addProperty("text", systemPrompt);
        JsonArray systemParts = new JsonArray();
        systemParts.add(systemPart);
        JsonObject systemInstruction = new JsonObject();
        systemInstruction.add("parts", systemParts);

        // contents: history turns + current user message
        JsonArray contents = new JsonArray();

        // 加入歷史對話
        if (history != null) {
            for (ChatTurn turn : history) {
                JsonObject part = new JsonObject();
                part.addProperty("text", turn.getContent());
                JsonArray parts = new JsonArray();
                parts.add(part);
                JsonObject content = new JsonObject();
                content.addProperty("role", turn.getRole()); // "user" or "model"
                content.add("parts", parts);
                contents.add(content);
            }
        }

        // 當前使用者訊息
        JsonObject userPart = new JsonObject();
        userPart.addProperty("text", userMessage);
        JsonArray userParts = new JsonArray();
        userParts.add(userPart);
        JsonObject userContent = new JsonObject();
        userContent.addProperty("role", "user");
        userContent.add("parts", userParts);
        contents.add(userContent);

        // generationConfig
        JsonObject generationConfig = new JsonObject();
        generationConfig.addProperty("maxOutputTokens", maxTokens);
        generationConfig.addProperty("temperature", temperature);

        // 組裝
        JsonObject body = new JsonObject();
        body.add("system_instruction", systemInstruction);
        body.add("contents", contents);
        body.add("generationConfig", generationConfig);

        return gson.toJson(body);
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
