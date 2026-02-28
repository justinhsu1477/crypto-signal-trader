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

    public LineConfig(
            String channelId,
            String channelSecret,
            String channelAccessToken,
            @DefaultValue("false") boolean enabled,
            @DefaultValue("10") int linkingCodeExpiryMinutes
    ) {
        this.channelId = channelId;
        this.channelSecret = channelSecret;
        this.channelAccessToken = channelAccessToken;
        this.enabled = enabled;
        this.linkingCodeExpiryMinutes = linkingCodeExpiryMinutes;
    }
}
