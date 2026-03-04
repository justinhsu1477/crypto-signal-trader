package com.trader.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUserListResponse {

    private List<AdminUserSummary> users;
    private long totalUsers;
    private long activeUsers;
    private long adminUsers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AdminUserSummary {
        private String userId;
        private String email;
        private String name;
        private String role;
        private boolean enabled;
        private boolean emailVerified;
        private boolean autoTradeEnabled;
        private String createdAt;
        private String updatedAt;
        /** 登入方式：["EMAIL"], ["LINE"], ["EMAIL", "LINE"] */
        private List<String> loginMethods;
    }
}
