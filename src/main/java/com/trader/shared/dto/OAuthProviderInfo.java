package com.trader.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * OAuth 提供者安全摘要（不含 token）
 *
 * 放在 shared 模組，user 和 auth 都能使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OAuthProviderInfo {
    private String provider;      // "LINE", "GOOGLE", "DISCORD"
    private String displayName;
    private String email;
    private String createdAt;     // ISO string
}
