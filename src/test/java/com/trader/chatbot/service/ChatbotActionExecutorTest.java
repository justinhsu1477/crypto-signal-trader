package com.trader.chatbot.service;

import com.google.gson.JsonObject;
import com.trader.user.dto.UpdateTradeSettingsRequest;
import com.trader.user.entity.UserTradeSettings;
import com.trader.user.service.UserTradeSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks
    private ChatbotActionExecutor executor;

    private static final String USER_ID = "test-user";

    @Test
    void buildToolsSchema_包含九個函式定義() {
        JsonObject tools = executor.buildToolsSchema();
        var declarations = tools.getAsJsonArray("function_declarations");
        assertThat(declarations).hasSize(9);
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

        String result = executor.executeFunction(USER_ID, "get_trade_settings", new JsonObject());

        assertThat(result).contains("30%");
        assertThat(result).contains("20x");
        assertThat(result).contains("DCA 層數：3");
        assertThat(result).contains("開啟");   // SL
        assertThat(result).contains("關閉");   // TP
    }

    @Test
    void executeUpdateRiskPercent_呼叫Service並回傳成功() {
        JsonObject args = new JsonObject();
        args.addProperty("risk_percent", 0.25);

        String result = executor.executeFunction(USER_ID, "update_risk_percent", args);

        ArgumentCaptor<UpdateTradeSettingsRequest> captor = ArgumentCaptor.forClass(UpdateTradeSettingsRequest.class);
        verify(userTradeSettingsService).updateSettings(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getRiskPercent()).isEqualTo(0.25);
        assertThat(result).contains("25%");
    }

    @Test
    void executeUpdateMaxLeverage_呼叫Service並回傳成功() {
        JsonObject args = new JsonObject();
        args.addProperty("max_leverage", 50);

        String result = executor.executeFunction(USER_ID, "update_max_leverage", args);

        ArgumentCaptor<UpdateTradeSettingsRequest> captor = ArgumentCaptor.forClass(UpdateTradeSettingsRequest.class);
        verify(userTradeSettingsService).updateSettings(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getMaxLeverage()).isEqualTo(50);
        assertThat(result).contains("50x");
    }

    @Test
    void executeUpdateMaxDcaLayers_呼叫Service並回傳成功() {
        JsonObject args = new JsonObject();
        args.addProperty("max_dca_layers", 5);

        String result = executor.executeFunction(USER_ID, "update_max_dca_layers", args);

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

        String result = executor.executeFunction(USER_ID, "toggle_auto_sl_tp", args);

        ArgumentCaptor<UpdateTradeSettingsRequest> captor = ArgumentCaptor.forClass(UpdateTradeSettingsRequest.class);
        verify(userTradeSettingsService).updateSettings(eq(USER_ID), captor.capture());
        assertThat(captor.getValue().getAutoSlEnabled()).isFalse();
        assertThat(captor.getValue().getAutoTpEnabled()).isTrue();
        assertThat(result).contains("關閉");
        assertThat(result).contains("開啟");
    }

    @Test
    void executeUnknownFunction_回傳不支援() {
        String result = executor.executeFunction(USER_ID, "delete_account", new JsonObject());
        assertThat(result).contains("不支援");
        verifyNoInteractions(userTradeSettingsService);
    }

    @Test
    void executeFunction_Service驗證失敗時回傳錯誤訊息() {
        JsonObject args = new JsonObject();
        args.addProperty("risk_percent", 5.0); // 超出範圍

        doThrow(new IllegalArgumentException("riskPercent 必須在 0.01 ~ 1.0 之間"))
                .when(userTradeSettingsService).updateSettings(eq(USER_ID), any());

        String result = executor.executeFunction(USER_ID, "update_risk_percent", args);

        assertThat(result).contains("操作失敗");
        assertThat(result).contains("0.01 ~ 1.0");
    }

    @Test
    void executeFunction_未預期異常時回傳通用錯誤() {
        JsonObject args = new JsonObject();
        args.addProperty("risk_percent", 0.3);

        doThrow(new RuntimeException("DB connection error"))
                .when(userTradeSettingsService).updateSettings(eq(USER_ID), any());

        String result = executor.executeFunction(USER_ID, "update_risk_percent", args);

        assertThat(result).contains("操作失敗");
        assertThat(result).contains("稍後再試");
    }

    @Test
    void executeGetMarketData_委派給MarketDataService() {
        when(marketDataService.getMarketOverview()).thenReturn("BTC $67000");

        String result = executor.executeFunction(USER_ID, "get_market_data", new JsonObject());

        assertThat(result).isEqualTo("BTC $67000");
        verify(marketDataService).getMarketOverview();
    }

    @Test
    void executeGetMyPositions_委派給MarketDataService() {
        when(marketDataService.getUserPositions(USER_ID)).thenReturn("BTCUSDT LONG");

        String result = executor.executeFunction(USER_ID, "get_my_positions", new JsonObject());

        assertThat(result).isEqualTo("BTCUSDT LONG");
        verify(marketDataService).getUserPositions(USER_ID);
    }

    @Test
    void executeGetSignalReport_委派給MarketDataService() {
        when(marketDataService.getSignalReportSummary()).thenReturn("15 條訊號");

        String result = executor.executeFunction(USER_ID, "get_signal_report", new JsonObject());

        assertThat(result).isEqualTo("15 條訊號");
        verify(marketDataService).getSignalReportSummary();
    }

    @Test
    void executeGetAllUsersSummary_委派給MarketDataService() {
        when(marketDataService.getAllUsersSummary()).thenReturn("全用戶概覽");

        String result = executor.executeFunction(USER_ID, "get_all_users_summary", new JsonObject());

        assertThat(result).isEqualTo("全用戶概覽");
        verify(marketDataService).getAllUsersSummary();
    }

    @Test
    void userId由系統注入_AI無法覆蓋() {
        // 模擬 AI 嘗試在 args 中傳入其他 userId
        JsonObject args = new JsonObject();
        args.addProperty("risk_percent", 0.3);
        args.addProperty("user_id", "other-user"); // AI 嘗試注入

        executor.executeFunction(USER_ID, "update_risk_percent", args);

        // 確認使用的是系統注入的 userId，不是 args 中的
        verify(userTradeSettingsService).updateSettings(eq(USER_ID), any());
    }
}
