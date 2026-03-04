package com.trader.user.dto;

import com.trader.shared.dto.OAuthProviderInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Admin 用戶詳情頁完整回應
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserDetailResponse {

    // -- 帳號資訊 --
    private String userId;
    private String email;
    private String name;
    private String role;
    private boolean enabled;
    private boolean emailVerified;
    private boolean autoTradeEnabled;
    private String createdAt;
    private String updatedAt;
    private String passwordChangedAt;
    private boolean hasPassword;

    // -- 登入方式 --
    private List<String> loginMethods;
    private List<OAuthProviderInfo> oauthProviders;

    // -- LINE 綁定 --
    private LineBindingInfo lineBinding;

    // -- API Keys（安全：只有 metadata） --
    private List<ApiKeyInfo> apiKeys;

    // -- Discord Webhooks（URL 截斷） --
    private List<DiscordWebhookInfo> discordWebhooks;

    // -- 通知偏好 --
    private NotificationPreferencesInfo notificationPreferences;

    // -- 交易設定 --
    private TradeSettingsResponse tradeSettings;

    // ===== Inner DTOs =====

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LineBindingInfo {
        private String displayName;
        private boolean enabled;
        private String linkedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApiKeyInfo {
        private String exchange;
        private String createdAt;
        private String updatedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DiscordWebhookInfo {
        private String webhookId;
        private String name;
        private boolean enabled;
        private String webhookUrlPreview;
        private String createdAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class NotificationPreferencesInfo {
        private boolean tradeExecution;
        private boolean slTpTriggered;
        private boolean protectionLost;
        private boolean dailyReport;
        private boolean streamStatus;
        private boolean systemAlert;
    }
}
