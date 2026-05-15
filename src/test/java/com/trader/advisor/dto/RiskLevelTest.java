package com.trader.advisor.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RiskLevelTest {

    @Test
    void fromGeminiOrInfer_uppercase_match() {
        assertThat(RiskLevel.fromGeminiOrInfer("LOW", 80)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.fromGeminiOrInfer("MEDIUM", 50)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.fromGeminiOrInfer("HIGH", 20)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void fromGeminiOrInfer_lowercase_and_whitespace_tolerated() {
        assertThat(RiskLevel.fromGeminiOrInfer("low", 80)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.fromGeminiOrInfer("  Medium  ", 50)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.fromGeminiOrInfer("hIgH", 20)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void fromGeminiOrInfer_null_falls_back_to_confidence() {
        assertThat(RiskLevel.fromGeminiOrInfer(null, 80)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.fromGeminiOrInfer(null, 50)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.fromGeminiOrInfer(null, 20)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void fromGeminiOrInfer_garbage_falls_back_to_confidence() {
        assertThat(RiskLevel.fromGeminiOrInfer("INSANE", 90)).isEqualTo(RiskLevel.LOW);
        assertThat(RiskLevel.fromGeminiOrInfer("", 45)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.fromGeminiOrInfer("???", 10)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void fromGeminiOrInfer_boundary_confidence() {
        assertThat(RiskLevel.fromGeminiOrInfer(null, 70)).isEqualTo(RiskLevel.LOW);   // 70 = LOW lower bound
        assertThat(RiskLevel.fromGeminiOrInfer(null, 69)).isEqualTo(RiskLevel.MEDIUM);
        assertThat(RiskLevel.fromGeminiOrInfer(null, 40)).isEqualTo(RiskLevel.MEDIUM); // 40 = MEDIUM lower bound
        assertThat(RiskLevel.fromGeminiOrInfer(null, 39)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    void display_and_emoji_are_zh_tw() {
        assertThat(RiskLevel.LOW.getDisplay()).isEqualTo("低風險");
        assertThat(RiskLevel.MEDIUM.getDisplay()).isEqualTo("中風險");
        assertThat(RiskLevel.HIGH.getDisplay()).isEqualTo("高風險");

        assertThat(RiskLevel.LOW.getEmoji()).isEqualTo("✅");
        assertThat(RiskLevel.MEDIUM.getEmoji()).isEqualTo("⚠️");
        assertThat(RiskLevel.HIGH.getEmoji()).isEqualTo("🔴");
    }
}
