package com.trader.shared.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Read Replica 健康檢查 — 斷路器模式
 *
 * 每 15 秒對 Replica 執行 SELECT 1：
 * - 成功 → markReplicaUp()（readOnly 查詢路由到 Replica）
 * - 失敗 → markReplicaDown()（所有查詢 fallback 到 Primary）
 *
 * 只在 REPLICA_DB_URL 有值時才載入
 */
@Slf4j
@Component
@ConditionalOnExpression("!'${spring.datasource.replica.url:}'.isEmpty()")
public class ReplicaHealthChecker {

    private final RoutingDataSource routingDataSource;
    private final DataSource replicaDataSource;

    public ReplicaHealthChecker(DataSource dataSource) {
        if (!(dataSource instanceof RoutingDataSource rds)) {
            throw new IllegalStateException("ReplicaHealthChecker 需要 RoutingDataSource，但取得: " + dataSource.getClass());
        }
        this.routingDataSource = rds;
        this.replicaDataSource = rds.getResolvedDataSources().get(DataSourceType.REPLICA);
    }

    @Scheduled(fixedDelay = 15000, initialDelay = 30000)
    public void checkReplicaHealth() {
        try (Connection conn = replicaDataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {

            if (rs.next()) {
                routingDataSource.markReplicaUp();
            }
        } catch (Exception e) {
            log.warn("Replica 健康檢查失敗: {}", e.getMessage());
            routingDataSource.markReplicaDown();
        }
    }
}
