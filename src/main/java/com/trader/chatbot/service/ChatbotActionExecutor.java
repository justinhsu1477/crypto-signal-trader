package com.trader.chatbot.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.user.dto.UpdateTradeSettingsRequest;
import com.trader.user.entity.UserTradeSettings;
import com.trader.user.service.UserTradeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Chatbot 動作執行器 — 安全白名單制
 *
 * 設計原則：
 * 1. 只允許修改「自己的」交易設定（userId 由系統注入，AI 無法指定）
 * 2. 所有修改經由 UserTradeSettingsService 的既有驗證層
 * 3. 每次操作都記錄 audit log
 * 4. 不允許：下單、改 API Key、改訂閱、存取他人資料
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatbotActionExecutor {

    private final UserTradeSettingsService userTradeSettingsService;
    private final Gson gson = new Gson();

    /**
     * 定義 Gemini Function Calling 的 tools schema
     * 用於 API 請求中的 tools 欄位
     */
    public JsonObject buildToolsSchema() {
        JsonObject tools = new JsonObject();
        var declarations = new com.google.gson.JsonArray();

        declarations.add(buildFunction(
                "get_trade_settings",
                "查詢用戶當前的交易設定（風險比例、槓桿、DCA 層數等）",
                Map.of()
        ));

        declarations.add(buildFunction(
                "update_risk_percent",
                "修改用戶的風險比例。值為小數，例如 0.3 代表 30%。範圍：0.01 ~ 1.0",
                Map.of("risk_percent", Map.of("type", "NUMBER", "description", "風險比例，例如 0.3 代表 30%"))
        ));

        declarations.add(buildFunction(
                "update_max_leverage",
                "修改用戶的最大槓桿倍數。範圍：1 ~ 125",
                Map.of("max_leverage", Map.of("type", "INTEGER", "description", "最大槓桿倍數，例如 20"))
        ));

        declarations.add(buildFunction(
                "update_max_dca_layers",
                "修改用戶的最大 DCA（加倉/補倉）層數。範圍：0 ~ 10",
                Map.of("max_dca_layers", Map.of("type", "INTEGER", "description", "最大 DCA 層數，例如 3"))
        ));

        declarations.add(buildFunction(
                "toggle_auto_sl_tp",
                "開啟或關閉自動止損（SL）和自動止盈（TP）",
                Map.of(
                        "auto_sl_enabled", Map.of("type", "BOOLEAN", "description", "是否啟用自動止損"),
                        "auto_tp_enabled", Map.of("type", "BOOLEAN", "description", "是否啟用自動止盈")
                )
        ));

        tools.add("function_declarations", declarations);
        return tools;
    }

    /**
     * 執行 Gemini 回傳的 function call
     *
     * @param userId       系統注入的用戶 ID（不可被 AI 覆蓋）
     * @param functionName 函式名稱
     * @param args         函式參數（JSON object）
     * @return 執行結果（格式化字串，回傳給 Gemini 做自然語言回覆）
     */
    public String executeFunction(String userId, String functionName, JsonObject args) {
        log.info("Chatbot 動作執行: userId={} function={} args={}", userId, functionName, args);

        try {
            return switch (functionName) {
                case "get_trade_settings" -> executeGetSettings(userId);
                case "update_risk_percent" -> executeUpdateRiskPercent(userId, args);
                case "update_max_leverage" -> executeUpdateMaxLeverage(userId, args);
                case "update_max_dca_layers" -> executeUpdateMaxDcaLayers(userId, args);
                case "toggle_auto_sl_tp" -> executeToggleAutoSlTp(userId, args);
                default -> {
                    log.warn("Chatbot 收到未知 function: {} userId={}", functionName, userId);
                    yield "不支援的操作。";
                }
            };
        } catch (IllegalArgumentException e) {
            log.warn("Chatbot 動作驗證失敗: userId={} function={} error={}", userId, functionName, e.getMessage());
            return "操作失敗：" + e.getMessage();
        } catch (Exception e) {
            log.error("Chatbot 動作執行異常: userId={} function={}", userId, functionName, e);
            return "操作失敗，請稍後再試或聯繫客服。";
        }
    }

    private String executeGetSettings(String userId) {
        UserTradeSettings settings = userTradeSettingsService.getOrCreateSettings(userId);
        StringBuilder sb = new StringBuilder();
        sb.append("當前交易設定：\n");
        sb.append(String.format("- 風險比例：%.0f%%\n", settings.getRiskPercent() != null ? settings.getRiskPercent() * 100 : 20));
        sb.append(String.format("- 最大槓桿：%dx\n", settings.getMaxLeverage() != null ? settings.getMaxLeverage() : 20));
        sb.append(String.format("- 最大 DCA 層數：%d\n", settings.getMaxDcaLayers() != null ? settings.getMaxDcaLayers() : 3));
        sb.append("- 自動止損：").append(settings.isAutoSlEnabled() ? "開啟" : "關閉").append("\n");
        sb.append("- 自動止盈：").append(settings.isAutoTpEnabled() ? "開啟" : "關閉").append("\n");
        if (settings.getMaxPositionSizeUsdt() != null) {
            sb.append(String.format("- 單筆最大名目：%.0f USDT\n", settings.getMaxPositionSizeUsdt()));
        }
        return sb.toString();
    }

    private String executeUpdateRiskPercent(String userId, JsonObject args) {
        double value = args.get("risk_percent").getAsDouble();
        UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
        request.setRiskPercent(value);
        userTradeSettingsService.updateSettings(userId, request);
        log.info("✅ Chatbot 修改成功: userId={} risk_percent={}", userId, value);
        return String.format("已成功將風險比例修改為 %.0f%%（%.2f）", value * 100, value);
    }

    private String executeUpdateMaxLeverage(String userId, JsonObject args) {
        int value = args.get("max_leverage").getAsInt();
        UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
        request.setMaxLeverage(value);
        userTradeSettingsService.updateSettings(userId, request);
        log.info("✅ Chatbot 修改成功: userId={} max_leverage={}", userId, value);
        return String.format("已成功將最大槓桿修改為 %dx", value);
    }

    private String executeUpdateMaxDcaLayers(String userId, JsonObject args) {
        int value = args.get("max_dca_layers").getAsInt();
        UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
        request.setMaxDcaLayers(value);
        userTradeSettingsService.updateSettings(userId, request);
        log.info("✅ Chatbot 修改成功: userId={} max_dca_layers={}", userId, value);
        return String.format("已成功將最大 DCA 層數修改為 %d", value);
    }

    private String executeToggleAutoSlTp(String userId, JsonObject args) {
        UpdateTradeSettingsRequest request = new UpdateTradeSettingsRequest();
        StringBuilder result = new StringBuilder("已成功修改：\n");

        if (args.has("auto_sl_enabled")) {
            boolean sl = args.get("auto_sl_enabled").getAsBoolean();
            request.setAutoSlEnabled(sl);
            result.append(String.format("- 自動止損：%s\n", sl ? "開啟" : "關閉"));
        }
        if (args.has("auto_tp_enabled")) {
            boolean tp = args.get("auto_tp_enabled").getAsBoolean();
            request.setAutoTpEnabled(tp);
            result.append(String.format("- 自動止盈：%s\n", tp ? "開啟" : "關閉"));
        }

        userTradeSettingsService.updateSettings(userId, request);
        log.info("✅ Chatbot 修改成功: userId={} auto_sl/tp", userId);
        return result.toString();
    }

    /**
     * 建構單一 function declaration
     */
    private JsonObject buildFunction(String name, String description, Map<String, Map<String, String>> params) {
        JsonObject func = new JsonObject();
        func.addProperty("name", name);
        func.addProperty("description", description);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "OBJECT");

        JsonObject properties = new JsonObject();
        List<String> requiredFields = new java.util.ArrayList<>();

        for (var entry : params.entrySet()) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", entry.getValue().get("type"));
            prop.addProperty("description", entry.getValue().get("description"));
            properties.add(entry.getKey(), prop);
            requiredFields.add(entry.getKey());
        }

        parameters.add("properties", properties);
        if (!requiredFields.isEmpty()) {
            var reqArray = new com.google.gson.JsonArray();
            requiredFields.forEach(reqArray::add);
            parameters.add("required", reqArray);
        }

        func.add("parameters", parameters);
        return func;
    }
}
