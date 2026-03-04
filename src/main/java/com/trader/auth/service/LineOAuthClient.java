package com.trader.auth.service;

import com.google.gson.Gson;
import com.trader.auth.config.LineLoginConfig;
import com.trader.auth.dto.LineProfile;
import com.trader.auth.dto.LineTokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * LINE Login OAuth2 API Client
 *
 * 負責：
 * 1. 組裝 LINE 授權 URL
 * 2. 用 authorization code 換取 access token
 * 3. 用 access token 取得用戶 Profile
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LineOAuthClient {

    private static final String AUTH_URL = "https://access.line.me/oauth2/v2.1/authorize";
    private static final String TOKEN_URL = "https://api.line.me/oauth2/v2.1/token";
    private static final String PROFILE_URL = "https://api.line.me/v2/profile";

    private final OkHttpClient okHttpClient;
    private final LineLoginConfig lineLoginConfig;
    private final Gson gson = new Gson();

    /**
     * 組裝 LINE 授權 URL
     */
    public String buildAuthorizationUrl(String state) {
        return AUTH_URL
                + "?response_type=code"
                + "&client_id=" + encode(lineLoginConfig.getChannelId())
                + "&redirect_uri=" + encode(lineLoginConfig.getCallbackUrl())
                + "&state=" + encode(state)
                + "&scope=" + encode("profile openid email")
                + "&bot_prompt=aggressive";  // 提示用戶加入 LINE Bot
    }

    /**
     * 用 authorization code 換取 token
     */
    public LineTokenResponse exchangeCode(String code) {
        RequestBody body = new FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", lineLoginConfig.getCallbackUrl())
                .add("client_id", lineLoginConfig.getChannelId())
                .add("client_secret", lineLoginConfig.getChannelSecret())
                .build();

        Request request = new Request.Builder()
                .url(TOKEN_URL)
                .post(body)
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("LINE token exchange 失敗: status={} body={}", response.code(), responseBody);
                throw new RuntimeException("LINE token exchange 失敗: " + response.code());
            }
            return gson.fromJson(responseBody, LineTokenResponse.class);
        } catch (IOException e) {
            log.error("LINE token exchange 網路錯誤: {}", e.getMessage());
            throw new RuntimeException("LINE token exchange 失敗", e);
        }
    }

    /**
     * 用 access token 取得用戶 Profile
     */
    public LineProfile getProfile(String accessToken) {
        Request request = new Request.Builder()
                .url(PROFILE_URL)
                .addHeader("Authorization", "Bearer " + accessToken)
                .get()
                .build();

        try (Response response = okHttpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("LINE profile API 失敗: status={} body={}", response.code(), responseBody);
                throw new RuntimeException("LINE profile API 失敗: " + response.code());
            }
            return gson.fromJson(responseBody, LineProfile.class);
        } catch (IOException e) {
            log.error("LINE profile API 網路錯誤: {}", e.getMessage());
            throw new RuntimeException("LINE profile API 失敗", e);
        }
    }

    /**
     * 從 LINE ID Token (JWT) 解析 email
     *
     * ID Token 剛從 LINE token endpoint 直接取得（可信來源），
     * 只需 base64 decode payload 段即可，不需驗證簽名。
     *
     * @return email 或 null（用戶未授權 email scope）
     */
    public String extractEmailFromIdToken(String idToken) {
        if (idToken == null || idToken.isBlank()) {
            return null;
        }

        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) {
                log.warn("LINE ID Token 格式異常: parts={}", parts.length);
                return null;
            }

            // JWT payload 是第二段，Base64URL 編碼
            String payload = new String(
                    Base64.getUrlDecoder().decode(parts[1]),
                    StandardCharsets.UTF_8
            );

            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            if (json.has("email") && !json.get("email").isJsonNull()) {
                String email = json.get("email").getAsString();
                log.debug("LINE ID Token email={}", email);
                return email;
            }

            return null;
        } catch (Exception e) {
            log.warn("LINE ID Token email 解析失敗: {}", e.getMessage());
            return null;
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
