package com.trader.shared.llm;

import com.google.gson.JsonObject;
import com.trader.chatbot.dto.ChatTurn;
import com.trader.chatbot.dto.GeminiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 預設注入的 {@link LlmClient} — 依 {@code app.llm.provider} 決定每個方法走哪個 impl。
 *
 * <p>Per-method routing（不是「全切到 Spring AI」也不是「全留 Gemini」）：
 * <table>
 *   <tr><th>方法</th><th>provider=gemini (預設)</th><th>provider=spring-ai</th></tr>
 *   <tr><td>{@link #generateContent}</td><td>GeminiService</td><td><b>SpringAiLlmClient</b></td></tr>
 *   <tr><td>{@link #generateContentWithHistory}</td><td>GeminiService</td><td><b>SpringAiLlmClient</b></td></tr>
 *   <tr><td>{@link #generateContentWithTools}</td><td>GeminiService</td><td>GeminiService（Phase 3 才接 @Tool）</td></tr>
 *   <tr><td>{@link #sendFunctionResult}</td><td>GeminiService</td><td>GeminiService</td></tr>
 *   <tr><td>{@link #sendFunctionResultForChaining}</td><td>GeminiService</td><td>GeminiService</td></tr>
 *   <tr><td>{@link #getEmbedding}</td><td>GeminiService</td><td>GeminiService（Phase 5 加 embedding starter）</td></tr>
 *   <tr><td>{@link #getBatchEmbeddings}</td><td>GeminiService</td><td>GeminiService</td></tr>
 * </table>
 *
 * <p>設計理由：
 * <ul>
 *   <li>Phase 2 只接通 2 個「簡單呼叫」path — 漸進遷移，把風險面降到最小</li>
 *   <li>未接通的方法 fallback 到既有 GeminiService（OkHttp 實作）— 完全不破壞 chatbot / RAG</li>
 *   <li>切換靠 config，不用改 code、不用 redeploy 不同 jar</li>
 *   <li>未來 Phase 3/5 接通 tools / embeddings 時，只改本類路由表，呼叫端 0 改動</li>
 * </ul>
 *
 * @see SpringAiLlmClient
 * @see com.trader.advisor.service.GeminiService
 */
@Slf4j
@Primary
@Component
public class RoutingLlmClient implements LlmClient {

    private final LlmClient gemini;
    private final LlmClient springAi;
    private final boolean preferSpringAi;

    public RoutingLlmClient(
            @Qualifier("geminiService") LlmClient gemini,
            @Qualifier("springAiLlmClient") LlmClient springAi,
            @Value("${app.llm.provider:gemini}") String provider) {
        this.gemini = gemini;
        this.springAi = springAi;
        this.preferSpringAi = "spring-ai".equalsIgnoreCase(provider);
        log.info("LLM router initialised: provider='{}' (simple methods → {}, tool/embedding methods → gemini)",
                provider, preferSpringAi ? "spring-ai" : "gemini");
    }

    // ─── Simple paths：依 provider 切換 ────────────────────────────────────────

    @Override
    public Optional<String> generateContent(String systemPrompt, String userContent) {
        return route().generateContent(systemPrompt, userContent);
    }

    @Override
    public Optional<String> generateContentWithHistory(String systemPrompt,
                                                       List<ChatTurn> history,
                                                       String userMessage,
                                                       int maxTokens,
                                                       double temperature,
                                                       String model) {
        return route().generateContentWithHistory(systemPrompt, history, userMessage,
                maxTokens, temperature, model);
    }

    // ─── Tools paths：永遠走 Gemini（Phase 3 接通後改成 route()） ─────────────────

    @Override
    public Optional<GeminiResponse> generateContentWithTools(String systemPrompt,
                                                             List<ChatTurn> history,
                                                             String userMessage,
                                                             int maxTokens,
                                                             double temperature,
                                                             String model,
                                                             JsonObject tools) {
        return gemini.generateContentWithTools(systemPrompt, history, userMessage,
                maxTokens, temperature, model, tools);
    }

    @Override
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
        return gemini.sendFunctionResult(systemPrompt, history, userMessage, functionName,
                functionCallArgs, functionResult, maxTokens, temperature, model, tools);
    }

    @Override
    public Optional<GeminiResponse> sendFunctionResultForChaining(String systemPrompt,
                                                                  List<ChatTurn> history,
                                                                  String userMessage,
                                                                  String functionName,
                                                                  JsonObject functionCallArgs,
                                                                  String functionResult,
                                                                  int maxTokens,
                                                                  double temperature,
                                                                  String model,
                                                                  JsonObject tools) {
        return gemini.sendFunctionResultForChaining(systemPrompt, history, userMessage,
                functionName, functionCallArgs, functionResult, maxTokens, temperature, model, tools);
    }

    // ─── Embedding paths：永遠走 Gemini（Phase 5 接通） ────────────────────────────

    @Override
    public Optional<float[]> getEmbedding(String text) {
        return gemini.getEmbedding(text);
    }

    @Override
    public Optional<List<float[]>> getBatchEmbeddings(List<String> texts) {
        return gemini.getBatchEmbeddings(texts);
    }

    private LlmClient route() {
        return preferSpringAi ? springAi : gemini;
    }
}
