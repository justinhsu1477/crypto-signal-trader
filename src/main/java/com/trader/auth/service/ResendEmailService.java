package com.trader.auth.service;

import com.trader.auth.config.EmailConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Resend API 發信服務
 *
 * enabled=false 時只 log OTP 不發信（開發環境）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResendEmailService {

    private static final String RESEND_API_URL = "https://api.resend.com/emails";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final EmailConfig emailConfig;

    /**
     * 發送 OTP 驗證碼 Email
     *
     * @param to   收件人 email
     * @param code 6 位數 OTP
     */
    public void sendOtpEmail(String to, String code) {
        if (!emailConfig.isEnabled()) {
            log.info("📧 [DEV] Email 未啟用，OTP 驗證碼: email={} code={}", to, code);
            return;
        }

        String subject = "HookFi — 您的驗證碼 " + code;
        String html = buildOtpHtml(code, emailConfig.getOtpExpiryMinutes());

        try {
            String jsonBody = buildRequestBody(
                    emailConfig.getFromAddress(), to, subject, html);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(TIMEOUT)
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RESEND_API_URL))
                    .header("Authorization", "Bearer " + emailConfig.getResendApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .timeout(TIMEOUT)
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                log.info("📧 OTP 發送成功: to={}", to);
            } else {
                log.error("📧 OTP 發送失敗: to={} status={} body={}", to, response.statusCode(), response.body());
                throw new RuntimeException("Email 發送失敗: HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Email 發送中斷", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("📧 OTP 發送異常: to={}", to, e);
            throw new RuntimeException("Email 發送失敗", e);
        }
    }

    /**
     * 建構 Resend API JSON body
     */
    String buildRequestBody(String from, String to, String subject, String html) {
        // 手動 JSON 建構（避免引入額外 JSON 依賴）
        return "{" +
                "\"from\":\"HookFi <" + escapeJson(from) + ">\"," +
                "\"to\":[\"" + escapeJson(to) + "\"]," +
                "\"subject\":\"" + escapeJson(subject) + "\"," +
                "\"html\":\"" + escapeJson(html) + "\"" +
                "}";
    }

    /**
     * 建構 OTP Email HTML 內容
     */
    String buildOtpHtml(String code, int expiryMinutes) {
        return """
                <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; max-width: 480px; margin: 0 auto; padding: 40px 20px;">
                  <div style="text-align: center; margin-bottom: 32px;">
                    <h1 style="color: #10b981; font-size: 24px; margin: 0;">HookFi</h1>
                    <p style="color: #6b7280; font-size: 14px; margin-top: 8px;">Email 驗證</p>
                  </div>
                  <div style="background: #f9fafb; border-radius: 12px; padding: 32px; text-align: center;">
                    <p style="color: #374151; font-size: 16px; margin: 0 0 24px;">您的驗證碼為：</p>
                    <div style="font-size: 36px; font-weight: bold; letter-spacing: 8px; color: #111827; padding: 16px; background: white; border-radius: 8px; display: inline-block;">
                      %s
                    </div>
                    <p style="color: #6b7280; font-size: 14px; margin-top: 24px;">
                      此驗證碼將在 <strong>%d 分鐘</strong>後過期
                    </p>
                  </div>
                  <p style="color: #9ca3af; font-size: 12px; text-align: center; margin-top: 24px;">
                    如果這不是您本人的操作，請忽略此郵件。
                  </p>
                </div>
                """.formatted(code, expiryMinutes);
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
