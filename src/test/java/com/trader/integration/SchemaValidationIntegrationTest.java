package com.trader.integration;

import com.trader.advisor.service.GeminiService;
import com.trader.chatbot.service.DiscordBotService;
import com.trader.chatbot.service.KnowledgeIndexService;
import com.trader.notification.service.DiscordWebhookService;
import com.trader.notification.service.LineNotificationService;
import com.trader.notification.service.LineRichMenuService;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.BinanceUserDataStreamService;
import com.trader.trading.service.StartupReconciliationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Hibernate schema validation 整合測試 — 抓 entity vs Flyway migration 不一致。
 *
 * <p>2026-05-15 prod 5xx 事件根因：V47/V48/V49 用 CHAR(16)，但 JPA @Column(length=16)
 * 預期 VARCHAR(16)。既有 integration-test 用 ddl-auto=create-drop（entity 自己生 schema），
 * 永遠跑不到驗證 → 只有 prod 開 ddl-auto=validate 才會炸。
 *
 * <p>此測試用 ddl-auto=validate + Flyway enabled 模擬 prod startup：
 * Spring context 起得來 → 全部 entity 跟 DB schema 對齊 ✓；context init throw → CI 紅。
 *
 * <p>Test method body 故意空的：Hibernate ddl-auto=validate 在 EntityManagerFactory 初始化時
 * 跑驗證，context 成功啟動本身就是「全部 entity pass」的隱性 assertion。
 *
 * <p>@Tag("integration") 排除預設 test task，只在 ./gradlew integrationTest + CI 跑。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("schema-validation-test")
@Tag("integration")
@DisplayName("Hibernate Schema Validation — Entity vs Flyway Migration")
class SchemaValidationIntegrationTest {

    /**
     * Singleton Container Pattern — 跟 BaseIntegrationTest 同模式，避免跨 test class 重啟
     * 導致 DataSource URL 鎖住舊 port（2026-05-13 踩過的坑）。
     */
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("pgvector/pgvector:pg16")
                    .withDatabaseName("schema_test")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    // 全部外部服務 mock 掉 — 此 test 只關心 schema validation，不需要真實依賴。
    //
    // !! IMPORTANT !! 這份清單必須跟 {@link BaseIntegrationTest} 同步。若主 application code 加入
    // 新的 @PostConstruct hook 而 BaseIntegrationTest 需要 mock 它，這裡也要加 — 否則 Spring context
    // 起不來，schema 驗證形同空殼（曾經發生：gRPC port 衝突讓 ddl-auto=validate 從沒跑到，靜默 pass）。
    @MockBean BinanceFuturesService binanceFuturesService;
    @MockBean GeminiService geminiService;
    @MockBean LineNotificationService lineNotificationService;
    @MockBean DiscordBotService discordBotService;
    @MockBean DiscordWebhookService discordWebhookService;
    @MockBean LineRichMenuService lineRichMenuService;
    @MockBean BinanceUserDataStreamService binanceUserDataStreamService;
    @MockBean StartupReconciliationService startupReconciliationService;
    @MockBean KnowledgeIndexService knowledgeIndexService;

    /**
     * 「Spring context 起得來」本身就是 assertion。
     *
     * <p>ddl-auto=validate 在 EntityManagerFactory 初始化時：
     * <ol>
     *     <li>Flyway 先跑完所有 migration → DB 有完整 schema</li>
     *     <li>Hibernate 讀所有 @Entity，把每個 @Column 跟 DB column 比對</li>
     *     <li>任何 mismatch（type/length/nullable/缺失欄位）→ SchemaManagementException</li>
     * </ol>
     *
     * <p>所以這個 method body 是空的 — context init 沒 throw 就 pass。
     */
    @Test
    @DisplayName("Spring context boots → 所有 entity 跟 Flyway schema 對齊")
    void springContextStartsAndHibernateValidates() {
        // Intentionally empty.
        // If we reached here, Hibernate validate already passed for EVERY entity.
    }
}
