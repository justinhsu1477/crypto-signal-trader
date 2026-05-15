package com.trader.shared.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuditServiceTest {

    @Test
    void hashOrNull_null_input_returns_null() {
        assertThat(AdminAuditService.hashOrNull(null)).isNull();
    }

    @Test
    void hashOrNull_empty_string_returns_hash() {
        // Empty string has a well-defined SHA-256
        String hash = AdminAuditService.hashOrNull("");
        assertThat(hash).hasSize(16).matches("[0-9a-f]{16}");
    }

    @Test
    void hashOrNull_is_deterministic() {
        String value = "陳哥說「保護」=移動 SL 到 entry";
        assertThat(AdminAuditService.hashOrNull(value))
                .isEqualTo(AdminAuditService.hashOrNull(value));
    }

    @Test
    void hashOrNull_different_inputs_differ() {
        assertThat(AdminAuditService.hashOrNull("foo"))
                .isNotEqualTo(AdminAuditService.hashOrNull("bar"));
    }

    @Test
    void hashOrNull_first_16_hex_format() {
        String hash = AdminAuditService.hashOrNull("test");
        assertThat(hash).hasSize(16).matches("[0-9a-f]{16}");
    }
}
