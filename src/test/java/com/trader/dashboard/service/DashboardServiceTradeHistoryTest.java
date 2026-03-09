package com.trader.dashboard.service;

import com.trader.dashboard.dto.TradeHistoryResponse;
import com.trader.shared.config.RiskConfig;
import com.trader.subscription.service.SubscriptionService;
import com.trader.trading.config.MultiUserConfig;
import com.trader.trading.entity.Trade;
import com.trader.trading.service.BinanceFuturesService;
import com.trader.trading.service.StartOfDayBalanceCache;
import com.trader.trading.service.TradeConfigResolver;
import com.trader.trading.service.TradeRecordService;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserApiKeyService;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * DashboardService.getTradeHistory() 測試
 *
 * 覆蓋場景：
 * 1. 手續費欄位映射（grossProfit, entryCommission, exitCommission, totalCommission, leverage）
 * 2. exitCommission 計算邏輯：commission - entryCommission
 * 3. AI 訊號欄位映射（aiConfidence, aiReasoning）
 * 4. null 值處理 — fee / AI 欄位全為 null
 * 5. exitCommission null 邊界 — commission 有值但 entryCommission 為 null
 */
class DashboardServiceTradeHistoryTest {

    private TradeRecordService tradeRecordService;
    private DashboardService dashboardService;

    private static final String USER_ID = "testUser";

    @BeforeEach
    void setUp() {
        tradeRecordService = mock(TradeRecordService.class);

        dashboardService = new DashboardService(
                tradeRecordService,
                mock(SubscriptionService.class),
                mock(BinanceFuturesService.class),
                mock(RiskConfig.class),
                mock(UserRepository.class),
                mock(TradeConfigResolver.class),
                new MultiUserConfig(),
                mock(UserApiKeyService.class),
                mock(com.trader.user.service.UserDiscordWebhookService.class),
                mock(StartOfDayBalanceCache.class),
                mock(com.trader.trading.repository.TradeRepository.class),
                mock(com.trader.referral.repository.UserExchangeReferralLinkRepository.class),
                mock(com.trader.subscription.repository.SubscriptionRepository.class));
    }

    /** 建立帶有完整手續費 + AI 欄位的 Trade */
    private Trade buildTradeWithFees(String tradeId, Double grossProfit,
                                     Double entryCommission, Double commission,
                                     Integer leverage,
                                     Integer aiConfidence, String aiReasoning) {
        Trade t = new Trade();
        t.setTradeId(tradeId);
        t.setSymbol("BTCUSDT");
        t.setSide("LONG");
        t.setEntryPrice(50000.0);
        t.setExitPrice(51000.0);
        t.setEntryQuantity(0.01);
        t.setNetProfit(8.0);
        t.setExitReason("TAKE_PROFIT");
        t.setStatus("CLOSED");
        t.setEntryTime(LocalDateTime.of(2024, 1, 1, 10, 0));
        t.setExitTime(LocalDateTime.of(2024, 1, 1, 12, 0));
        t.setGrossProfit(grossProfit);
        t.setEntryCommission(entryCommission);
        t.setCommission(commission);
        t.setLeverage(leverage);
        t.setAiConfidence(aiConfidence);
        t.setAiReasoning(aiReasoning);
        return t;
    }

    @Nested
    @DisplayName("手續費欄位映射")
    class FeeFieldMapping {

        @Test
        @DisplayName("完整手續費 — grossProfit, entryCommission, exitCommission, totalCommission, leverage 正確映射")
        void mapsAllFeeFields() {
            Trade trade = buildTradeWithFees("T1",
                    10.0,   // grossProfit
                    1.0,    // entryCommission
                    2.5,    // commission (total)
                    20,     // leverage
                    null, null);
            when(tradeRecordService.findByStatus("CLOSED", USER_ID)).thenReturn(List.of(trade));

            TradeHistoryResponse response = dashboardService.getTradeHistory(USER_ID, 0, 10);

            assertThat(response.getTrades()).hasSize(1);
            TradeHistoryResponse.TradeRecord record = response.getTrades().get(0);
            assertThat(record.getGrossProfit()).isEqualTo(10.0);
            assertThat(record.getEntryCommission()).isEqualTo(1.0);
            assertThat(record.getExitCommission()).isEqualTo(1.5);  // 2.5 - 1.0
            assertThat(record.getTotalCommission()).isEqualTo(2.5);
            assertThat(record.getLeverage()).isEqualTo(20);
        }

        @Test
        @DisplayName("exitCommission 計算 — round2 精度")
        void exitCommissionCalculation() {
            Trade trade = buildTradeWithFees("T2",
                    15.33,  // grossProfit
                    0.77,   // entryCommission
                    1.89,   // commission (total)
                    10, null, null);
            when(tradeRecordService.findByStatus("CLOSED", USER_ID)).thenReturn(List.of(trade));

            TradeHistoryResponse response = dashboardService.getTradeHistory(USER_ID, 0, 10);

            TradeHistoryResponse.TradeRecord record = response.getTrades().get(0);
            assertThat(record.getExitCommission()).isEqualTo(1.12);  // 1.89 - 0.77 = 1.12
        }
    }

    @Nested
    @DisplayName("AI 訊號欄位映射")
    class AiFieldMapping {

        @Test
        @DisplayName("AI 欄位有值 — aiConfidence + aiReasoning 正確映射")
        void mapsAiFields() {
            Trade trade = buildTradeWithFees("T3",
                    null, null, null, null,
                    85, "趨勢強勁，均線多排");
            when(tradeRecordService.findByStatus("CLOSED", USER_ID)).thenReturn(List.of(trade));

            TradeHistoryResponse response = dashboardService.getTradeHistory(USER_ID, 0, 10);

            TradeHistoryResponse.TradeRecord record = response.getTrades().get(0);
            assertThat(record.getAiConfidence()).isEqualTo(85);
            assertThat(record.getAiReasoning()).isEqualTo("趨勢強勁，均線多排");
        }

        @Test
        @DisplayName("AI 欄位為 null — aiConfidence + aiReasoning 返回 null")
        void aiFieldsNull() {
            Trade trade = buildTradeWithFees("T4",
                    null, null, null, null,
                    null, null);
            when(tradeRecordService.findByStatus("CLOSED", USER_ID)).thenReturn(List.of(trade));

            TradeHistoryResponse response = dashboardService.getTradeHistory(USER_ID, 0, 10);

            TradeHistoryResponse.TradeRecord record = response.getTrades().get(0);
            assertThat(record.getAiConfidence()).isNull();
            assertThat(record.getAiReasoning()).isNull();
        }
    }

    @Nested
    @DisplayName("Null 值處理")
    class NullHandling {

        @Test
        @DisplayName("手續費全為 null — 不崩潰，欄位皆為 null")
        void allFeeFieldsNull() {
            Trade trade = buildTradeWithFees("T5",
                    null, null, null, null,
                    null, null);
            when(tradeRecordService.findByStatus("CLOSED", USER_ID)).thenReturn(List.of(trade));

            TradeHistoryResponse response = dashboardService.getTradeHistory(USER_ID, 0, 10);

            TradeHistoryResponse.TradeRecord record = response.getTrades().get(0);
            assertThat(record.getGrossProfit()).isNull();
            assertThat(record.getEntryCommission()).isNull();
            assertThat(record.getExitCommission()).isNull();
            assertThat(record.getTotalCommission()).isNull();
            assertThat(record.getLeverage()).isNull();
        }

        @Test
        @DisplayName("commission 有值但 entryCommission 為 null — exitCommission 為 null")
        void commissionWithoutEntryCommission() {
            Trade trade = buildTradeWithFees("T6",
                    10.0,   // grossProfit
                    null,   // entryCommission = null
                    2.0,    // commission (total)
                    10, null, null);
            when(tradeRecordService.findByStatus("CLOSED", USER_ID)).thenReturn(List.of(trade));

            TradeHistoryResponse response = dashboardService.getTradeHistory(USER_ID, 0, 10);

            TradeHistoryResponse.TradeRecord record = response.getTrades().get(0);
            assertThat(record.getEntryCommission()).isNull();
            assertThat(record.getExitCommission()).isNull();  // 需兩者皆有值才計算
            assertThat(record.getTotalCommission()).isEqualTo(2.0);
        }

        @Test
        @DisplayName("entryCommission 有值但 commission 為 null — exitCommission 為 null")
        void entryCommissionWithoutCommission() {
            Trade trade = buildTradeWithFees("T7",
                    10.0,   // grossProfit
                    1.0,    // entryCommission
                    null,   // commission = null
                    null, null, null);
            when(tradeRecordService.findByStatus("CLOSED", USER_ID)).thenReturn(List.of(trade));

            TradeHistoryResponse response = dashboardService.getTradeHistory(USER_ID, 0, 10);

            TradeHistoryResponse.TradeRecord record = response.getTrades().get(0);
            assertThat(record.getEntryCommission()).isEqualTo(1.0);
            assertThat(record.getExitCommission()).isNull();  // commission 為 null，無法計算
            assertThat(record.getTotalCommission()).isNull();
        }
    }

    @Nested
    @DisplayName("分頁")
    class Pagination {

        @Test
        @DisplayName("多筆交易分頁 — 手續費 + AI 欄位在各頁都正確")
        void paginationPreservesFeeAndAiFields() {
            List<Trade> trades = List.of(
                    buildTradeWithFees("T10", 10.0, 1.0, 2.0, 20, 90, "強勢突破"),
                    buildTradeWithFees("T11", 5.0, 0.5, 1.0, 10, 60, "震盪走勢"),
                    buildTradeWithFees("T12", null, null, null, null, null, null)
            );
            when(tradeRecordService.findByStatus("CLOSED", USER_ID)).thenReturn(trades);

            // 第一頁（2 筆）
            TradeHistoryResponse page1 = dashboardService.getTradeHistory(USER_ID, 0, 2);
            assertThat(page1.getTrades()).hasSize(2);
            assertThat(page1.getTrades().get(0).getAiConfidence()).isEqualTo(90);
            assertThat(page1.getTrades().get(1).getLeverage()).isEqualTo(10);

            // 第二頁（1 筆）
            TradeHistoryResponse page2 = dashboardService.getTradeHistory(USER_ID, 1, 2);
            assertThat(page2.getTrades()).hasSize(1);
            assertThat(page2.getTrades().get(0).getGrossProfit()).isNull();
            assertThat(page2.getTrades().get(0).getAiConfidence()).isNull();

            // 分頁資訊
            assertThat(page1.getPagination().getTotalElements()).isEqualTo(3);
            assertThat(page1.getPagination().getTotalPages()).isEqualTo(2);
        }
    }
}
