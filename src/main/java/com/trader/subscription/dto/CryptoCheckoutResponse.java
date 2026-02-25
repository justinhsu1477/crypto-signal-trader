package com.trader.subscription.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * USDT 付款資訊回應
 *
 * 前端顯示錢包地址 + QR Code + 金額
 */
@Data
@Builder
public class CryptoCheckoutResponse {
    private String planId;
    private String planName;
    private BigDecimal amountUsdt;
    private String walletAddress;
    private String network;
}
