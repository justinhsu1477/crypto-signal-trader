package com.trader.subscription.config;

import lombok.Getter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * USDT TRC20 加密貨幣收款設定
 *
 * 對應 application.yml:
 * crypto:
 *   payment:
 *     enabled: true
 *     network: TRC20
 *     wallet-address: TLgjqVz...
 *     trongrid-api-key: xxx
 *     confirmation-blocks: 20
 *     subscription-days: 30
 */
@Getter
@ConfigurationProperties(prefix = "crypto.payment")
public class CryptoPaymentConfig {

    /** 是否啟用加密貨幣付款 */
    private final boolean enabled;

    /** 網路類型 */
    private final String network;

    /** TRC20 收款錢包地址 */
    private final String walletAddress;

    /** TronGrid API Key（免費申請） */
    private final String trongridApiKey;

    /** 交易確認區塊數 */
    private final int confirmationBlocks;

    /** 訂閱天數（每次付款） */
    private final int subscriptionDays;

    public CryptoPaymentConfig(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("TRC20") String network,
            @DefaultValue("") String walletAddress,
            @DefaultValue("") String trongridApiKey,
            @DefaultValue("20") int confirmationBlocks,
            @DefaultValue("30") int subscriptionDays) {
        this.enabled = enabled;
        this.network = network;
        this.walletAddress = walletAddress;
        this.trongridApiKey = trongridApiKey;
        this.confirmationBlocks = confirmationBlocks;
        this.subscriptionDays = subscriptionDays;
    }
}
