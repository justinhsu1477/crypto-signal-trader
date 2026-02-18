package com.trader.user.dto;

import lombok.Builder;
import lombok.Data;

/**
 * GET /api/user/me 回應
 *
 * 用戶基本資訊（不含密碼等敏感欄位）
 */
@Data
@Builder
public class UserProfileResponse {

    private String userId;
    private String email;
    private String name;
    private String role;
    private String createdAt;
}
