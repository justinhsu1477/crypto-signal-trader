package com.trader.service;

import com.trader.shared.service.MetricsService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MetricsService 單元測試
 *
 * 使用 SimpleMeterRegistry（Micrometer 提供的記憶體實作），不需要 Spring Context。
 */
class MetricsServiceTest {

    private SimpleMeterRegistry registry;
    private MetricsService metricsService;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metricsService = new MetricsService(registry);
    }

    @Nested
    @DisplayName("下單指標")
    class OrderMetrics {

        @Test
        @DisplayName("recordOrder — 成功下單計數器 +1")
        void recordOrderSuccess() {
            metricsService.recordOrder("MARKET", true);
            metricsService.recordOrder("MARKET", true);
            metricsService.recordOrder("LIMIT", true);

            Counter marketSuccess = registry.find("trading.orders.total")
                    .tag("type", "MARKET").tag("status", "success").counter();
            Counter limitSuccess = registry.find("trading.orders.total")
                    .tag("type", "LIMIT").tag("status", "success").counter();

            assertThat(marketSuccess).isNotNull();
            assertThat(marketSuccess.count()).isEqualTo(2);
            assertThat(limitSuccess).isNotNull();
            assertThat(limitSuccess.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("recordOrder — 失敗下單計數器 +1")
        void recordOrderFailure() {
            metricsService.recordOrder("SL", false);

            Counter slFail = registry.find("trading.orders.total")
                    .tag("type", "SL").tag("status", "fail").counter();

            assertThat(slFail).isNotNull();
            assertThat(slFail.count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("訊號指標")
    class SignalMetrics {

        @Test
        @DisplayName("recordSignal — 各類型計數器獨立")
        void recordSignalByType() {
            metricsService.recordSignal("ENTRY");
            metricsService.recordSignal("ENTRY");
            metricsService.recordSignal("CLOSE");
            metricsService.recordSignal("MOVE_SL");

            assertThat(registry.find("trading.signals.processed")
                    .tag("signalType", "ENTRY").counter().count()).isEqualTo(2);
            assertThat(registry.find("trading.signals.processed")
                    .tag("signalType", "CLOSE").counter().count()).isEqualTo(1);
            assertThat(registry.find("trading.signals.processed")
                    .tag("signalType", "MOVE_SL").counter().count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("API 延遲指標")
    class ApiLatencyMetrics {

        @Test
        @DisplayName("recordApiLatency — Timer 正確記錄")
        void recordApiLatency() {
            metricsService.recordApiLatency("placeMarketOrder", 150);
            metricsService.recordApiLatency("placeMarketOrder", 250);

            Timer timer = registry.find("trading.binance.api.latency")
                    .tag("operation", "placeMarketOrder").timer();

            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(2);
            assertThat(timer.mean(java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(150.0, 250.0);
        }
    }

    @Nested
    @DisplayName("通知指標")
    class NotificationMetrics {

        @Test
        @DisplayName("recordNotification — 各頻道獨立計數")
        void recordNotificationByChannel() {
            metricsService.recordNotification("discord", true);
            metricsService.recordNotification("discord", true);
            metricsService.recordNotification("line", true);
            metricsService.recordNotification("discord", false);

            assertThat(registry.find("notification.sent")
                    .tag("channel", "discord").tag("status", "success").counter().count()).isEqualTo(2);
            assertThat(registry.find("notification.sent")
                    .tag("channel", "discord").tag("status", "fail").counter().count()).isEqualTo(1);
            assertThat(registry.find("notification.sent")
                    .tag("channel", "line").tag("status", "success").counter().count()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("指標摘要 (Admin Dashboard)")
    class MetricsSummary {

        @Test
        @DisplayName("getMetricsSummary — 無資料時回傳零值")
        @SuppressWarnings("unchecked")
        void emptyMetricsSummary() {
            Map<String, Object> summary = metricsService.getMetricsSummary();

            assertThat(summary).containsKeys("orders", "signals", "notifications", "api", "system");

            Map<String, Object> orders = (Map<String, Object>) summary.get("orders");
            assertThat(orders.get("total")).isEqualTo(0L);
            assertThat(orders.get("successRate")).isEqualTo(0.0);

            Map<String, Object> api = (Map<String, Object>) summary.get("api");
            assertThat(api.get("avgLatencyMs")).isEqualTo(0);
            assertThat(api.get("totalCalls")).isEqualTo(0L);

            Map<String, Object> system = (Map<String, Object>) summary.get("system");
            assertThat((long) system.get("uptimeSeconds")).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("getMetricsSummary — 有資料時正確聚合")
        @SuppressWarnings("unchecked")
        void populatedMetricsSummary() {
            // 模擬一些指標
            metricsService.recordOrder("MARKET", true);
            metricsService.recordOrder("MARKET", true);
            metricsService.recordOrder("LIMIT", false);
            metricsService.recordSignal("ENTRY");
            metricsService.recordSignal("CLOSE");
            metricsService.recordApiLatency("placeMarketOrder", 100);
            metricsService.recordApiLatency("placeMarketOrder", 200);
            metricsService.recordNotification("discord", true);
            metricsService.recordNotification("line", false);

            Map<String, Object> summary = metricsService.getMetricsSummary();

            Map<String, Object> orders = (Map<String, Object>) summary.get("orders");
            assertThat(orders.get("total")).isEqualTo(3L);
            assertThat(orders.get("success")).isEqualTo(2L);
            assertThat(orders.get("failed")).isEqualTo(1L);
            assertThat((double) orders.get("successRate")).isCloseTo(66.7, org.assertj.core.data.Offset.offset(0.1));

            Map<String, Object> signals = (Map<String, Object>) summary.get("signals");
            assertThat(signals.get("total")).isEqualTo(2L);
            Map<String, Long> byType = (Map<String, Long>) signals.get("byType");
            assertThat(byType.get("ENTRY")).isEqualTo(1L);
            assertThat(byType.get("CLOSE")).isEqualTo(1L);

            Map<String, Object> notifications = (Map<String, Object>) summary.get("notifications");
            assertThat(notifications.get("total")).isEqualTo(2L);
            assertThat((double) notifications.get("failRate")).isCloseTo(50.0, org.assertj.core.data.Offset.offset(0.1));

            Map<String, Object> api = (Map<String, Object>) summary.get("api");
            assertThat(api.get("totalCalls")).isEqualTo(2L);
        }
    }
}
