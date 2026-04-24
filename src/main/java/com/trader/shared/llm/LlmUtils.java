package com.trader.shared.llm;

/**
 * LLM 相關工具類別（provider-agnostic）
 */
public final class LlmUtils {

    private LlmUtils() {
        // util class — no instantiation
    }

    /**
     * 將 float[] 向量轉為 pgvector 格式字串 "[0.1,0.2,...]"
     */
    public static String vectorToString(float[] vector) {
        if (vector == null) {
            throw new IllegalArgumentException("vector 不可為 null");
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
