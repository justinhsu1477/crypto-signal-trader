package com.trader.shared.config;

/**
 * DataSource 路由類型
 *
 * AbstractRoutingDataSource 的 lookup key：
 * - PRIMARY：主庫（讀寫）
 * - REPLICA：唯讀副本（Neon Read Replica）
 */
public enum DataSourceType {
    PRIMARY,
    REPLICA
}
