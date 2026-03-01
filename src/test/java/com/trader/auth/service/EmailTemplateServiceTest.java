package com.trader.auth.service;

import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;

/**
 * EmailTemplateService 單元測試
 *
 * 模板從 classpath 載入（src/main/resources/templates/email/）
 */
class EmailTemplateServiceTest {

    private EmailTemplateService service;

    @BeforeEach
    void setUp() {
        service = new EmailTemplateService();
        service.loadTemplates();
    }

    @Nested
    @DisplayName("renderOtpEmail")
    class RenderOtpEmail {

        @Test
        @DisplayName("包含驗證碼")
        void containsCode() {
            String html = service.renderOtpEmail("123456", 10);
            assertThat(html).contains("123456");
        }

        @Test
        @DisplayName("包含過期分鐘數")
        void containsExpiry() {
            String html = service.renderOtpEmail("654321", 15);
            assertThat(html).contains("15 分鐘");
        }

        @Test
        @DisplayName("包含 HookFi header")
        void containsHeader() {
            String html = service.renderOtpEmail("000000", 10);
            assertThat(html).contains("HookFi");
            assertThat(html).contains("#10b981");
        }

        @Test
        @DisplayName("包含 Email 驗證 subtitle")
        void containsSubtitle() {
            String html = service.renderOtpEmail("000000", 10);
            assertThat(html).contains("Email 驗證");
        }

        @Test
        @DisplayName("包含 footer 免責聲明")
        void containsFooter() {
            String html = service.renderOtpEmail("000000", 10);
            assertThat(html).contains("如果這不是您本人的操作，請忽略此郵件。");
        }
    }

    @Nested
    @DisplayName("renderPasswordResetEmail")
    class RenderPasswordResetEmail {

        @Test
        @DisplayName("包含重設連結")
        void containsResetUrl() {
            String html = service.renderPasswordResetEmail("https://hookfi.com/reset?token=abc123", 60);
            assertThat(html).contains("https://hookfi.com/reset?token=abc123");
        }

        @Test
        @DisplayName("包含過期分鐘數")
        void containsExpiry() {
            String html = service.renderPasswordResetEmail("https://example.com", 30);
            assertThat(html).contains("30 分鐘");
        }

        @Test
        @DisplayName("包含 HookFi header")
        void containsHeader() {
            String html = service.renderPasswordResetEmail("https://example.com", 60);
            assertThat(html).contains("HookFi");
            assertThat(html).contains("#10b981");
        }

        @Test
        @DisplayName("包含密碼重設 subtitle")
        void containsSubtitle() {
            String html = service.renderPasswordResetEmail("https://example.com", 60);
            assertThat(html).contains("密碼重設");
        }

        @Test
        @DisplayName("包含 footer 免責聲明")
        void containsFooter() {
            String html = service.renderPasswordResetEmail("https://example.com", 60);
            assertThat(html).contains("如果這不是您本人的操作，請忽略此郵件，您的密碼不會被變更。");
        }
    }

    @Nested
    @DisplayName("模板載入")
    class TemplateLoading {

        @Test
        @DisplayName("所有模板載入成功")
        void allTemplatesLoadSuccessfully() {
            EmailTemplateService freshService = new EmailTemplateService();
            assertThatCode(freshService::loadTemplates).doesNotThrowAnyException();
        }
    }
}
