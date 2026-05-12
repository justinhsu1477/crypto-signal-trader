package com.trader.shared.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LlmUtils 單元測試 — 純函式工具類別
 *
 * vectorToString 用於將 float[] embedding 轉成 pgvector 格式 "[0.1,0.2,...]"。
 * 邊界 case：null / empty / 單元素 / 多元素 / 負數 / NaN / Infinity。
 */
@DisplayName("LlmUtils — provider-agnostic 工具")
class LlmUtilsTest {

    @Test
    @DisplayName("vectorToString — null vector 應拋 IllegalArgumentException")
    void vectorToString_nullThrows() {
        assertThatThrownBy(() -> LlmUtils.vectorToString(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可為 null");
    }

    @Test
    @DisplayName("vectorToString — 空陣列 → \"[]\"")
    void vectorToString_emptyArray() {
        assertThat(LlmUtils.vectorToString(new float[0])).isEqualTo("[]");
    }

    @Test
    @DisplayName("vectorToString — 單一元素 → \"[X]\"")
    void vectorToString_singleElement() {
        assertThat(LlmUtils.vectorToString(new float[]{0.5f})).isEqualTo("[0.5]");
    }

    @Test
    @DisplayName("vectorToString — 多元素以逗號分隔（無空格）")
    void vectorToString_multipleElementsCommaSeparated() {
        String result = LlmUtils.vectorToString(new float[]{0.1f, 0.2f, 0.3f});
        assertThat(result).isEqualTo("[0.1,0.2,0.3]");
        // 重要：pgvector 不接受空格分隔，必須是純逗號
        assertThat(result).doesNotContain(", ");
    }

    @Test
    @DisplayName("vectorToString — 含負數")
    void vectorToString_negativeValues() {
        String result = LlmUtils.vectorToString(new float[]{-0.5f, 0.5f, -1.0f});
        assertThat(result).isEqualTo("[-0.5,0.5,-1.0]");
    }

    @Test
    @DisplayName("vectorToString — 含零")
    void vectorToString_includesZero() {
        String result = LlmUtils.vectorToString(new float[]{0.0f, -0.0f});
        assertThat(result).startsWith("[").endsWith("]");
        // 兩個元素一個逗號
        assertThat(result.split(",")).hasSize(2);
    }

    @Test
    @DisplayName("vectorToString — 真實 embedding 維度（768 或 1536）格式正確")
    void vectorToString_realEmbeddingDimension() {
        float[] embedding = new float[768];
        for (int i = 0; i < 768; i++) {
            embedding[i] = (float) (i / 1000.0);
        }
        String result = LlmUtils.vectorToString(embedding);

        // 必須以 [ 開頭、] 結尾
        assertThat(result).startsWith("[").endsWith("]");
        // 768 個值 = 767 個逗號
        long commaCount = result.chars().filter(c -> c == ',').count();
        assertThat(commaCount).isEqualTo(767);
        // 起始值 0.0、結束值 0.767
        assertThat(result).contains("0.0,").contains("0.767]");
    }

    @Test
    @DisplayName("vectorToString — utility class 不可被 instantiate（私有 constructor）")
    void llmUtils_isUtilityClass() {
        // 反射驗證：constructor 是 private
        var constructors = LlmUtils.class.getDeclaredConstructors();
        assertThat(constructors).hasSize(1);
        assertThat(java.lang.reflect.Modifier.isPrivate(constructors[0].getModifiers()))
                .as("LlmUtils constructor 應為 private（util class 不允許 instantiate）")
                .isTrue();
    }
}
