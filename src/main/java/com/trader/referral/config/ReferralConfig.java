package com.trader.referral.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
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

    @PostConstruct
    void validate() {
        if (referralLink == null || referralLink.isBlank()) {
            log.warn("⚠ referral.referral-link 未設定，推薦連結功能將無法正常使用");
        }
        if (referralCode == null || referralCode.isBlank()) {
            log.warn("⚠ referral.referral-code 未設定，推薦碼功能將無法正常使用");
        }
    }
}
