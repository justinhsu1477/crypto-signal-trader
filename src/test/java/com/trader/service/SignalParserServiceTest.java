package com.trader.service;

import com.trader.shared.config.RiskConfig;
import com.trader.shared.model.TradeSignal;
import com.trader.trading.service.SignalParserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SignalParserServiceTest {

    private SignalParserService parser;

    @BeforeEach
    void setUp() {
        RiskConfig riskConfig = new RiskConfig(50000, 2000, true, 0.20, 3, 2.0, 20, List.of("BTCUSDT", "ETHUSDT"), "BTCUSDT");
        parser = new SignalParserService(riskConfig);
    }

    // ==================== Discord ENTRY 格式 ====================

    @Nested
    @DisplayName("Discord ENTRY 訊號解析")
    class DiscordEntry {

        @Test
        @DisplayName("完整 ENTRY 訊號 — 做多 BTC")
        void fullLongEntry() {
            String msg = "📢 交易訊號發布: BTCUSDT\n"
                    + "做多 LONG 🟢 (限價單)\n"
                    + "入場價格 (Entry)\n"
                    + "95000\n"
                    + "止盈目標 (TP)\n"
                    + "98000\n"
                    + "止損價格 (SL)\n"
                    + "93000";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(s.getSide()).isEqualTo(TradeSignal.Side.LONG);
            assertThat(s.getEntryPriceLow()).isEqualTo(95000.0);
            assertThat(s.getStopLoss()).isEqualTo(93000.0);
            assertThat(s.getTakeProfits()).containsExactly(98000.0);
            assertThat(s.getSignalType()).isEqualTo(TradeSignal.SignalType.ENTRY);
        }

        @Test
        @DisplayName("完整 ENTRY 訊號 — 做空 ETH")
        void fullShortEntry() {
            String msg = "📢 交易訊號發布: ETHUSDT\n"
                    + "做空 SHORT 🔴 (限價單)\n"
                    + "入場價格 (Entry)\n"
                    + "2650\n"
                    + "止盈目標 (TP)\n"
                    + "2400\n"
                    + "止損價格 (SL)\n"
                    + "2750";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getSymbol()).isEqualTo("ETHUSDT");
            assertThat(s.getSide()).isEqualTo(TradeSignal.Side.SHORT);
            assertThat(s.getEntryPriceLow()).isEqualTo(2650.0);
            assertThat(s.getStopLoss()).isEqualTo(2750.0);
            assertThat(s.getTakeProfits()).containsExactly(2400.0);
        }

        @Test
        @DisplayName("TP/SL 未設定 — 應回傳 0 和空 list")
        void tpSlNotSet() {
            String msg = "📢 交易訊號發布: BTCUSDT\n"
                    + "做多 LONG 🟢 (限價單)\n"
                    + "入場價格 (Entry)\n"
                    + "95000\n"
                    + "止盈目標 (TP)\n"
                    + "未設定\n"
                    + "止損價格 (SL)\n"
                    + "未設定";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getStopLoss()).isEqualTo(0.0);
            assertThat(s.getTakeProfits()).isEmpty();
        }

        @Test
        @DisplayName("symbol 不帶 USDT — 自動補上")
        void symbolWithoutUsdt() {
            String msg = "📢 交易訊號發布: ETH\n"
                    + "做多 LONG 🟢 (限價單)\n"
                    + "入場價格 (Entry)\n"
                    + "2650\n"
                    + "止盈目標 (TP)\n"
                    + "2790\n"
                    + "止損價格 (SL)\n"
                    + "2580";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            assertThat(result.get().getSymbol()).isEqualTo("ETHUSDT");
        }

        @Test
        @DisplayName("缺少方向 — 解析失敗")
        void missingSide() {
            String msg = "📢 交易訊號發布: BTCUSDT\n"
                    + "入場價格 (Entry)\n"
                    + "95000\n"
                    + "止盈目標 (TP)\n"
                    + "98000\n"
                    + "止損價格 (SL)\n"
                    + "93000";

            assertThat(parser.parse(msg)).isEmpty();
        }

        @Test
        @DisplayName("缺少入場價格 — 解析失敗")
        void missingEntryPrice() {
            String msg = "📢 交易訊號發布: BTCUSDT\n"
                    + "做多 LONG 🟢 (限價單)\n"
                    + "止盈目標 (TP)\n"
                    + "98000\n"
                    + "止損價格 (SL)\n"
                    + "93000";

            assertThat(parser.parse(msg)).isEmpty();
        }

        @Test
        @DisplayName("小數入場價格")
        void decimalEntryPrice() {
            String msg = "📢 交易訊號發布: ETHUSDT\n"
                    + "做多 LONG 🟢 (限價單)\n"
                    + "入場價格 (Entry)\n"
                    + "2650.50\n"
                    + "止盈目標 (TP)\n"
                    + "2790.25\n"
                    + "止損價格 (SL)\n"
                    + "2580.75";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getEntryPriceLow()).isEqualTo(2650.50);
            assertThat(s.getStopLoss()).isEqualTo(2580.75);
            assertThat(s.getTakeProfits()).containsExactly(2790.25);
        }
    }

    // ==================== Discord CANCEL 格式 ====================

    @Nested
    @DisplayName("Discord CANCEL 訊號解析")
    class DiscordCancel {

        @Test
        @DisplayName("標準取消訊號")
        void standardCancel() {
            String msg = "⚠️ 掛單取消: ETHUSDT\n"
                    + "做空 SHORT 🔴\n"
                    + "掛單價格 (Price)\n"
                    + "2850";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getSymbol()).isEqualTo("ETHUSDT");
            assertThat(s.getSide()).isEqualTo(TradeSignal.Side.SHORT);
            assertThat(s.getSignalType()).isEqualTo(TradeSignal.SignalType.CANCEL);
        }

        @Test
        @DisplayName("取消訊號 — symbol 不帶 USDT")
        void cancelWithoutUsdt() {
            String msg = "⚠️ 掛單取消: BTC\n"
                    + "做多 LONG 🟢";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            assertThat(result.get().getSymbol()).isEqualTo("BTCUSDT");
            assertThat(result.get().getSignalType()).isEqualTo(TradeSignal.SignalType.CANCEL);
        }
    }

    // ==================== Discord MODIFY (TP-SL) 格式 ====================

    @Nested
    @DisplayName("Discord TP-SL 修改訊號解析")
    class DiscordModify {

        @Test
        @DisplayName("完整 TP-SL 修改")
        void fullModify() {
            String msg = "訂單/TP-SL 修改: BTCUSDT\n"
                    + "做多 LONG Position Update\n"
                    + "入場價格 (Entry)\n"
                    + "67500\n"
                    + "最新止盈 (New TP)\n"
                    + "69200\n"
                    + "最新止損 (New SL)\n"
                    + "65000";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(s.getSide()).isEqualTo(TradeSignal.Side.LONG);
            assertThat(s.getSignalType()).isEqualTo(TradeSignal.SignalType.MOVE_SL);
            assertThat(s.getNewStopLoss()).isEqualTo(65000.0);
            assertThat(s.getTakeProfits()).containsExactly(69200.0);
        }

        @Test
        @DisplayName("只有新 SL，沒有新 TP")
        void onlyNewSl() {
            String msg = "TP-SL 修改: BTCUSDT\n"
                    + "做多 LONG Position Update\n"
                    + "入場價格 (Entry)\n"
                    + "67500\n"
                    + "最新止盈 (New TP)\n"
                    + "未設定\n"
                    + "最新止損 (New SL)\n"
                    + "66000";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getNewStopLoss()).isEqualTo(66000.0);
            assertThat(s.getTakeProfits()).isEmpty();
        }

        @Test
        @DisplayName("TP 和 SL 都未設定 — 解析失敗")
        void bothNotSet() {
            String msg = "TP-SL 修改: BTCUSDT\n"
                    + "做多 LONG Position Update\n"
                    + "入場價格 (Entry)\n"
                    + "67500\n"
                    + "最新止盈 (New TP)\n"
                    + "未設定\n"
                    + "最新止損 (New SL)\n"
                    + "未設定";

            assertThat(parser.parse(msg)).isEmpty();
        }
    }

    // ==================== 陳哥格式 ====================

    @Nested
    @DisplayName("陳哥策略訊號解析")
    class ChenGeSignal {

        @Test
        @DisplayName("完整限價做空訊號")
        void fullShortSignal() {
            String msg = "⚠️⚠️⚠️⚠️⚠️⚠️\n"
                    + "陈哥合约交易策略【限价】\n"
                    + "BTC，70800-72000附近，做空\n"
                    + "止损预计: 72800\n"
                    + "止盈预计: 68400/66700\n"
                    + "⚠️⚠️⚠️⚠️⚠️⚠️";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(s.getSide()).isEqualTo(TradeSignal.Side.SHORT);
            assertThat(s.getEntryPriceLow()).isEqualTo(70800.0);
            assertThat(s.getEntryPriceHigh()).isEqualTo(72000.0);
            assertThat(s.getStopLoss()).isEqualTo(72800.0);
            assertThat(s.getTakeProfits()).containsExactly(68400.0, 66700.0);
        }

        @Test
        @DisplayName("完整限價做多訊號")
        void fullLongSignal() {
            String msg = "陈哥合约交易策略【限价】\n"
                    + "ETH，2600-2700附近，做多\n"
                    + "止损预计: 2500\n"
                    + "止盈预计: 2900";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getSymbol()).isEqualTo("ETHUSDT");
            assertThat(s.getSide()).isEqualTo(TradeSignal.Side.LONG);
            assertThat(s.getEntryPriceLow()).isEqualTo(2600.0);
            assertThat(s.getEntryPriceHigh()).isEqualTo(2700.0);
            assertThat(s.getStopLoss()).isEqualTo(2500.0);
            assertThat(s.getTakeProfits()).containsExactly(2900.0);
        }

        @Test
        @DisplayName("單價格 + 附近 — BTC，69000附近，做多")
        void singlePriceNearby() {
            String msg = "陈哥合约交易策略【限价】\n"
                    + "BTC，69000附近，做多\n"
                    + "止损预计: 66900\n"
                    + "止盈预计: 72000";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(s.getSide()).isEqualTo(TradeSignal.Side.LONG);
            assertThat(s.getEntryPriceLow()).isEqualTo(69000.0);
            assertThat(s.getEntryPriceHigh()).isEqualTo(69000.0);
            assertThat(s.getStopLoss()).isEqualTo(66900.0);
            assertThat(s.getTakeProfits()).containsExactly(72000.0);
        }

        @Test
        @DisplayName("單價格帶多餘橫線 — BTC，69000-附近，做多")
        void singlePriceDashNearby() {
            String msg = "陈哥合约交易策略【限价】\n"
                    + "BTC，69000-附近，做多\n"
                    + "止损预计: 66900\n"
                    + "止盈预计: 72000";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getSymbol()).isEqualTo("BTCUSDT");
            assertThat(s.getSide()).isEqualTo(TradeSignal.Side.LONG);
            assertThat(s.getEntryPriceLow()).isEqualTo(69000.0);
            assertThat(s.getEntryPriceHigh()).isEqualTo(69000.0);
            assertThat(s.getStopLoss()).isEqualTo(66900.0);
            assertThat(s.getTakeProfits()).containsExactly(72000.0);
        }

        @Test
        @DisplayName("單價格做空 — ETH，2560附近，做空")
        void singlePriceShort() {
            String msg = "ETH，2560附近，做空\n"
                    + "止损预计: 2650\n"
                    + "止盈预计: 2400";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getSymbol()).isEqualTo("ETHUSDT");
            assertThat(s.getSide()).isEqualTo(TradeSignal.Side.SHORT);
            assertThat(s.getEntryPriceLow()).isEqualTo(2560.0);
            assertThat(s.getEntryPriceHigh()).isEqualTo(2560.0);
        }

        @Test
        @DisplayName("缺少止損 — 解析失敗")
        void missingStopLoss() {
            String msg = "BTC，70800-72000附近，做空\n"
                    + "止盈预计: 68400";

            assertThat(parser.parse(msg)).isEmpty();
        }

        @Test
        @DisplayName("觸發入場訊號 — 空單（簡體触发入场）")
        void triggerShort() {
            String msg = "70800空單触发入场。";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getSide()).isEqualTo(TradeSignal.Side.SHORT);
            assertThat(s.getEntryPriceLow()).isEqualTo(70800.0);
        }

        @Test
        @DisplayName("觸發入場訊號 — 多單（簡體触发入场）")
        void triggerLong() {
            String msg = "95000多單触发入场。";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            TradeSignal s = result.get();
            assertThat(s.getSide()).isEqualTo(TradeSignal.Side.LONG);
            assertThat(s.getEntryPriceLow()).isEqualTo(95000.0);
        }

        @Test
        @DisplayName("觸發入場訊號 — 繁體觸也可以匹配")
        void triggerTraditionalPartial() {
            // regex [触觸]发 → 觸发 也匹配
            String msg = "70800空單觸发入场。";

            Optional<TradeSignal> result = parser.parse(msg);

            assertThat(result).isPresent();
            assertThat(result.get().getSide()).isEqualTo(TradeSignal.Side.SHORT);
        }
    }

    // ==================== 邊界情境 ====================

    @Nested
    @DisplayName("邊界情境")
    class EdgeCases {

        @Test
        @DisplayName("null 輸入")
        void nullInput() {
            assertThat(parser.parse(null)).isEmpty();
        }

        @Test
        @DisplayName("空字串")
        void emptyString() {
            assertThat(parser.parse("")).isEmpty();
        }

        @Test
        @DisplayName("空白字串")
        void blankString() {
            assertThat(parser.parse("   ")).isEmpty();
        }

        @Test
        @DisplayName("無法辨識的訊息")
        void unrecognizedMessage() {
            assertThat(parser.parse("今天天氣真好")).isEmpty();
        }

        @Test
        @DisplayName("INFO 訊號 — 不應被解析為交易訊號")
        void infoSignalNotParsed() {
            assertThat(parser.parse("🚀 訊號成交: BTCUSDT 已成交")).isEmpty();
            assertThat(parser.parse("🛑 止損出場: ETHUSDT")).isEmpty();
            assertThat(parser.parse("💰 盈虧更新")).isEmpty();
        }
    }
}
