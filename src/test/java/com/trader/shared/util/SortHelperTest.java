package com.trader.shared.util;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.*;

/**
 * SortHelper 單元測試
 *
 * 覆蓋：字串/數值/Boolean/Comparable 排序 + null 處理 + fallback 邏輯
 */
class SortHelperTest {

    // ==================== Test DTO ====================

    record Item(String name, Integer score, Boolean active, LocalDateTime createdAt) {
    }

    private static final Map<String, Function<Boolean, Comparator<Item>>> FIELDS = Map.ofEntries(
            SortHelper.stringField("name", Item::name),
            SortHelper.comparableField("score", Item::score),
            SortHelper.booleanField("active", Item::active),
            SortHelper.comparableField("createdAt", Item::createdAt)
    );

    // ==================== String 排序 ====================

    @Test
    @DisplayName("String asc — 字母順序")
    void stringAsc() {
        List<Item> items = List.of(
                new Item("Charlie", 1, true, null),
                new Item("Alice", 2, true, null),
                new Item("Bob", 3, true, null));

        List<Item> sorted = SortHelper.sort(items, "name", "asc", FIELDS, "name");

        assertThat(sorted).extracting(Item::name)
                .containsExactly("Alice", "Bob", "Charlie");
    }

    @Test
    @DisplayName("String desc — 反向字母順序")
    void stringDesc() {
        List<Item> items = List.of(
                new Item("Alice", 1, true, null),
                new Item("Charlie", 2, true, null),
                new Item("Bob", 3, true, null));

        List<Item> sorted = SortHelper.sort(items, "name", "desc", FIELDS, "name");

        assertThat(sorted).extracting(Item::name)
                .containsExactly("Charlie", "Bob", "Alice");
    }

    @Test
    @DisplayName("String case-insensitive — 大小寫不影響排序")
    void stringCaseInsensitive() {
        List<Item> items = List.of(
                new Item("banana", 1, true, null),
                new Item("Apple", 2, true, null),
                new Item("Cherry", 3, true, null));

        List<Item> sorted = SortHelper.sort(items, "name", "asc", FIELDS, "name");

        assertThat(sorted).extracting(Item::name)
                .containsExactly("Apple", "banana", "Cherry");
    }

    // ==================== 數值排序 ====================

    @Test
    @DisplayName("Comparable (Integer) asc")
    void comparableAsc() {
        List<Item> items = List.of(
                new Item("A", 30, true, null),
                new Item("B", 10, true, null),
                new Item("C", 20, true, null));

        List<Item> sorted = SortHelper.sort(items, "score", "asc", FIELDS, "name");

        assertThat(sorted).extracting(Item::score)
                .containsExactly(10, 20, 30);
    }

    @Test
    @DisplayName("Comparable (Integer) desc")
    void comparableDesc() {
        List<Item> items = List.of(
                new Item("A", 30, true, null),
                new Item("B", 10, true, null),
                new Item("C", 20, true, null));

        List<Item> sorted = SortHelper.sort(items, "score", "desc", FIELDS, "name");

        assertThat(sorted).extracting(Item::score)
                .containsExactly(30, 20, 10);
    }

    // ==================== Primitive 欄位 ====================

    @Test
    @DisplayName("intField / longField / doubleField — primitive 排序")
    void primitiveFields() {
        record NumItem(int intVal, long longVal, double doubleVal) {
        }

        Map<String, Function<Boolean, Comparator<NumItem>>> fields = Map.ofEntries(
                SortHelper.intField("intVal", NumItem::intVal),
                SortHelper.longField("longVal", NumItem::longVal),
                SortHelper.doubleField("doubleVal", NumItem::doubleVal)
        );

        List<NumItem> items = List.of(
                new NumItem(3, 300L, 3.0),
                new NumItem(1, 100L, 1.0),
                new NumItem(2, 200L, 2.0));

        assertThat(SortHelper.sort(items, "intVal", "asc", fields, "intVal"))
                .extracting(NumItem::intVal).containsExactly(1, 2, 3);

        assertThat(SortHelper.sort(items, "longVal", "desc", fields, "longVal"))
                .extracting(NumItem::longVal).containsExactly(300L, 200L, 100L);

        assertThat(SortHelper.sort(items, "doubleVal", "asc", fields, "doubleVal"))
                .extracting(NumItem::doubleVal).containsExactly(1.0, 2.0, 3.0);
    }

    // ==================== Boolean 排序 ====================

    @Test
    @DisplayName("Boolean asc — true 排前面")
    void booleanAsc() {
        List<Item> items = List.of(
                new Item("A", 1, false, null),
                new Item("B", 2, true, null),
                new Item("C", 3, false, null));

        List<Item> sorted = SortHelper.sort(items, "active", "asc", FIELDS, "name");

        assertThat(sorted).extracting(Item::active)
                .containsExactly(true, false, false);
    }

    @Test
    @DisplayName("Boolean desc — false 排前面")
    void booleanDesc() {
        List<Item> items = List.of(
                new Item("A", 1, true, null),
                new Item("B", 2, false, null),
                new Item("C", 3, true, null));

        List<Item> sorted = SortHelper.sort(items, "active", "desc", FIELDS, "name");

        assertThat(sorted).extracting(Item::active)
                .containsExactly(false, true, true);
    }

    // ==================== Null 處理 ====================

    @Test
    @DisplayName("Null 值 asc — null 排最後")
    void nullsLastAsc() {
        List<Item> items = List.of(
                new Item(null, 1, true, null),
                new Item("Alice", 2, true, null),
                new Item(null, 3, true, null),
                new Item("Bob", 4, true, null));

        List<Item> sorted = SortHelper.sort(items, "name", "asc", FIELDS, "name");

        assertThat(sorted).extracting(Item::name)
                .containsExactly("Alice", "Bob", null, null);
    }

    @Test
    @DisplayName("Null 值 desc — null 仍然排最後（不被 reverse 翻到最前面）")
    void nullsLastDesc() {
        List<Item> items = List.of(
                new Item(null, 1, true, null),
                new Item("Alice", 2, true, null),
                new Item("Bob", 3, true, null),
                new Item(null, 4, true, null));

        List<Item> sorted = SortHelper.sort(items, "name", "desc", FIELDS, "name");

        assertThat(sorted).extracting(Item::name)
                .containsExactly("Bob", "Alice", null, null);
    }

    @Test
    @DisplayName("LocalDateTime 含 null — null 排最後")
    void localDateTimeWithNulls() {
        LocalDateTime t1 = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 3, 1, 0, 0);

        List<Item> items = List.of(
                new Item("A", 1, true, null),
                new Item("B", 2, true, t2),
                new Item("C", 3, true, t1));

        List<Item> sorted = SortHelper.sort(items, "createdAt", "asc", FIELDS, "name");

        assertThat(sorted).extracting(Item::name)
                .containsExactly("C", "B", "A");  // t1, t2, null
    }

    @Test
    @DisplayName("LocalDateTime desc 含 null — null 仍排最後")
    void localDateTimeDescWithNulls() {
        LocalDateTime t1 = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 3, 1, 0, 0);

        List<Item> items = List.of(
                new Item("A", 1, true, null),
                new Item("B", 2, true, t1),
                new Item("C", 3, true, t2));

        List<Item> sorted = SortHelper.sort(items, "createdAt", "desc", FIELDS, "name");

        assertThat(sorted).extracting(Item::name)
                .containsExactly("C", "B", "A");  // t2, t1, null
    }

    // ==================== Fallback 邏輯 ====================

    @Test
    @DisplayName("未知 sortBy — fallback 到預設欄位")
    void unknownSortByFallback() {
        List<Item> items = List.of(
                new Item("Charlie", 1, true, null),
                new Item("Alice", 2, true, null),
                new Item("Bob", 3, true, null));

        List<Item> sorted = SortHelper.sort(items, "nonExistentField", "asc", FIELDS, "name");

        // fallback 到 name asc
        assertThat(sorted).extracting(Item::name)
                .containsExactly("Alice", "Bob", "Charlie");
    }

    @Test
    @DisplayName("無效 sortDir — fallback 到 asc")
    void invalidSortDirFallback() {
        List<Item> items = List.of(
                new Item("Charlie", 1, true, null),
                new Item("Alice", 2, true, null),
                new Item("Bob", 3, true, null));

        List<Item> sorted = SortHelper.sort(items, "name", "xyz", FIELDS, "name");

        // "xyz" 不是 "desc" → fallback 到 asc
        assertThat(sorted).extracting(Item::name)
                .containsExactly("Alice", "Bob", "Charlie");
    }

    // ==================== 邊界情況 ====================

    @Test
    @DisplayName("空 list — 回傳空 list")
    void emptyList() {
        List<Item> result = SortHelper.sort(List.of(), "name", "asc", FIELDS, "name");
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("null list — 回傳 null")
    void nullList() {
        List<Item> result = SortHelper.sort(null, "name", "asc", FIELDS, "name");
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("BigDecimal 欄位排序 + null 處理")
    void bigDecimalField() {
        record PayItem(String name, BigDecimal amount) {
        }

        Map<String, Function<Boolean, Comparator<PayItem>>> fields = Map.ofEntries(
                SortHelper.stringField("name", PayItem::name),
                SortHelper.comparableField("amount", PayItem::amount)
        );

        List<PayItem> items = List.of(
                new PayItem("A", new BigDecimal("99.00")),
                new PayItem("B", null),
                new PayItem("C", new BigDecimal("199.00")),
                new PayItem("D", BigDecimal.ZERO));

        List<PayItem> sorted = SortHelper.sort(items, "amount", "desc", fields, "name");

        assertThat(sorted).extracting(PayItem::name)
                .containsExactly("C", "A", "D", "B");  // 199, 99, 0, null
    }
}
