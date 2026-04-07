package com.trader.trading.dto.signalsource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用戶視角的訊號來源回應 — 只有 displayName（隱私保護，不含 channelId/guildId/name）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalSourceUserResponse {

    private Long id;
    private String displayName;
    private String description;
    private boolean enabled;
}
