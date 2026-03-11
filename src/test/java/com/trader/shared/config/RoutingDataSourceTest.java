package com.trader.shared.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RoutingDataSource 單元測試
 *
 * 驗證：
 * - readOnly transaction → REPLICA
 * - write transaction → PRIMARY
 * - 無 transaction → PRIMARY（預設安全）
 * - Replica 故障 fallback → PRIMARY
 * - Replica 恢復 → 重新路由到 REPLICA
 */
class RoutingDataSourceTest {

    private RoutingDataSource routingDataSource;

    @BeforeEach
    void setUp() {
        routingDataSource = new RoutingDataSource();
        // 清除 ThreadLocal 狀態
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
    }

    @Nested
    @DisplayName("路由邏輯")
    class RoutingLogic {

        @Test
        @DisplayName("readOnly=true 且 Replica 可用 → REPLICA")
        void readOnlyTransaction_routesToReplica() {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

            Object key = routingDataSource.determineCurrentLookupKey();

            assertThat(key).isEqualTo(DataSourceType.REPLICA);
        }

        @Test
        @DisplayName("readOnly=false → PRIMARY")
        void writeTransaction_routesToPrimary() {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

            Object key = routingDataSource.determineCurrentLookupKey();

            assertThat(key).isEqualTo(DataSourceType.PRIMARY);
        }

        @Test
        @DisplayName("無 transaction context → PRIMARY（預設安全）")
        void noTransaction_routesToPrimary() {
            // TransactionSynchronizationManager 預設 readOnly=false
            Object key = routingDataSource.determineCurrentLookupKey();

            assertThat(key).isEqualTo(DataSourceType.PRIMARY);
        }
    }

    @Nested
    @DisplayName("斷路器 — Replica 故障 Fallback")
    class CircuitBreaker {

        @Test
        @DisplayName("Replica 不可用時，readOnly 也走 PRIMARY")
        void replicaDown_readOnlyFallsToPrimary() {
            routingDataSource.markReplicaDown();
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

            Object key = routingDataSource.determineCurrentLookupKey();

            assertThat(key).isEqualTo(DataSourceType.PRIMARY);
        }

        @Test
        @DisplayName("Replica 恢復後，readOnly 重新路由到 REPLICA")
        void replicaRecovered_readOnlyRoutesToReplica() {
            routingDataSource.markReplicaDown();
            routingDataSource.markReplicaUp();
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

            Object key = routingDataSource.determineCurrentLookupKey();

            assertThat(key).isEqualTo(DataSourceType.REPLICA);
        }

        @Test
        @DisplayName("初始狀態 Replica 可用")
        void initialState_replicaAvailable() {
            assertThat(routingDataSource.isReplicaAvailable()).isTrue();
        }

        @Test
        @DisplayName("markReplicaDown 設定為不可用")
        void markDown_setsUnavailable() {
            routingDataSource.markReplicaDown();

            assertThat(routingDataSource.isReplicaAvailable()).isFalse();
        }

        @Test
        @DisplayName("markReplicaUp 恢復為可用")
        void markUp_setsAvailable() {
            routingDataSource.markReplicaDown();
            routingDataSource.markReplicaUp();

            assertThat(routingDataSource.isReplicaAvailable()).isTrue();
        }

        @Test
        @DisplayName("重複 markReplicaDown 不會有問題")
        void multipleMarkDown_idempotent() {
            routingDataSource.markReplicaDown();
            routingDataSource.markReplicaDown();

            assertThat(routingDataSource.isReplicaAvailable()).isFalse();
        }

        @Test
        @DisplayName("重複 markReplicaUp 不會有問題")
        void multipleMarkUp_idempotent() {
            routingDataSource.markReplicaUp();
            routingDataSource.markReplicaUp();

            assertThat(routingDataSource.isReplicaAvailable()).isTrue();
        }
    }
}
