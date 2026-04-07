package com.trader.trading.repository;

import com.trader.trading.entity.Signal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SignalRepository — @DataJpaTest Slice Test
 *
 * 使用 H2 in-memory（MODE=PostgreSQL）驗證：
 * - Entity ↔ Table mapping（欄位名稱、型別、nullable）
 * - Spring Data 衍生查詢（method name → JPQL）正確產生 SQL
 * - Index 不與 Hibernate DDL 衝突
 *
 * 與 unit test 差異：unit test mock Repository，無法抓到
 * query typo、column mapping 錯誤、@PrePersist 行為。
 */
@DataJpaTest
@ActiveProfiles("slicetest")
@DisplayName("SignalRepository — @DataJpaTest Slice Test")
class SignalRepositorySliceTest {

    @Autowired
    private SignalRepository signalRepository;

    @BeforeEach
    void setUp() {
        signalRepository.deleteAll();
    }

    private Signal buildSignal(String id, String symbol, String side, String status, String messageId) {
        return Signal.builder()
                .signalId(id)
                .sourcePlatform("DISCORD")
                .sourceChannelId("ch-1")
                .sourceChannelName("test-channel")
                .sourceAuthorName("tester")
                .sourceMessageId(messageId)
                .action("ENTRY")
                .symbol(symbol)
                .side(side)
                .entryPriceLow(95000.0)
                .entryPriceHigh(96000.0)
                .stopLoss(94000.0)
                .leverage(20)
                .signalHash("hash-" + id)
                .executionStatus(status)
                .rawMessage("test raw message")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Nested
    @DisplayName("Entity 持久化")
    class PersistenceTests {

        @Test
        @DisplayName("save + findById — 基本 CRUD")
        void saveAndFind() {
            Signal signal = buildSignal("sig-1", "BTCUSDT", "LONG", "EXECUTED", "msg-1");
            signalRepository.save(signal);

            var found = signalRepository.findById("sig-1");
            assertThat(found).isPresent();
            assertThat(found.get().getSymbol()).isEqualTo("BTCUSDT");
            assertThat(found.get().getSide()).isEqualTo("LONG");
            assertThat(found.get().getEntryPriceLow()).isEqualTo(95000.0);
        }

        @Test
        @DisplayName("@PrePersist — createdAt 自動填入")
        void prePersistSetsCreatedAt() {
            Signal signal = Signal.builder()
                    .signalId("sig-pp")
                    .symbol("BTCUSDT")
                    .action("ENTRY")
                    .build();
            // createdAt 為 null，@PrePersist 應自動填入

            signalRepository.save(signal);

            var found = signalRepository.findById("sig-pp");
            assertThat(found).isPresent();
            assertThat(found.get().getCreatedAt()).isNotNull();
        }

        @Test
        @DisplayName("TEXT 欄位 — takeProfits 可存長 JSON")
        void textColumnStoresLongJson() {
            String longJson = "[" + "95500.0,".repeat(100) + "96000.0]";
            Signal signal = buildSignal("sig-text", "BTCUSDT", "LONG", "EXECUTED", "msg-text");
            signal.setTakeProfits(longJson);

            signalRepository.save(signal);

            var found = signalRepository.findById("sig-text");
            assertThat(found).isPresent();
            assertThat(found.get().getTakeProfits()).isEqualTo(longJson);
        }
    }

    @Nested
    @DisplayName("衍生查詢")
    class DerivedQueryTests {

        @Test
        @DisplayName("findBySymbol — 依幣種查詢")
        void findBySymbol() {
            signalRepository.save(buildSignal("s1", "BTCUSDT", "LONG", "EXECUTED", "m1"));
            signalRepository.save(buildSignal("s2", "ETHUSDT", "SHORT", "EXECUTED", "m2"));
            signalRepository.save(buildSignal("s3", "BTCUSDT", "SHORT", "REJECTED", "m3"));

            List<Signal> btcSignals = signalRepository.findBySymbol("BTCUSDT");

            assertThat(btcSignals).hasSize(2);
            assertThat(btcSignals).allMatch(s -> s.getSymbol().equals("BTCUSDT"));
        }

        @Test
        @DisplayName("findBySignalHash — 依 hash 查詢（去重用）")
        void findBySignalHash() {
            Signal signal = buildSignal("s1", "BTCUSDT", "LONG", "EXECUTED", "m1");
            signal.setSignalHash("unique-hash-abc");
            signalRepository.save(signal);

            List<Signal> found = signalRepository.findBySignalHash("unique-hash-abc");
            assertThat(found).hasSize(1);
            assertThat(found.get(0).getSignalId()).isEqualTo("s1");
        }

        @Test
        @DisplayName("findBySignalHash — 不存在的 hash → 空清單")
        void findBySignalHashNotFound() {
            List<Signal> found = signalRepository.findBySignalHash("nonexistent");
            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("findByExecutionStatus — 依執行狀態查詢")
        void findByExecutionStatus() {
            signalRepository.save(buildSignal("s1", "BTCUSDT", "LONG", "EXECUTED", "m1"));
            signalRepository.save(buildSignal("s2", "BTCUSDT", "SHORT", "REJECTED", "m2"));
            signalRepository.save(buildSignal("s3", "ETHUSDT", "LONG", "EXECUTED", "m3"));

            List<Signal> executed = signalRepository.findByExecutionStatus("EXECUTED");

            assertThat(executed).hasSize(2);
            assertThat(executed).allMatch(s -> s.getExecutionStatus().equals("EXECUTED"));
        }

        @Test
        @DisplayName("existsBySourceMessageId — message_id 永久去重")
        void existsBySourceMessageId() {
            signalRepository.save(buildSignal("s1", "BTCUSDT", "LONG", "EXECUTED", "discord-msg-123"));

            assertThat(signalRepository.existsBySourceMessageId("discord-msg-123")).isTrue();
            assertThat(signalRepository.existsBySourceMessageId("discord-msg-999")).isFalse();
        }
    }

    @Nested
    @DisplayName("邊界情況")
    class EdgeCases {

        @Test
        @DisplayName("nullable 欄位 — sourceMessageId 為 null 也能存取")
        void nullableFieldsWork() {
            Signal signal = Signal.builder()
                    .signalId("sig-null")
                    .symbol("BTCUSDT")
                    .action("ENTRY")
                    .sourceMessageId(null)
                    .build();

            signalRepository.save(signal);

            // null sourceMessageId 的 signal 應可正常 persist + 查詢
            assertThat(signalRepository.findById("sig-null")).isPresent();
            assertThat(signalRepository.findById("sig-null").get().getSourceMessageId()).isNull();
            // 查詢不存在的 messageId 回傳 false
            assertThat(signalRepository.existsBySourceMessageId("nonexistent-id")).isFalse();
        }

        @Test
        @DisplayName("批次寫入 + count")
        void batchSaveAndCount() {
            for (int i = 0; i < 10; i++) {
                signalRepository.save(
                        buildSignal("batch-" + i, "BTCUSDT", "LONG", "EXECUTED", "bmsg-" + i));
            }

            assertThat(signalRepository.count()).isEqualTo(10);
            assertThat(signalRepository.findBySymbol("BTCUSDT")).hasSize(10);
        }
    }
}
