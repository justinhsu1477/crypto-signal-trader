package com.trader.subscription.entity;

import com.trader.shared.config.AppConstants;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "plans", indexes = {
        @Index(name = "idx_plans_active", columnList = "active")
})
public class Plan {

    @Id
    private String planId;

    @Column(nullable = false)
    private String name;

    private Double priceMonthly;
    private Double priceYearly;

    /** 最大同時持倉數 */
    private Integer maxPositions;

    /** 可跟單幣種數 */
    private Integer maxSymbols;

    /** 最大風險比例 (0.10 = 10%) */
    private Double maxRiskPercent;

    /** DCA 補倉層數上限 */
    private Integer dcaLayersAllowed;

    /** JSON — 進階功能開關 */
    @Column(columnDefinition = "TEXT")
    private String features;

    @Builder.Default
    private boolean active = true;

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
