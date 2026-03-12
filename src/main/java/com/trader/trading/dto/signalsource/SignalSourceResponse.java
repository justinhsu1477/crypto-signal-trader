package com.trader.trading.dto.signalsource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Admin 視角的訊號來源回應 — 含完整資訊（name, channelId, guildId）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SignalSourceResponse {

    private Long id;
    private String name;
    private String displayName;
    private String channelId;
    private String guildId;
    private String description;
    private boolean enabled;
    private int assignedUserCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
