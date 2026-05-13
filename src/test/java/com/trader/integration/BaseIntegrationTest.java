package com.trader.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.advisor.service.GeminiService;
import com.trader.auth.filter.RateLimitFilter;
import com.trader.auth.service.JwtService;
import com.trader.chatbot.service.DiscordBotService;
import com.trader.chatbot.service.KnowledgeIndexService;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.LineNotificationService;
import com.trader.notification.service.LineRichMenuService;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.BinanceUserDataStreamService;
import com.trader.trading.service.StartupReconciliationService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.servlet.http.Cookie;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration Test 基底類 — Testcontainers PostgreSQL + MockMvc
 *
 * 參考 GenBI 的 BaseIntegrationTest pattern：
 * - Testcontainers pgvector:pg16（支援 vector extension）
 * - Flyway 跑真正的 42 個 migration
 * - @MockBean 外部服務（Binance, Gemini, LINE, Discord）
 * - Helper methods 簡化 HTTP 操作
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
@Testcontainers
@Tag("integration")
public abstract class BaseIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("pgvector/pgvector:pg16")
            .withDatabaseName("trading_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    // ==================== Mock 外部服務 ====================

    @MockBean protected BinanceFuturesService binanceFuturesService;
    @MockBean protected GeminiService geminiService;
    @MockBean protected LineNotificationService lineNotificationService;
    @MockBean protected DiscordBotService discordBotService;
    @MockBean protected DiscordWebhookService discordWebhookService;
    @MockBean protected LineRichMenuService lineRichMenuService;
    @MockBean protected BinanceUserDataStreamService binanceUserDataStreamService;
    @MockBean protected StartupReconciliationService startupReconciliationService;
    @MockBean protected KnowledgeIndexService knowledgeIndexService;

    // ==================== 注入 ====================

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JwtService jwtService;
    @Autowired protected EntityManager entityManager;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected RateLimitFilter rateLimitFilter;

    // ==================== 測試資料常數 ====================

    protected static final String TEST_EMAIL = "test@hookfi.com";
    protected static final String TEST_PASSWORD = "TestPass123";
    protected static final String TEST_NAME = "Test User";

    // ==================== Helper Methods ====================

    protected String asJson(Object obj) throws Exception {
        return objectMapper.writeValueAsString(obj);
    }

    /**
     * 註冊 + 登入，回傳 JWT access token cookie
     */
    protected Cookie registerAndLogin() throws Exception {
        // 註冊
        String registerBody = """
                {"email":"%s","password":"%s","name":"%s","termsAccepted":true}
                """.formatted(TEST_EMAIL, TEST_PASSWORD, TEST_NAME);

        mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isOk());

        // 登入
        String loginBody = """
                {"email":"%s","password":"%s"}
                """.formatted(TEST_EMAIL, TEST_PASSWORD);

        MvcResult loginResult = mockMvc.perform(MockMvcRequestBuilders.post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn();

        Cookie accessToken = loginResult.getResponse().getCookie("accessToken");
        if (accessToken == null) {
            throw new AssertionError("Login did not return accessToken cookie");
        }
        return accessToken;
    }

    /**
     * @AfterEach 用 TransactionTemplate 而非 @Transactional：
     * Spring 的 TransactionalTestExecutionListener 只攔 @Test，不攔 lifecycle hook，
     * 所以 @Transactional 標在這裡會丟 TransactionRequiredException。
     *
     * 反向 FK 順序清，涵蓋所有 integration test 用到的 table。
     */
    @AfterEach
    void cleanDatabase() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("DELETE FROM trades").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM broadcast_logs").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM signals").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM discord_raw_messages").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM user_api_keys").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM subscriptions").executeUpdate();
            entityManager.createNativeQuery("DELETE FROM users").executeUpdate();
            entityManager.flush();
        });
        // RateLimitFilter 是 in-memory counter，跨測試共用同一 Spring context →
        // /api/auth/login (5/min) 累積 → 429。每測試後重置避免互相干擾。
        rateLimitFilter.resetCounters();
    }
}
