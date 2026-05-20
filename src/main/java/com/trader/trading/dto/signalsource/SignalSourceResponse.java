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
    private String routingMode;
    private String tradeMode;
    private double riskMultiplier;
    private boolean paperTradingEnabled;
    private boolean enabled;
    private int assignedUserCount;

    /** 是否設定了 customPrompt（不回傳全文，避免敏感資訊在多 admin 場景外洩） */
    private boolean customPromptSet;

    /** customPrompt 版本號（給 audit chain 對齊用） */
    private int customPromptVersion;

    /** customPrompt SHA-256 前 16 hex */
    private String customPromptSha256;

    private LocalDateTime customPromptUpdatedAt;
    private String customPromptUpdatedBy;

    /**
     * 此源 mirror 是否啟用。
     * false 可能是「per-source flag 關著」或「URL 未設」— 前端要看 {@link #hasMirrorWebhook} 區分。
     */
    private boolean mirrorEnabled;

    /**
     * webhook URL 是否已設定。
     * **故意不回傳 URL 全文** — admin dashboard 多人場景避免敏感資訊外洩。
     * 前端僅顯示「未設定 / 已設」狀態。
     */
    private boolean hasMirrorWebhook;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
