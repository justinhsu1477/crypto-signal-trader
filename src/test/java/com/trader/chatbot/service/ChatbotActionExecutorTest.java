package com.trader.chatbot.service;

import com.google.gson.JsonObject;
import com.trader.user.dto.UpdateTradeSettingsRequest;
import com.trader.user.entity.User;
import com.trader.user.entity.UserTradeSettings;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserTradeSettingsService;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatbotActionExecutorTest {

    @Mock
    private UserTradeSettingsService userTradeSettingsService;

    @Mock
    private MarketDataService marketDataService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ChatbotActionExecutor executor;

    private static final String USER_ID = "test-user";
    private static final String ADMIN_ID = "admin-user";

    @Test
    void buildToolsSchema_包含十六個函式定義() {
        JsonObject tools = executor.buildToolsSchema();
        var declarations = tools.getAsJsonArray("function_declarations");
        assertThat(declarations).hasSize(16);
    }

    @Test
    void executeGetSettings_回傳用戶設定() {
        UserTradeSettings settings = UserTradeSettings.builder()
                .userId(USER_ID)
                .riskPercent(0.3)
                .maxLeverage(20)
                .maxDcaLayers(3)
                .autoSlEnabled(true)
                .autoTpEnabled(false)
                .maxPositionSizeUsdt(50000.0)
                .build();
        when(userTradeSettingsService.getOrCreateSettings(USER_ID)).thenReturn(settings);

        String result = executor.executeFunction(USER_ID, false, "get_trade_settings", new JsonObject());

        assertThat(result).contains("30%");
        assertThat(result).contains("20x");
        assertThat(result).contains("DCA 層數：3");
        assertThat(result).contains("開啟");
        assertThat(result).contains("關閉");
    }

    @Test
    void executeUpdateRiskPercent_呼叫Service並回傳成功() {
        JsonObject args = new JsonObject();
        args.addProperty("risk_percent", 0.25);

        String result = executor.executeFunction(USER_ID, false, "update_risk_percent", args);

        ArgumentCaptor<UpdateTradeSettingsRequest> captor = ArgumentCaptor.forClass(UpdateTradeSettingsRequest.class);
        verify(userTradeSettingsService).updateSettings(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getRiskPercent()).isEqualTo(0.25);
        assertThat(result).contains("25%");
    }

    @Test
    void executeUpdateMaxLeverage_呼叫Service並回傳成功() {
        JsonObject args = new JsonObject();
        args.addProperty("max_leverage", 50);

        String result = executor.executeFunction(USER_ID, false, "update_max_leverage", args);

        ArgumentCaptor<UpdateTradeSettingsRequest> captor = ArgumentCaptor.forClass(UpdateTradeSettingsRequest.class);
        verify(userTradeSettingsService).updateSettings(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getMaxLeverage()).isEqualTo(50);
        assertThat(result).contains("50x");
    }

    @Test
    void executeUpdateMaxDcaLayers_呼叫Service並回傳成功() {
        JsonObject args = new JsonObject();
        args.addProperty("max_dca_layers", 5);

        String result = executor.executeFunction(USER_ID, false, "update_max_dca_layers", args);

        ArgumentCaptor<UpdateTradeSettingsRequest> captor = ArgumentCaptor.forClass(UpdateTradeSettingsRequest.class);
        verify(userTradeSettingsService).updateSettings(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getMaxDcaLayers()).isEqualTo(5);
        assertThat(result).contains("5");
    }

    @Test
    void executeToggleAutoSlTp_修改止損止盈開關() {
        JsonObject args = new JsonObject();
        args.addProperty("auto_sl_enabled", false);
        args.addProperty("auto_tp_enabled", true);

        String result = executor.executeFunction(USER_ID, false, "toggle_auto_sl_tp", args);

        ArgumentCaptor<UpdateTradeSettingsRequest> captor = ArgumentCaptor.forClass(UpdateTradeSettingsRequest.class);
        verify(userTradeSettingsService).updateSettings(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getAutoSlEnabled()).isFalse();
        assertThat(captor.getValue().getAutoTpEnabled()).isTrue();
        assertThat(result).contains("關閉");
        assertThat(result).contains("開啟");
    }

    @Test
    void executeUnknownFunction_回傳不支援() {
        String result = executor.executeFunction(USER_ID, false, "delete_account", new JsonObject());
        assertThat(result).contains("不支援");
        verifyNoInteractions(userTradeSettingsService);
    }

    @Test
    void executeFunction_Service驗證失敗時回傳錯誤訊息() {
        JsonObject args = new JsonObject();
        args.addProperty("risk_percent", 5.0);

        doThrow(new IllegalArgumentException("riskPercent 必須在 0.01 ~ 1.0 之間"))
                .when(userTradeSettingsService).updateSettings(eq(USER_ID), any());

        String result = executor.executeFunction(USER_ID, false, "update_risk_percent", args);

        assertThat(result).contains("操作失敗");
        assertThat(result).contains("0.01 ~ 1.0");
    }

    @Test
    void executeFunction_未預期異常時回傳通用錯誤() {
        JsonObject args = new JsonObject();
        args.addProperty("risk_percent", 0.3);

        doThrow(new RuntimeException("DB connection error"))
                .when(userTradeSettingsService).updateSettings(eq(USER_ID), any());

        String result = executor.executeFunction(USER_ID, false, "update_risk_percent", args);

        assertThat(result).contains("操作失敗");
        assertThat(result).contains("稍後再試");
    }

    @Test
    void executeGetMarketData_委派給MarketDataService() {
        when(marketDataService.getMarketOverview()).thenReturn("BTC $67000");

        String result = executor.executeFunction(USER_ID, false, "get_market_data", new JsonObject());

        assertThat(result).isEqualTo("BTC $67000");
        verify(marketDataService).getMarketOverview();
    }

    @Test
    void executeGetMyPositions_委派給MarketDataService() {
        when(marketDataService.getUserPositions(USER_ID)).thenReturn("BTCUSDT LONG");

        String result = executor.executeFunction(USER_ID, false, "get_my_positions", new JsonObject());

        assertThat(result).isEqualTo("BTCUSDT LONG");
        verify(marketDataService).getUserPositions(USER_ID);
    }

    @Test
    void executeGetSignalReport_委派給MarketDataService() {
        when(marketDataService.getSignalReportSummary()).thenReturn("15 條訊號");

        String result = executor.executeFunction(USER_ID, false, "get_signal_report", new JsonObject());

        assertThat(result).isEqualTo("15 條訊號");
        verify(marketDataService).getSignalReportSummary();
    }

    @Test
    void executeGetAllUsersSummary_委派給MarketDataService() {
        when(marketDataService.getAllUsersSummary()).thenReturn("全用戶概覽");

        String result = executor.executeFunction(USER_ID, false, "get_all_users_summary", new JsonObject());

        assertThat(result).isEqualTo("全用戶概覽");
        verify(marketDataService).getAllUsersSummary();
    }

    @Test
    void executeGetSourceList_委派給MarketDataService() {
        when(marketDataService.getSourceList()).thenReturn("來源清單");

        String result = executor.executeFunction(USER_ID, false, "get_source_list", new JsonObject());

        assertThat(result).isEqualTo("來源清單");
        verify(marketDataService).getSourceList();
    }

    @Test
    void executeGetSourcePerformance_傳遞參數並委派() {
        JsonObject args = new JsonObject();
        args.addProperty("source_name", "比特幣飛揚");
        args.addProperty("period", "30d");
        when(marketDataService.getSourcePerformance("比特幣飛揚", "30d")).thenReturn("績效數據");

        String result = executor.executeFunction(USER_ID, false, "get_source_performance", args);

        assertThat(result).isEqualTo("績效數據");
        verify(marketDataService).getSourcePerformance("比特幣飛揚", "30d");
    }

    @Test
    void executeGetSourceRecentTrades_傳遞參數並委派() {
        JsonObject args = new JsonObject();
        args.addProperty("source_name", "陳哥");
        args.addProperty("count", 3);
        when(marketDataService.getSourceRecentTrades("陳哥", 3)).thenReturn("交易明細");

        String result = executor.executeFunction(USER_ID, false, "get_source_recent_trades", args);

        assertThat(result).isEqualTo("交易明細");
        verify(marketDataService).getSourceRecentTrades("陳哥", 3);
    }

    @Test
    void executeGetRecentBroadcasts_傳遞參數並委派() {
        JsonObject args = new JsonObject();
        args.addProperty("source_name", "飛揚");
        args.addProperty("count", 5);
        when(marketDataService.getRecentBroadcasts("飛揚", 5)).thenReturn("廣播紀錄");

        String result = executor.executeFunction(USER_ID, false, "get_recent_broadcasts", args);

        assertThat(result).isEqualTo("廣播紀錄");
        verify(marketDataService).getRecentBroadcasts("飛揚", 5);
    }

    @Test
    void executeGetRecentBroadcasts_無sourceName時傳空字串() {
        JsonObject args = new JsonObject();
        args.addProperty("source_name", "");
        args.addProperty("count", 5);
        when(marketDataService.getRecentBroadcasts("", 5)).thenReturn("全部廣播");

        String result = executor.executeFunction(USER_ID, false, "get_recent_broadcasts", args);

        assertThat(result).isEqualTo("全部廣播");
    }

    @Test
    void userId由系統注入_AI無法覆蓋() {
        JsonObject args = new JsonObject();
        args.addProperty("risk_percent", 0.3);
        args.addProperty("user_id", "other-user");

        executor.executeFunction(USER_ID, false, "update_risk_percent", args);

        verify(userTradeSettingsService).updateSettings(eq(USER_ID), any());
    }

    @Test
    void buildToolsSchema_包含所有必要的函式名稱() {
        JsonObject tools = executor.buildToolsSchema();
        String json = tools.toString();

        assertThat(json).contains("get_trade_settings");
        assertThat(json).contains("update_risk_percent");
        assertThat(json).contains("update_max_leverage");
        assertThat(json).contains("update_max_dca_layers");
        assertThat(json).contains("toggle_auto_sl_tp");
        assertThat(json).contains("get_market_data");
        assertThat(json).contains("get_my_positions");
        assertThat(json).contains("get_signal_report");
        assertThat(json).contains("get_all_users_summary");
        assertThat(json).contains("get_source_list");
        assertThat(json).contains("get_source_performance");
        assertThat(json).contains("get_source_recent_trades");
        assertThat(json).contains("get_recent_broadcasts");
        assertThat(json).contains("update_source_mode");
        assertThat(json).contains("get_trades_by_date");
    }

    @Test
    void buildToolsSchema_修改類工具包含target_user_name參數() {
        JsonObject tools = executor.buildToolsSchema();
        String json = tools.toString();

        assertThat(json).contains("target_user_name");
        assertThat(json).contains("Admin 專用");
    }

    @Test
    void executeFunction_空args不拋異常() {
        UserTradeSettings settings = UserTradeSettings.builder()
                .userId(USER_ID).riskPercent(0.2).maxLeverage(10)
                .maxDcaLayers(2).autoSlEnabled(true).autoTpEnabled(true).build();
        when(userTradeSettingsService.getOrCreateSettings(USER_ID)).thenReturn(settings);

        String result = executor.executeFunction(USER_ID, false, "get_trade_settings", null);

        assertThat(result).contains("20%");
    }

    @Test
    void executeNonWhitelistedFunction_回傳不支援() {
        String result = executor.executeFunction(USER_ID, false, "get_fear_greed", new JsonObject());

        assertThat(result).contains("不支援");
        verifyNoInteractions(userTradeSettingsService);
        verifyNoInteractions(marketDataService);
    }

    // === Admin 指定用戶測試 ===

    @Nested
    class AdminTargetUserTests {

        @Test
        void admin指定用戶名稱_修改該用戶風險比例() {
            User targetUser = User.builder().userId("edward-id").name("Edward Lin").build();
            when(userRepository.findByNameContainingIgnoreCase("Edward Lin")).thenReturn(List.of(targetUser));

            JsonObject args = new JsonObject();
            args.addProperty("risk_percent", 0.4);
            args.addProperty("target_user_name", "Edward Lin");

            String result = executor.executeFunction(ADMIN_ID, true, "update_risk_percent", args);

            verify(userTradeSettingsService).updateSettings(eq("edward-id"), any());
            assertThat(result).contains("40%");
        }

        @Test
        void admin指定用戶名稱_查詢該用戶設定() {
            User targetUser = User.builder().userId("edward-id").name("Edward Lin").build();
            when(userRepository.findByNameContainingIgnoreCase("Edward")).thenReturn(List.of(targetUser));
            UserTradeSettings settings = UserTradeSettings.builder()
                    .userId("edward-id").riskPercent(0.3).maxLeverage(20)
                    .maxDcaLayers(3).autoSlEnabled(true).autoTpEnabled(false).build();
            when(userTradeSettingsService.getOrCreateSettings("edward-id")).thenReturn(settings);

            JsonObject args = new JsonObject();
            args.addProperty("target_user_name", "Edward");

            String result = executor.executeFunction(ADMIN_ID, true, "get_trade_settings", args);

            verify(userTradeSettingsService).getOrCreateSettings("edward-id");
            assertThat(result).contains("30%");
        }

        @Test
        void admin未指定用戶名稱_修改自己的設定() {
            JsonObject args = new JsonObject();
            args.addProperty("risk_percent", 0.5);

            executor.executeFunction(ADMIN_ID, true, "update_risk_percent", args);

            verify(userTradeSettingsService).updateSettings(eq(ADMIN_ID), any());
            verifyNoInteractions(userRepository);
        }

        @Test
        void admin指定不存在的用戶_回傳錯誤() {
            when(userRepository.findByNameContainingIgnoreCase("不存在")).thenReturn(List.of());

            JsonObject args = new JsonObject();
            args.addProperty("risk_percent", 0.3);
            args.addProperty("target_user_name", "不存在");

            String result = executor.executeFunction(ADMIN_ID, true, "update_risk_percent", args);

            assertThat(result).contains("操作失敗");
            assertThat(result).contains("找不到用戶");
            verifyNoInteractions(userTradeSettingsService);
        }

        @Test
        void admin指定模糊名稱_匹配到多人_回傳錯誤() {
            User user1 = User.builder().userId("id1").name("Edward Lin").build();
            User user2 = User.builder().userId("id2").name("Edward Chen").build();
            when(userRepository.findByNameContainingIgnoreCase("Edward")).thenReturn(List.of(user1, user2));

            JsonObject args = new JsonObject();
            args.addProperty("risk_percent", 0.3);
            args.addProperty("target_user_name", "Edward");

            String result = executor.executeFunction(ADMIN_ID, true, "update_risk_percent", args);

            assertThat(result).contains("操作失敗");
            assertThat(result).contains("多位符合");
            assertThat(result).contains("Edward Lin");
            assertThat(result).contains("Edward Chen");
            verifyNoInteractions(userTradeSettingsService);
        }

        @Test
        void admin修改來源模式_委派給MarketDataService() {
            JsonObject args = new JsonObject();
            args.addProperty("source_name", "陳哥");
            args.addProperty("trade_mode", "SHADOW");
            when(marketDataService.updateSourceTradeMode("陳哥", "SHADOW")).thenReturn("已成功修改");

            String result = executor.executeFunction(ADMIN_ID, true, "update_source_mode", args);

            assertThat(result).isEqualTo("已成功修改");
            verify(marketDataService).updateSourceTradeMode("陳哥", "SHADOW");
        }

        @Test
        void 非Admin嘗試修改來源模式_拒絕() {
            JsonObject args = new JsonObject();
            args.addProperty("source_name", "陳哥");
            args.addProperty("trade_mode", "SHADOW");

            String result = executor.executeFunction(USER_ID, false, "update_source_mode", args);

            assertThat(result).contains("僅限管理員");
            verifyNoInteractions(marketDataService);
        }

        @Test
        void 非Admin指定target_user_name_忽略直接用自己的userId() {
            JsonObject args = new JsonObject();
            args.addProperty("risk_percent", 0.3);
            args.addProperty("target_user_name", "Edward Lin");

            executor.executeFunction(USER_ID, false, "update_risk_percent", args);

            // 非 Admin 即使有 target_user_name 也只能改自己
            verify(userTradeSettingsService).updateSettings(eq(USER_ID), any());
            verifyNoInteractions(userRepository);
        }
    }
}
