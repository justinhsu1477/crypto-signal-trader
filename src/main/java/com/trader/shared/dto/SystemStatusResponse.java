package com.trader.shared.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 公開系統狀態回應 DTO
 *
 * GET /api/status（無需認證）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemStatusResponse {

    private String overallStatus;       // UP / DEGRADED
    private List<ServiceStatus> services;
    private String checkedAt;           // ISO 8601

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ServiceStatus {
        private String name;
        private String status;          // UP / DEGRADED / DOWN
        private String description;
    }
}
