package com.trader.user.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "user_trade_settings")
public class UserTradeSettings {

    /** PK = userId，一人一列 */
    @Id
    private String userId;

    /** 個人風險比例 (0.20 = 20%) */
    private Double riskPercent;

    /** 最大槓桿 */
    private Integer maxLeverage;

    /** 最大 DCA 補倉層數 */
    private Integer maxDcaLayers;

    /** 單筆最大名目 (USDT) */
    private Double maxPositionSizeUsdt;

    /** 允許跟單的幣種 — JSON array e.g. ["BTCUSDT","ETHUSDT"] */
    @Column(columnDefinition = "TEXT")
    private String allowedSymbols;

    /** 自動止損開關 */
    @Builder.Default
    private boolean autoSlEnabled = true;

    /** 自動止盈開關 */
    @Builder.Default
    private boolean autoTpEnabled = true;

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
