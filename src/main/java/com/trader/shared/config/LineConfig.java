package com.trader.shared.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LINE Messaging API 設定
 *
 * 前綴：line
 * 環境變數：LINE_CHANNEL_ID、LINE_CHANNEL_SECRET、LINE_CHANNEL_ACCESS_TOKEN、LINE_ENABLED
 */
@Getter
@ConfigurationProperties(prefix = "line")
public class LineConfig {

    private final String channelId;
    private final String channelSecret;
    private final String channelAccessToken;
    private final boolean enabled;
    private final int linkingCodeExpiryMinutes;
    private final RichMenuSettings richMenu;

    public LineConfig(
            String channelId,
            String channelSecret,
            String channelAccessToken,
            @DefaultValue("false") boolean enabled,
            @DefaultValue("10") int linkingCodeExpiryMinutes,
            RichMenuSettings richMenu
    ) {
        this.channelId = channelId;
        this.channelSecret = channelSecret;
        this.channelAccessToken = channelAccessToken;
        this.enabled = enabled;
        this.linkingCodeExpiryMinutes = linkingCodeExpiryMinutes;
        this.richMenu = richMenu;
    }

    /**
     * Rich Menu 設定
     *
     * 控制 LINE 底部功能選單的自動建立與動態切換。
     */
    @Getter
    public static class RichMenuSettings {
        private final boolean enabled;
        private final boolean forceRebuild;
        private final String webBaseUrl;

        public RichMenuSettings(
                @DefaultValue("true") boolean enabled,
                @DefaultValue("false") boolean forceRebuild,
                @DefaultValue("https://hook-fi.com") String webBaseUrl
        ) {
            this.enabled = enabled;
            this.forceRebuild = forceRebuild;
            this.webBaseUrl = webBaseUrl;
        }
    }
}
