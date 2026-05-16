package com.trader.trading.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 訊號來源設定 — 對應一個 Discord 群組/頻道
 *
 * 命名為 SignalSourceConfig 避免與 com.trader.shared.model.SignalSource（DTO）衝突
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "signal_sources", indexes = {
    @Index(name = "idx_ss_enabled", columnList = "enabled")
})
public class SignalSourceConfig {

    /**
     * 路由模式：
     * - GLOBAL：全員廣播（所有用戶都收到此來源的訊號）
     * - ASSIGNED：僅綁定用戶收到（需透過 user_signal_sources 綁定）
     */
    public enum RoutingMode {
        GLOBAL, ASSIGNED
    }

    /**
     * 交易模式：
     * - AUTO：自動執行交易（預設）
     * - SHADOW：影子模式（只記錄不交易，用於觀察新來源準確率）
     * - MANUAL：手動模式（僅送通知，不廣播跟單）
     */
    public enum TradeMode {
        AUTO, SHADOW, MANUAL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Admin 內部名稱（如「陳哥VIP群」），用戶不可見 */
    private String name;

    /** 用戶看到的別名（如「訊號源 A」） */
    private String displayName;

    /** Discord channel ID */
    private String channelId;

    /** Discord guild ID */
    private String guildId;

    /** Admin 備註 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 路由模式 */
    @Enumerated(EnumType.STRING)
    @Column(name = "routing_mode", nullable = false)
    @Builder.Default
    private RoutingMode routingMode = RoutingMode.ASSIGNED;

    /** 交易模式 */
    @Enumerated(EnumType.STRING)
    @Column(name = "trade_mode", nullable = false)
    @Builder.Default
    private TradeMode tradeMode = TradeMode.AUTO;

    /** 風險倍率（預設 1.0，範圍 0.1 ~ 3.0） */
    @Column(name = "risk_multiplier", nullable = false)
    @Builder.Default
    private double riskMultiplier = 1.0;

    /** AI 補充指令（per-source 解析方言） — 寫入規範見 PROMPT_ARCHITECTURE.md */
    @Column(name = "custom_prompt", columnDefinition = "TEXT", nullable = false)
    @Builder.Default
    private String customPrompt = "";

    /** custom_prompt 單調遞增版本號；BroadcastLog 可記錄這個值做稽核鏈 */
    @Column(name = "custom_prompt_version", nullable = false)
    @Builder.Default
    private int customPromptVersion = 0;

    /** custom_prompt 內容的 SHA-256 前 16 hex（null = 從未寫過 / 空字串） */
    @Column(name = "custom_prompt_sha256", length = 16)
    private String customPromptSha256;

    @Column(name = "custom_prompt_updated_at")
    private LocalDateTime customPromptUpdatedAt;

    @Column(name = "custom_prompt_updated_by", length = 64)
    private String customPromptUpdatedBy;

    /**
     * AES-GCM 加密後 base64 的 Discord webhook URL；null = 未設定。
     * decrypt 之後才是真 URL。明碼不入庫。
     */
    @Column(name = "mirror_webhook_url", length = 512)
    private String mirrorWebhookUrl;

    /**
     * 此源 mirror 開關。
     * 即使加了 webhook URL，這個 flag 沒設 true 不會送 — 避免設好 URL 還沒準備好就誤發。
     */
    @Column(name = "mirror_enabled", nullable = false)
    @Builder.Default
    private boolean mirrorEnabled = false;

    /** 模擬交易開關（僅 SHADOW 模式有效） */
    @Column(name = "paper_trading_enabled", nullable = false)
    @Builder.Default
    private boolean paperTradingEnabled = false;

    /** 啟用狀態 */
    @Builder.Default
    private boolean enabled = true;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(AppConstants.ZONE_ID);
        updatedAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(AppConstants.ZONE_ID);
    }
}
