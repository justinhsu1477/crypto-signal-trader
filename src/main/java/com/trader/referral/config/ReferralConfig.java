package com.trader.referral.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 推薦系統設定
 *
 * 對應 application.yml:
 * referral:
 *   default-exchange: BINANCE
 *   referral-link: https://...
 *   referral-code: YOUR_CODE
 */
@Getter
@ConfigurationProperties(prefix = "referral")
public class ReferralConfig {

    private final String defaultExchange;
    private final String referralLink;
    private final String referralCode;

    public ReferralConfig(
            @DefaultValue("BINANCE") String defaultExchange,
            @DefaultValue("") String referralLink,
            @DefaultValue("") String referralCode) {
        this.defaultExchange = defaultExchange;
        this.referralLink = referralLink;
        this.referralCode = referralCode;
    }
}
