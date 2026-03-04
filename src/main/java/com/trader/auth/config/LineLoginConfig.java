package com.trader.auth.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LINE Login Channel 設定
 *
 * 與 LineConfig（Messaging API Channel）分開，因為是不同的 LINE Channel。
 * 兩者必須在同一個 LINE Provider 底下，lineUserId 才一致。
 */
@Getter
@ConfigurationProperties(prefix = "line.login")
public class LineLoginConfig {

    private final String channelId;
    private final String channelSecret;
    private final String callbackUrl;
    private final boolean enabled;

    public LineLoginConfig(
            @DefaultValue("") String channelId,
            @DefaultValue("") String channelSecret,
            @DefaultValue("") String callbackUrl,
            @DefaultValue("false") boolean enabled
    ) {
        this.channelId = channelId;
        this.channelSecret = channelSecret;
        this.callbackUrl = callbackUrl;
        this.enabled = enabled;
    }
}
