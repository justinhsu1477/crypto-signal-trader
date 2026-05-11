package com.trader.trading.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SignalMetrics 單元測試
 *
 * 用 SimpleMeterRegistry 驗證 Counter 是否被正確標籤化、累加、隔離。
 */
class SignalMetricsTest {

    @Test
    @DisplayName("recordImageSignal — 同 result tag 累加同一個 Counter")
    void recordImageSignal_incrementsCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SignalMetrics metrics = new SignalMetrics(registry);

        metrics.recordImageSignal("received");
        metrics.recordImageSignal("received");

        Counter c = registry.find("signal_image_total").tag("result", "received").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("recordImageSignal — 不同 result tag 走不同 Counter")
    void recordImageSignal_separateLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SignalMetrics metrics = new SignalMetrics(registry);

        metrics.recordImageSignal("received");
        metrics.recordImageSignal("skip");
        metrics.recordImageSignal("skip");

        assertThat(registry.find("signal_image_total").tag("result", "received").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("signal_image_total").tag("result", "skip").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("recordCompoundAction — close / move_sl 分別計數")
    void recordCompoundAction_separateLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SignalMetrics metrics = new SignalMetrics(registry);

        metrics.recordCompoundAction("close");
        metrics.recordCompoundAction("move_sl");

        assertThat(registry.find("signal_compound_total").tag("action", "close").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("signal_compound_total").tag("action", "move_sl").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("recordCompoundAction — 同 action 累加")
    void recordCompoundAction_accumulates() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SignalMetrics metrics = new SignalMetrics(registry);

        for (int i = 0; i < 5; i++) {
            metrics.recordCompoundAction("close");
        }

        assertThat(registry.find("signal_compound_total").tag("action", "close").counter().count())
                .isEqualTo(5.0);
    }
}
