package com.trader.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 用戶漏斗統計 — Admin Insights 用
 *
 * 6 階段漏斗：已註冊 → Email 驗證 → 推薦碼驗證 → API Key → 交易 → 訂閱
 * + 註冊趨勢圖 + 最近註冊列表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FunnelStatsResponse {

    private int totalUsers;
    private int emailVerified;
    private int referralVerified;
    private int hasApiKey;
    private int hasTraded;
    private int activeSubscription;

    /** 每日註冊數（最近 90 天） */
    private List<DateCount> registrationsByDate;

    /** 最近 10 筆註冊用戶 */
    private List<RecentUser> recentUsers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DateCount {
        private String date;
        private long count;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RecentUser {
        private String userId;
        private String name;
        private String email;
        private String createdAt;
        private String stage;
    }
}
