package com.trader.trading.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BinanceUserDataStreamUrlBuilder 單元測試
 *
 * 驗證 2026-04-23 遷移後的新 URL 格式：
 *   {base}?listenKey=<key>&events=<events slash-separated>
 */
@DisplayName("BinanceUserDataStreamUrlBuilder — Private Stream URL 組合")
class BinanceUserDataStreamUrlBuilderTest {

    private static final String PROD_BASE = "wss://fstream.binance.com/private/ws";
    private static final String TESTNET_BASE = "wss://stream.binancefuture.com/private/ws";

    @Nested
    @DisplayName("正常組合")
    class BuildTests {

        @Test
        @DisplayName("prod URL — 正確組合 listenKey + events query param")
        void buildsProdUrl() {
            String url = BinanceUserDataStreamUrlBuilder.build(PROD_BASE, "abc123");

            assertThat(url).isEqualTo(
                    "wss://fstream.binance.com/private/ws?listenKey=abc123&events=" +
                            BinanceUserDataStreamUrlBuilder.SUBSCRIBED_EVENTS);
        }

        @Test
        @DisplayName("testnet URL — 一致的格式")
        void buildsTestnetUrl() {
            String url = BinanceUserDataStreamUrlBuilder.build(TESTNET_BASE, "xyz789");

            assertThat(url).startsWith(TESTNET_BASE + "?listenKey=xyz789");
            assertThat(url).endsWith("&events=" + BinanceUserDataStreamUrlBuilder.SUBSCRIBED_EVENTS);
        }

        @Test
        @DisplayName("包含 events 清單的所有必要事件")
        void includesAllRequiredEvents() {
            String url = BinanceUserDataStreamUrlBuilder.build(PROD_BASE, "key");

            assertThat(url).contains("ORDER_TRADE_UPDATE");
            assertThat(url).contains("ACCOUNT_UPDATE");
            assertThat(url).contains("ALGO_UPDATE");
            assertThat(url).contains("MARGIN_CALL");
        }

        @Test
        @DisplayName("events 以斜線 `/` 分隔（Binance 規格）")
        void eventsSeparatedBySlash() {
            String events = BinanceUserDataStreamUrlBuilder.SUBSCRIBED_EVENTS;

            // 每個事件名稱之間應有斜線
            String[] tokens = events.split("/");
            assertThat(tokens).hasSizeGreaterThanOrEqualTo(4);
            // 避免誤用 `,` 或 `|` 等其他分隔符
            assertThat(events).doesNotContain(",");
            assertThat(events).doesNotContain("|");
        }
    }

    @Nested
    @DisplayName("輸入驗證")
    class ValidationTests {

        @Test
        @DisplayName("wsBaseUrl 為 null → IllegalArgumentException")
        void nullBaseUrlRejected() {
            assertThatThrownBy(() -> BinanceUserDataStreamUrlBuilder.build(null, "key"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("wsBaseUrl");
        }

        @Test
        @DisplayName("wsBaseUrl 為空白 → IllegalArgumentException")
        void blankBaseUrlRejected() {
            assertThatThrownBy(() -> BinanceUserDataStreamUrlBuilder.build("  ", "key"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("listenKey 為 null → IllegalArgumentException")
        void nullListenKeyRejected() {
            assertThatThrownBy(() -> BinanceUserDataStreamUrlBuilder.build(PROD_BASE, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("listenKey");
        }

        @Test
        @DisplayName("listenKey 為空字串 → IllegalArgumentException")
        void emptyListenKeyRejected() {
            assertThatThrownBy(() -> BinanceUserDataStreamUrlBuilder.build(PROD_BASE, ""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
