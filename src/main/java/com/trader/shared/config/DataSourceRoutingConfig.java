package com.trader.shared.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 讀寫分離 DataSource 配置
 *
 * 安全設計：
 * 1. REPLICA_DB_URL 為空 → 直接回傳 Primary DataSource（零開銷，向下相容）
 * 2. REPLICA_DB_URL 有值 → 建立 RoutingDataSource（Primary + Replica 雙 pool）
 * 3. Flyway 強制只走 Primary（FlywayConfigurationCustomizer）
 * 4. Replica pool 設定 readOnly=true（JDBC 層安全網）
 */
@Slf4j
@Configuration
public class DataSourceRoutingConfig {

    private final String replicaUrl;
    private final int replicaMaxPoolSize;
    private final Environment environment;

    public DataSourceRoutingConfig(
            @Value("${spring.datasource.replica.url:}") String replicaUrl,
            @Value("${spring.datasource.replica.hikari.maximum-pool-size:10}") int replicaMaxPoolSize,
            Environment environment) {
        this.replicaUrl = replicaUrl;
        this.replicaMaxPoolSize = replicaMaxPoolSize;
        this.environment = environment;
    }

    /**
     * 主要 DataSource Bean — 覆蓋 Spring Boot auto-config
     *
     * 若無 Replica URL → 回傳 Spring Boot auto-config 的 Primary DataSource（零開銷）
     * 若有 Replica URL → 回傳 RoutingDataSource（自動路由讀寫）
     */
    @Bean
    @Primary
    public DataSource dataSource(DataSourceProperties properties) {
        HikariDataSource primaryDataSource = createPrimaryDataSource(properties);

        if (replicaUrl == null || replicaUrl.isBlank()) {
            log.info("Read Replica 未設定（REPLICA_DB_URL 為空），使用單一 Primary DataSource");
            return primaryDataSource;
        }

        HikariDataSource replicaDataSource = createReplicaDataSource(properties);

        RoutingDataSource routingDataSource = new RoutingDataSource();
        routingDataSource.setTargetDataSources(Map.of(
                DataSourceType.PRIMARY, primaryDataSource,
                DataSourceType.REPLICA, replicaDataSource
        ));
        routingDataSource.setDefaultTargetDataSource(primaryDataSource);
        routingDataSource.afterPropertiesSet();

        log.info("Read Replica DataSource 已啟用 — Primary pool={}, Replica pool={}",
                primaryDataSource.getMaximumPoolSize(), replicaDataSource.getMaximumPoolSize());

        return routingDataSource;
    }

    /**
     * Flyway 強制使用 Primary DataSource — 防止 migration 跑在 Replica
     */
    @Bean
    public FlywayConfigurationCustomizer flywayConfigurationCustomizer(DataSourceProperties properties) {
        return (FluentConfiguration configuration) -> {
            configuration.dataSource(
                    properties.getUrl(),
                    properties.getUsername(),
                    properties.getPassword()
            );
            log.debug("Flyway 已綁定 Primary DataSource");
        };
    }

    private HikariDataSource createPrimaryDataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        // 套用 spring.datasource.hikari.* 設定（pool size, keepalive, timeout 等）
        Binder.get(environment).bind("spring.datasource.hikari", Bindable.ofInstance(ds));
        ds.setPoolName("HikariPool-Primary");
        return ds;
    }

    private HikariDataSource createReplicaDataSource(DataSourceProperties properties) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(replicaUrl);
        ds.setUsername(properties.getUsername());
        ds.setPassword(properties.getPassword());
        ds.setDriverClassName(properties.getDriverClassName());
        ds.setPoolName("HikariPool-Replica");
        ds.setMaximumPoolSize(replicaMaxPoolSize);
        ds.setMinimumIdle(2);
        ds.setConnectionTimeout(10000);
        ds.setIdleTimeout(300000);
        ds.setKeepaliveTime(120000);   // Neon 最佳化：每 2 分鐘探測
        ds.setReadOnly(true);          // JDBC 層安全網：防止 write 誤路由
        return ds;
    }
}
