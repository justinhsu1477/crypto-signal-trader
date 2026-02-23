package com.trader.referral.dto;

import com.trader.referral.entity.ReferralStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用戶推薦綁定狀態回應
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReferralStatusResponse {
    private ReferralStatus status;
    private String exchangeUid;
    private LocalDateTime verifiedAt;
    private String referralLink;
    private String referralCode;
}
