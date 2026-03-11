package com.trader.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 讀寫分離路由 DataSource
 *
 * 路由邏輯：
 * 1. 當前 transaction 為 readOnly=true 且 replica 可用 → REPLICA
 * 2. 其他所有情況 → PRIMARY（預設安全）
 *
 * 斷路器：volatile boolean replicaAvailable
 * - ReplicaHealthChecker 定時 SELECT 1 偵測 replica 存活
 * - 故障時自動 fallback 到 PRIMARY，恢復後重新路由
 */
@Slf4j
public class RoutingDataSource extends AbstractRoutingDataSource {

    private volatile boolean replicaAvailable = true;

    @Override
    protected Object determineCurrentLookupKey() {
        boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();

        if (isReadOnly && replicaAvailable) {
            log.trace("DataSource 路由: REPLICA");
            return DataSourceType.REPLICA;
        }

        log.trace("DataSource 路由: PRIMARY");
        return DataSourceType.PRIMARY;
    }

    public void markReplicaDown() {
        if (replicaAvailable) {
            replicaAvailable = false;
            log.warn("Read Replica 已標記為不可用，所有查詢 fallback 到 Primary");
        }
    }

    public void markReplicaUp() {
        if (!replicaAvailable) {
            replicaAvailable = true;
            log.info("Read Replica 已恢復，readOnly 查詢重新路由到 Replica");
        }
    }

    public boolean isReplicaAvailable() {
        return replicaAvailable;
    }
}
