package com.trader.shared.util;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/**
 * Admin 列表排序工具
 *
 * 設計重點：
 * - Null 永遠排最後（不論 asc/desc），提供最直覺的 UX
 * - 未知 sortBy → fallback 到預設欄位（不報 400，前後端版本可能不同步）
 * - 無效 sortDir → fallback 到 asc
 * - 使用 field factory 方法建立欄位定義，讓 Controller 宣告簡潔
 *
 * 面試重點：為什麼不用 Comparator.reversed() + Comparator.nullsLast()？
 *   → reversed() 會連帶翻轉 null 位置，nullsLast → nullsFirst。
 *   → 本工具在 Comparator 內部處理 null + 方向，確保 null 永遠排最後。
 */
public final class SortHelper {

    private SortHelper() {
    }

    /**
     * 通用排序：根據 sortBy 取出 Comparator Factory，配合 sortDir 建立 Comparator 並排序。
     *
     * @param list         要排序的列表（不會被修改，回傳新 List）
     * @param sortBy       排序欄位名稱
     * @param sortDir      排序方向（"asc" / "desc"，其他值 fallback 到 asc）
     * @param fields       欄位名 → Comparator Factory（使用 stringField/intField 等方法建立）
     * @param defaultField sortBy 不在 map 中時的 fallback 欄位
     */
    public static <T> List<T> sort(List<T> list,
                                   String sortBy,
                                   String sortDir,
                                   Map<String, Function<Boolean, Comparator<T>>> fields,
                                   String defaultField) {
        if (list == null || list.isEmpty()) {
            return list;
        }

        String field = fields.containsKey(sortBy) ? sortBy : defaultField;
        Function<Boolean, Comparator<T>> factory = fields.get(field);
        if (factory == null) {
            return list;
        }

        boolean descending = "desc".equalsIgnoreCase(sortDir);
        Comparator<T> comparator = factory.apply(descending);

        return list.stream().sorted(comparator).toList();
    }

    // ==================== Field Factory 方法 ====================
    // 供 Controller 宣告 SORT_FIELDS Map 時使用，回傳 Map.Entry 可直接放入 Map.ofEntries()

    /** 字串欄位（case-insensitive，null 排最後） */
    public static <T> Map.Entry<String, Function<Boolean, Comparator<T>>> stringField(
            String name, Function<T, String> extractor) {
        return Map.entry(name, desc -> buildStringComparator(extractor, desc));
    }

    /** Comparable 欄位（如 BigDecimal、LocalDateTime，null 排最後） */
    public static <T, V extends Comparable<V>> Map.Entry<String, Function<Boolean, Comparator<T>>> comparableField(
            String name, Function<T, V> extractor) {
        return Map.entry(name, desc -> buildComparableComparator(extractor, desc));
    }

    /** int 欄位（primitive，無 null 問題） */
    public static <T> Map.Entry<String, Function<Boolean, Comparator<T>>> intField(
            String name, ToIntFunction<T> extractor) {
        return Map.entry(name, desc -> (a, b) -> {
            int cmp = Integer.compare(extractor.applyAsInt(a), extractor.applyAsInt(b));
            return desc ? -cmp : cmp;
        });
    }

    /** long 欄位（primitive，無 null 問題） */
    public static <T> Map.Entry<String, Function<Boolean, Comparator<T>>> longField(
            String name, ToLongFunction<T> extractor) {
        return Map.entry(name, desc -> (a, b) -> {
            int cmp = Long.compare(extractor.applyAsLong(a), extractor.applyAsLong(b));
            return desc ? -cmp : cmp;
        });
    }

    /** double 欄位（primitive，無 null 問題） */
    public static <T> Map.Entry<String, Function<Boolean, Comparator<T>>> doubleField(
            String name, ToDoubleFunction<T> extractor) {
        return Map.entry(name, desc -> (a, b) -> {
            int cmp = Double.compare(extractor.applyAsDouble(a), extractor.applyAsDouble(b));
            return desc ? -cmp : cmp;
        });
    }

    /** Boolean 欄位（asc 時 true 排前面；null 排最後） */
    public static <T> Map.Entry<String, Function<Boolean, Comparator<T>>> booleanField(
            String name, Function<T, Boolean> extractor) {
        return Map.entry(name, desc -> buildBooleanComparator(extractor, desc));
    }

    // ==================== 內部 Comparator 建構 ====================

    /**
     * 字串比較器（case-insensitive）
     * Null 永遠排最後，不受 desc 影響。
     */
    static <T> Comparator<T> buildStringComparator(Function<T, String> extractor, boolean desc) {
        return (a, b) -> {
            String va = extractor.apply(a);
            String vb = extractor.apply(b);
            if (va == null && vb == null) return 0;
            if (va == null) return 1;   // null 永遠排最後
            if (vb == null) return -1;
            int cmp = va.compareToIgnoreCase(vb);
            return desc ? -cmp : cmp;
        };
    }

    /**
     * Comparable 比較器（如 BigDecimal、LocalDateTime）
     * Null 永遠排最後，不受 desc 影響。
     */
    static <T, V extends Comparable<V>> Comparator<T> buildComparableComparator(
            Function<T, V> extractor, boolean desc) {
        return (a, b) -> {
            V va = extractor.apply(a);
            V vb = extractor.apply(b);
            if (va == null && vb == null) return 0;
            if (va == null) return 1;
            if (vb == null) return -1;
            int cmp = va.compareTo(vb);
            return desc ? -cmp : cmp;
        };
    }

    /**
     * Boolean 比較器
     * asc 時 true 排前面（直覺：「啟用的用戶先顯示」），desc 反之。
     * Null 永遠排最後。
     */
    static <T> Comparator<T> buildBooleanComparator(Function<T, Boolean> extractor, boolean desc) {
        return (a, b) -> {
            Boolean va = extractor.apply(a);
            Boolean vb = extractor.apply(b);
            if (va == null && vb == null) return 0;
            if (va == null) return 1;
            if (vb == null) return -1;
            // Boolean.compare: false(0) < true(1)
            // 我們要 asc 時 true 在前 → 反轉 → compare(vb, va)
            int cmp = Boolean.compare(vb, va);
            return desc ? -cmp : cmp;
        };
    }
}
