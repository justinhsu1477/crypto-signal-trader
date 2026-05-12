package com.trader.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trader.shared.model.TradeRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Python ↔ Java 契約測試
 *
 * 載入 tests/fixtures/payloads/ 下的 JSON fixture，
 * 用 Jackson ObjectMapper 反序列化為 TradeRequest，
 * 驗證 Python 端送出的關鍵欄位能被 Java 正確接收。
 *
 * Why this matters：
 * Unit tests with mocks lie about schema. JSON fixtures give us exact wire-format
 * snapshots — Java 與 Python 雙方都必須遵守。當 Python 改 payload 結構而忘了改 Java DTO
 * （或反之），此測試直接抓到。
 */
class PythonPayloadContractTest {

    private static final Path FIXTURES_DIR = Path.of("tests", "fixtures", "payloads");

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TradeRequest loadFixture(String name) throws Exception {
        Path file = FIXTURES_DIR.resolve(name);
        return objectMapper.readValue(Files.readString(file), TradeRequest.class);
    }

    // ==================== text-entry ====================

    @Test
    @DisplayName("text ENTRY payload — 基準欄位（action / symbol / side / 價格區間 / source）正確反序列化")
    void textEntryPayloadDeserializes() throws Exception {
        TradeRequest req = loadFixture("text-entry.json");

        assertThat(req.getAction()).isEqualTo("ENTRY");
        assertThat(req.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(req.getSide()).isEqualTo("SHORT");
        assertThat(req.getEntryPrice()).isEqualTo(82200.0);
        assertThat(req.getStopLoss()).isEqualTo(83800.0);
        assertThat(req.getTakeProfit()).isEqualTo(80600.0);
        assertThat(req.getSignalTimestamp()).isEqualTo(1747000000000L);

        assertThat(req.getSource()).isNotNull();
        assertThat(req.getSource().getPlatform()).isEqualTo("DISCORD");
        assertThat(req.getSource().getChannelId()).isEqualTo("1234567890");
        assertThat(req.getSource().getGuildId()).isEqualTo("9876543210");
        assertThat(req.getSource().getAuthorName()).isEqualTo("signal-channel");
        assertThat(req.getSource().getMessageId()).isEqualTo("msg_text_001");
        // 文字訊號沒有 attachment
        assertThat(req.getSource().getAttachmentSha256()).isNull();
    }

    // ==================== image-entry（核心契約：attachment.sha256） ====================

    @Test
    @DisplayName("image ENTRY payload — source.attachment.sha256 必須保留（之前的 audit bug 不能再發生）")
    void imageEntryPayloadPreservesAttachmentSha256() throws Exception {
        TradeRequest req = loadFixture("image-entry.json");

        assertThat(req.getSource()).isNotNull();
        assertThat(req.getSource().getAttachmentSha256())
                .isNotNull()
                .hasSize(64)  // SHA-256 hex
                .isEqualTo("a3b1c8d5e9f2147ba6c3d8e9f10b21c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9");
    }

    @Test
    @DisplayName("image ENTRY payload — 其餘欄位與 text 一致")
    void imageEntryPayloadOtherFieldsCorrect() throws Exception {
        TradeRequest req = loadFixture("image-entry.json");

        assertThat(req.getAction()).isEqualTo("ENTRY");
        assertThat(req.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(req.getSide()).isEqualTo("SHORT");
        assertThat(req.getEntryPrice()).isEqualTo(82200.0);
        assertThat(req.getSource().getMessageId()).isEqualTo("msg_image_001");
    }

    // ==================== compound-close-half ====================

    @Test
    @DisplayName("compound CLOSE half payload — close_ratio + __close 後綴 message_id 正確")
    void compoundCloseHalfPayloadDeserializes() throws Exception {
        TradeRequest req = loadFixture("compound-close-half.json");

        assertThat(req.getAction()).isEqualTo("CLOSE");
        assertThat(req.getSymbol()).isEqualTo("BTCUSDT");
        assertThat(req.getCloseRatio()).isEqualTo(0.5);
        assertThat(req.getSource().getMessageId()).endsWith("__close");
    }

    // ==================== compound-movesl-breakeven ====================

    @Test
    @DisplayName("compound MOVE_SL payload — 無 new_stop_loss 觸發 Java 端 breakeven 邏輯")
    void compoundMoveSLHasNullNewStopLoss() throws Exception {
        TradeRequest req = loadFixture("compound-movesl-breakeven.json");

        assertThat(req.getAction()).isEqualTo("MOVE_SL");
        assertThat(req.getNewStopLoss()).isNull();
        assertThat(req.getSource().getMessageId()).endsWith("__move_sl");
    }

    // ==================== Round-trip 欄位 drift 檢查 ====================

    @Test
    @DisplayName("Round-trip — 反序列化 → 重新序列化後 Java DTO 已知的欄位不可丟")
    void allFixturesPreserveKnownFieldsOnRoundTrip() throws Exception {
        // 此測試的意圖：
        // Java DTO 已宣告的欄位（TradeRequest / SignalSource 內以 @JsonProperty 對到的）
        // 在 round-trip 後必須與原值完全一致。
        //
        // Python 送出的、但 Java DTO 未對應的欄位（如 prompt_version / source.source_name /
        // source.display_name / source.trade_mode / source.risk_multiplier）會被 Jackson
        // 忽略，這是 Spring Boot 預設 FAIL_ON_UNKNOWN_PROPERTIES=false 的行為。
        // 那些「應該但還沒在 Java DTO 出現的欄位」屬於另一類 drift，由獨立測試追蹤。
        //
        // 這個測試在意的是：對 Java 宣告過的欄位，Python 改格式就會 break。
        for (String name : List.of(
                "text-entry.json",
                "image-entry.json",
                "compound-close-half.json",
                "compound-movesl-breakeven.json")) {
            String original = Files.readString(FIXTURES_DIR.resolve(name));
            TradeRequest req = objectMapper.readValue(original, TradeRequest.class);
            String roundTripped = objectMapper.writeValueAsString(req);

            JsonNode origNode = objectMapper.readTree(original);
            JsonNode rtNode = objectMapper.readTree(roundTripped);

            // 對每個 Java 已知欄位做斷言（針對 root 層）
            assertKnownFieldEquals(origNode, rtNode, "action", name);
            assertKnownFieldEquals(origNode, rtNode, "symbol", name);
            assertKnownFieldEquals(origNode, rtNode, "side", name);
            assertKnownFieldEquals(origNode, rtNode, "entry_price", name);
            assertKnownFieldEquals(origNode, rtNode, "stop_loss", name);
            assertKnownFieldEquals(origNode, rtNode, "take_profit", name);
            assertKnownFieldEquals(origNode, rtNode, "close_ratio", name);
            assertKnownFieldEquals(origNode, rtNode, "new_stop_loss", name);
            assertKnownFieldEquals(origNode, rtNode, "signal_timestamp", name);

            // source 子物件
            JsonNode origSrc = origNode.path("source");
            JsonNode rtSrc = rtNode.path("source");
            if (!origSrc.isMissingNode()) {
                for (String f : List.of("platform", "channel_id", "channel_name",
                        "guild_id", "author_name", "message_id")) {
                    assertKnownFieldEquals(origSrc, rtSrc, f, name + ":source");
                }
            }
        }
    }

    /**
     * 兩個 JsonNode 在指定欄位 key 上必須相等。
     * 數值用 BigDecimal 數值比對（容忍 int↔double 的型別差，例如 82200 vs 82200.0），
     * 其他型別用 Node equality。
     * 若 origin 沒這 key 就跳過。
     */
    private void assertKnownFieldEquals(JsonNode orig, JsonNode rt, String key, String ctx) {
        JsonNode origVal = orig.get(key);
        if (origVal == null || origVal.isNull()) {
            return; // origin 沒這欄位，無需比對
        }
        JsonNode rtVal = rt.get(key);
        assertThat(rtVal)
                .as("Round-trip drift at %s.%s — Python 送了，Java 反序列化後丟失", ctx, key)
                .isNotNull();

        if (origVal.isNumber() && rtVal.isNumber()) {
            assertThat(rtVal.decimalValue())
                    .as("Round-trip numeric drift at %s.%s", ctx, key)
                    .isEqualByComparingTo(origVal.decimalValue());
        } else {
            assertThat(rtVal)
                    .as("Round-trip drift at %s.%s — Python 送了，Java 反序列化後丟失或改值", ctx, key)
                    .isEqualTo(origVal);
        }
    }
}
