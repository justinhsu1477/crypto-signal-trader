package com.trader.referral.entity;

/**
 * 推薦綁定狀態
 */
public enum ReferralStatus {
    NOT_STARTED,  // 尚未提交 UID
    PENDING,      // 已提交 UID，等待管理員驗證
    VERIFIED      // 已驗證通過
}
