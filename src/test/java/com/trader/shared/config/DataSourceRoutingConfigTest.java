package com.trader.shared.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DataSourceRoutingConfig 單元測試
 *
 * 驗證：
 * - 無 Replica URL → 回傳原始 Primary DataSource（零開銷，向下相容）
 * - 有 Replica URL → 回傳 RoutingDataSource
 * - Primary pool 套用 Hikari 設定
 */
class DataSourceRoutingConfigTest {

    private DataSourceProperties createTestProperties() {
        DataSourceProperties props = new DataSourceProperties();
        props.setUrl("jdbc:h2:mem:testdb");
        props.setUsername("sa");
        props.setPassword("");
        props.setDriverClassName("org.h2.Driver");
        return props;
    }

    private MockEnvironment createMockEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.hikari.maximum-pool-size", "20");
        env.setProperty("spring.datasource.hikari.minimum-idle", "5");
        env.setProperty("spring.datasource.hikari.connection-timeout", "10000");
        env.setProperty("spring.datasource.hikari.idle-timeout", "300000");
        env.setProperty("spring.datasource.hikari.keepalive-time", "120000");
        return env;
    }

    @Test
    @DisplayName("無 Replica URL → 回傳 HikariDataSource（零開銷，向下相容）")
    void noReplicaUrl_returnsPrimaryOnly() {
        DataSourceRoutingConfig config = new DataSourceRoutingConfig("", 10, createMockEnvironment());
        DataSourceProperties props = createTestProperties();

        DataSource dataSource = config.dataSource(props);

        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(dataSource).isNotInstanceOf(RoutingDataSource.class);

        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getPoolName()).isEqualTo("HikariPool-Primary");
        hikari.close();
    }

    @Test
    @DisplayName("null Replica URL → 回傳 HikariDataSource")
    void nullReplicaUrl_returnsPrimaryOnly() {
        DataSourceRoutingConfig config = new DataSourceRoutingConfig(null, 10, createMockEnvironment());
        DataSourceProperties props = createTestProperties();

        DataSource dataSource = config.dataSource(props);

        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(dataSource).isNotInstanceOf(RoutingDataSource.class);

        ((HikariDataSource) dataSource).close();
    }

    @Test
    @DisplayName("有 Replica URL → 回傳 RoutingDataSource")
    void withReplicaUrl_returnsRoutingDataSource() {
        DataSourceRoutingConfig config = new DataSourceRoutingConfig("jdbc:h2:mem:replicadb", 5, createMockEnvironment());
        DataSourceProperties props = createTestProperties();

        DataSource dataSource = config.dataSource(props);

        assertThat(dataSource).isInstanceOf(RoutingDataSource.class);

        // 清理
        RoutingDataSource rds = (RoutingDataSource) dataSource;
        rds.getResolvedDataSources().values().forEach(ds -> {
            if (ds instanceof HikariDataSource hikari) {
                hikari.close();
            }
        });
    }

    @Test
    @DisplayName("Replica pool 設定為 readOnly")
    void replicaPool_isReadOnly() {
        DataSourceRoutingConfig config = new DataSourceRoutingConfig("jdbc:h2:mem:replicadb", 5, createMockEnvironment());
        DataSourceProperties props = createTestProperties();

        DataSource dataSource = config.dataSource(props);
        RoutingDataSource rds = (RoutingDataSource) dataSource;
        DataSource replicaDs = rds.getResolvedDataSources().get(DataSourceType.REPLICA);

        assertThat(replicaDs).isInstanceOf(HikariDataSource.class);
        HikariDataSource hikari = (HikariDataSource) replicaDs;
        assertThat(hikari.isReadOnly()).isTrue();
        assertThat(hikari.getPoolName()).isEqualTo("HikariPool-Replica");
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(5);

        // 清理
        rds.getResolvedDataSources().values().forEach(ds -> {
            if (ds instanceof HikariDataSource h) {
                h.close();
            }
        });
    }

    @Test
    @DisplayName("Primary pool 套用 spring.datasource.hikari.* 設定")
    void primaryPool_appliesHikariSettings() {
        DataSourceRoutingConfig config = new DataSourceRoutingConfig("", 10, createMockEnvironment());
        DataSourceProperties props = createTestProperties();

        DataSource dataSource = config.dataSource(props);

        HikariDataSource hikari = (HikariDataSource) dataSource;
        assertThat(hikari.getMaximumPoolSize()).isEqualTo(20);
        assertThat(hikari.getMinimumIdle()).isEqualTo(5);
        assertThat(hikari.getConnectionTimeout()).isEqualTo(10000);
        assertThat(hikari.getIdleTimeout()).isEqualTo(300000);
        assertThat(hikari.getKeepaliveTime()).isEqualTo(120000);
        hikari.close();
    }
}
