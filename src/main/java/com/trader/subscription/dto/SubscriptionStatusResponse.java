package com.trader.subscription.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SubscriptionStatusResponse {

    private String planId;
    private String planName;
    private String status;
    private LocalDateTime currentPeriodEnd;
    private boolean active;
}
