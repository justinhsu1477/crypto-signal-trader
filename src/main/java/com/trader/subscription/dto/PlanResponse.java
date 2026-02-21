package com.trader.subscription.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 方案列表 — 單個方案的回應 DTO
 *
 * 前端用來顯示訂閱方案卡片：
 * - 方案名稱 / 價格 / 限制
 * - Payment Link URL（給「訂閱」按鈕用）
 * - current = true 表示用戶目前使用的方案
 */
@Data
@Builder
public class PlanResponse {

    private String planId;
    private String name;
    private Double priceMonthly;

    /** 最大同時持倉數 */
    private Integer maxPositions;

    /** 可跟單幣種數 */
    private Integer maxSymbols;

    /** DCA 補倉層數上限 */
    private Integer dcaLayersAllowed;

    /** 最大風險比例 */
    private Double maxRiskPercent;

    /** Stripe Payment Link URL — 前端開新分頁到此 URL 付款 */
    private String paymentLinkUrl;

    /** 此方案是否為用戶目前使用的方案 */
    private boolean current;
}
