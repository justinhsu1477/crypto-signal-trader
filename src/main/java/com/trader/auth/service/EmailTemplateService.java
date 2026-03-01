package com.trader.auth.service;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Email HTML 模板服務
 *
 * 從 classpath resources/templates/email/ 載入模板，
 * 使用 {{KEY}} 變數替換機制。
 *
 * 模板：
 * - base-layout.html — 共用 header / footer
 * - otp.html — OTP 驗證碼內容區塊
 * - password-reset.html — 密碼重設內容區塊
 */
@Slf4j
@Service
public class EmailTemplateService {

    private static final String TEMPLATE_DIR = "templates/email/";
    private static final String[] TEMPLATE_NAMES = {"base-layout", "otp", "password-reset"};

    private final Map<String, String> templateCache = new HashMap<>();

    @PostConstruct
    public void loadTemplates() {
        for (String name : TEMPLATE_NAMES) {
            String path = TEMPLATE_DIR + name + ".html";
            try {
                ClassPathResource resource = new ClassPathResource(path);
                try (InputStream is = resource.getInputStream()) {
                    String content = StreamUtils.copyToString(is, StandardCharsets.UTF_8);
                    templateCache.put(name, content);
                    log.info("📧 Email 模板載入成功: {}", path);
                }
            } catch (IOException e) {
                throw new IllegalStateException("Email 模板載入失敗: " + path, e);
            }
        }
        log.info("📧 所有 Email 模板載入完成（共 {} 個）", templateCache.size());
    }

    /**
     * 渲染 OTP 驗證碼 Email
     */
    public String renderOtpEmail(String code, int expiryMinutes) {
        String content = render("otp", Map.of(
                "CODE", code,
                "EXPIRY_MINUTES", String.valueOf(expiryMinutes)
        ));

        return renderWithLayout(content, "Email 驗證",
                "如果這不是您本人的操作，請忽略此郵件。");
    }

    /**
     * 渲染密碼重設 Email
     */
    public String renderPasswordResetEmail(String resetUrl, int expiryMinutes) {
        String content = render("password-reset", Map.of(
                "RESET_URL", resetUrl,
                "EXPIRY_MINUTES", String.valueOf(expiryMinutes)
        ));

        return renderWithLayout(content, "密碼重設",
                "如果這不是您本人的操作，請忽略此郵件，您的密碼不會被變更。");
    }

    /**
     * 將內容嵌入 base-layout
     */
    private String renderWithLayout(String content, String subtitle, String footerText) {
        return render("base-layout", Map.of(
                "SUBTITLE", subtitle,
                "CONTENT", content,
                "FOOTER_TEXT", footerText
        ));
    }

    /**
     * 載入模板並替換變數 {{KEY}} → value
     */
    private String render(String templateName, Map<String, String> variables) {
        String template = templateCache.get(templateName);
        if (template == null) {
            throw new IllegalStateException("模板不存在: " + templateName);
        }

        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }
}
