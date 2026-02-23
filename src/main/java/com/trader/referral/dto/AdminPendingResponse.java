package com.trader.referral.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 待驗證推薦綁定列表回應
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPendingResponse {
    private String userId;
    private String email;
    private String exchangeUid;
    private LocalDateTime submittedAt;
}
