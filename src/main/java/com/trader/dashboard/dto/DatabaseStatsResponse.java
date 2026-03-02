package com.trader.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 資料庫使用量統計 — Admin Dashboard 用
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseStatsResponse {

    /** 資料庫總大小（bytes） */
    private long totalSizeBytes;

    /** 儲存空間上限（bytes），Neon Free Tier = 512 MB */
    private long storageLimitBytes;

    /** 使用百分比 */
    private double usagePercent;

    /** 各表統計（按大小降序） */
    private List<TableStats> tables;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableStats {
        private String tableName;
        private long rowCount;
        private long totalBytes;
    }
}
