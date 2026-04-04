package com.trader.chatbot.service;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.trader.user.dto.UpdateTradeSettingsRequest;
import com.trader.user.entity.User;
import com.trader.user.entity.UserTradeSettings;
import com.trader.user.repository.UserRepository;
import com.trader.user.service.UserTradeSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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
    private final MarketDataService marketDataService;
    private final TradingNlqService tradingNlqService;
    private final UserRepository userRepository;
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
                "查詢用戶當前的交易設定（風險比例、槓桿、DCA 層數等）。Admin 可指定 target_user_name 查詢特定用戶。",
                Map.of("target_user_name", Map.of("type", "STRING", "description", "（Admin 專用，可選）目標用戶名稱，如「Edward Lin」。不填則查自己。")),
                List.of()
        ));

        declarations.add(buildFunction(
                "update_risk_percent",
                "修改用戶的風險比例。值為小數，例如 0.3 代表 30%。範圍：0.01 ~ 1.0。Admin 可指定 target_user_name 修改特定用戶。",
                Map.of(
                        "risk_percent", Map.of("type", "NUMBER", "description", "風險比例，例如 0.3 代表 30%"),
                        "target_user_name", Map.of("type", "STRING", "description", "（Admin 專用，可選）目標用戶名稱")
                ),
                List.of("risk_percent")
        ));

        declarations.add(buildFunction(
                "update_max_leverage",
                "修改用戶的最大槓桿倍數。範圍：1 ~ 125。Admin 可指定 target_user_name 修改特定用戶。",
                Map.of(
                        "max_leverage", Map.of("type", "INTEGER", "description", "最大槓桿倍數，例如 20"),
                        "target_user_name", Map.of("type", "STRING", "description", "（Admin 專用，可選）目標用戶名稱")
                ),
                List.of("max_leverage")
        ));

        declarations.add(buildFunction(
                "update_max_dca_layers",
                "修改用戶的最大 DCA（加倉/補倉）層數。範圍：0 ~ 10。Admin 可指定 target_user_name 修改特定用戶。",
                Map.of(
                        "max_dca_layers", Map.of("type", "INTEGER", "description", "最大 DCA 層數，例如 3"),
                        "target_user_name", Map.of("type", "STRING", "description", "（Admin 專用，可選）目標用戶名稱")
                ),
                List.of("max_dca_layers")
        ));

        declarations.add(buildFunction(
                "toggle_auto_sl_tp",
                "開啟或關閉自動止損（SL）和自動止盈（TP）。Admin 可指定 target_user_name 修改特定用戶。",
                Map.of(
                        "auto_sl_enabled", Map.of("type", "BOOLEAN", "description", "是否啟用自動止損"),
                        "auto_tp_enabled", Map.of("type", "BOOLEAN", "description", "是否啟用自動止盈"),
                        "target_user_name", Map.of("type", "STRING", "description", "（Admin 專用，可選）目標用戶名稱")
                ),
                List.of("auto_sl_enabled", "auto_tp_enabled")
        ));

        // === 市場數據查詢（唯讀） ===

        declarations.add(buildFunction(
                "get_market_data",
                "查詢 BTC 即時行情：價格、24h漲跌幅、成交量、資金費率（Funding Rate）、恐懼貪婪指數。用戶問到行情、市場、BTC 多少錢、適不適合做多時呼叫此函式。",
                Map.of()
        ));

        declarations.add(buildFunction(
                "get_my_positions",
                "查詢用戶目前的持倉狀況，包含入場價、止損、槓桿、未實現損益。用戶問到持倉、倉位、我的單時呼叫此函式。",
                Map.of()
        ));

        declarations.add(buildFunction(
                "get_signal_report",
                "查詢最近的訊號日報摘要：每日訊號數量、多空比例、AI 平均信心分數。用戶問到最近訊號表現、日報時呼叫此函式。",
                Map.of()
        ));

        // === Admin 專屬工具 ===

        declarations.add(buildFunction(
                "get_all_users_summary",
                "查詢全部用戶的持倉與交易概覽，包含每位用戶的持倉數、總損益、勝率。僅限 Admin 使用。管理員問到全部用戶、所有用戶、餘額、持倉概覽時呼叫。",
                Map.of()
        ));

        declarations.add(buildFunction(
                "get_source_list",
                "查詢所有訊號來源清單，包含名稱、交易模式（AUTO/SHADOW）、啟用狀態。管理員問到「有哪些頻道」「訊號來源」「來源清單」時呼叫。",
                Map.of()
        ));

        declarations.add(buildFunction(
                "get_source_performance",
                "查詢指定訊號來源的績效統計：交易數、勝率、總損益、平均損益、最大獲利/虧損、Profit Factor。當管理員提到任何頻道/來源名稱並搭配「表現」「績效」「勝率」等字眼時，直接將名稱作為 source_name 呼叫。",
                Map.of(
                        "source_name", Map.of("type", "STRING", "description", "來源名稱（支援模糊匹配，直接填入用戶提到的名稱即可，如「加密大漂亮」「飛揚」「陳哥」）"),
                        "period", Map.of("type", "STRING", "description", "時間區間：7d / 30d / 90d / all（預設 all）")
                )
        ));

        declarations.add(buildFunction(
                "get_source_recent_trades",
                "查詢指定訊號來源最近的交易紀錄明細（入場價、出場價、PnL、AI 信心分數）。當管理員提到任何頻道/來源名稱並搭配「最近交易」「最近的單」「紀錄」等字眼時，直接將名稱作為 source_name 呼叫，工具支援模糊匹配。",
                Map.of(
                        "source_name", Map.of("type", "STRING", "description", "來源名稱（支援模糊匹配，直接填入用戶提到的名稱即可，如「加密大漂亮」「飛揚」「陳哥」）"),
                        "count", Map.of("type", "INTEGER", "description", "筆數（預設 5，最多 10）")
                )
        ));

        declarations.add(buildFunction(
                "get_recent_broadcasts",
                "查詢最近的廣播跟單紀錄，包含訊號動作、成功/失敗/跳過人數。可按來源篩選。管理員問到「最近廣播」「跟單紀錄」「廣播歷史」時呼叫。",
                Map.of(
                        "source_name", Map.of("type", "STRING", "description", "來源名稱篩選（模糊匹配，空字串代表查全部）"),
                        "count", Map.of("type", "INTEGER", "description", "筆數（預設 5，最多 10）")
                )
        ));

        declarations.add(buildFunction(
                "update_source_mode",
                "修改訊號來源的交易模式。僅限 Admin 使用。管理員說「把 XX 改成影子模式」「XX 切換到 AUTO」時呼叫。直接將名稱作為 source_name 呼叫，工具支援模糊匹配。",
                Map.of(
                        "source_name", Map.of("type", "STRING", "description", "來源名稱（模糊匹配，如「陳哥」「飛揚」「加密大漂亮」）"),
                        "trade_mode", Map.of("type", "STRING", "description", "交易模式：AUTO（自動跟單）、SHADOW（影子模式）、MANUAL（手動）")
                )
        ));

        declarations.add(buildFunction(
                "get_trades_by_date",
                "查詢指定日期範圍的所有會員交易紀錄，包含每位用戶的交易明細和損益統計。管理員問到「昨天交易」「今天成交」「給我某天的資料」「本週交易」時呼叫。",
                Map.of("date", Map.of("type", "STRING", "description", "日期描述：yesterday（昨天）、today（今天）、7d（近7天）、30d（近30天）、或 YYYY-MM-DD 格式"))
        ));

        declarations.add(buildFunction(
                "query_trading_data",
                "使用自然語言查詢交易數據庫。可以問任何關於交易紀錄、損益統計、勝率分析、幣種表現、廣播紀錄等數據分析問題。" +
                "例如：「我這個月賺了多少」「哪個幣種勝率最高」「最近 10 筆交易明細」「做多和做空哪個表現好」。" +
                "當其他工具無法回答用戶的數據查詢需求時使用此工具。",
                Map.of("question", Map.of("type", "STRING", "description", "用戶的自然語言數據查詢問題")),
                List.of("question")
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
    public String executeFunction(String userId, boolean isAdmin, String functionName, JsonObject args) {
        log.info("Chatbot 動作執行: userId={} isAdmin={} function={} args={}", userId, isAdmin, functionName, args);

        try {
            // Admin 可指定 target_user_name 操作特定用戶的設定
            String effectiveUserId = resolveTargetUserId(userId, isAdmin, args);

            return switch (functionName) {
                case "get_trade_settings" -> executeGetSettings(effectiveUserId);
                case "update_risk_percent" -> executeUpdateRiskPercent(effectiveUserId, args);
                case "update_max_leverage" -> executeUpdateMaxLeverage(effectiveUserId, args);
                case "update_max_dca_layers" -> executeUpdateMaxDcaLayers(effectiveUserId, args);
                case "toggle_auto_sl_tp" -> executeToggleAutoSlTp(effectiveUserId, args);
                case "get_market_data" -> marketDataService.getMarketOverview();
                case "get_my_positions" -> marketDataService.getUserPositions(userId);
                case "get_signal_report" -> marketDataService.getSignalReportSummary();
                case "get_all_users_summary" -> {
                    if (!isAdmin) {
                        yield "此操作僅限管理員使用。";
                    }
                    yield marketDataService.getAllUsersSummary();
                }
                case "get_source_list" -> {
                    if (!isAdmin) {
                        yield "此操作僅限管理員使用。";
                    }
                    yield marketDataService.getSourceList();
                }
                case "get_source_performance" -> {
                    if (!isAdmin) {
                        yield "此操作僅限管理員使用。";
                    }
                    String sourceName = args.has("source_name") ? args.get("source_name").getAsString() : "";
                    String period = args.has("period") ? args.get("period").getAsString() : "all";
                    yield marketDataService.getSourcePerformance(sourceName, period);
                }
                case "get_source_recent_trades" -> {
                    if (!isAdmin) {
                        yield "此操作僅限管理員使用。";
                    }
                    String sourceName = args.has("source_name") ? args.get("source_name").getAsString() : "";
                    int count = args.has("count") ? args.get("count").getAsInt() : 5;
                    yield marketDataService.getSourceRecentTrades(sourceName, count);
                }
                case "get_recent_broadcasts" -> {
                    if (!isAdmin) {
                        yield "此操作僅限管理員使用。";
                    }
                    String sourceName = args.has("source_name") ? args.get("source_name").getAsString() : "";
                    int count = args.has("count") ? args.get("count").getAsInt() : 5;
                    yield marketDataService.getRecentBroadcasts(sourceName, count);
                }
                case "update_source_mode" -> {
                    if (!isAdmin) {
                        yield "此操作僅限管理員使用。";
                    }
                    String sourceName = args.has("source_name") ? args.get("source_name").getAsString() : "";
                    String tradeMode = args.has("trade_mode") ? args.get("trade_mode").getAsString() : "";
                    yield marketDataService.updateSourceTradeMode(sourceName, tradeMode);
                }
                case "get_trades_by_date" -> {
                    if (!isAdmin) {
                        yield "此操作僅限管理員使用。";
                    }
                    String date = args.has("date") ? args.get("date").getAsString() : "today";
                    yield marketDataService.getTradesByDate(date);
                }
                case "query_trading_data" -> {
                    String question = args.has("question") ? args.get("question").getAsString() : "";
                    yield tradingNlqService.executeNlq(effectiveUserId, isAdmin, question);
                }
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
     * Admin 指定 target_user_name 時，解析為目標用戶的 userId
     * 非 Admin 或未指定 target_user_name 時，回傳呼叫者自己的 userId
     */
    private String resolveTargetUserId(String callerUserId, boolean isAdmin, JsonObject args) {
        if (!isAdmin || args == null || !args.has("target_user_name")) {
            return callerUserId;
        }

        String targetName = args.get("target_user_name").getAsString().trim();
        if (targetName.isEmpty()) {
            return callerUserId;
        }

        List<User> matched = userRepository.findByNameContainingIgnoreCase(targetName);
        if (matched.isEmpty()) {
            throw new IllegalArgumentException("找不到用戶「" + targetName + "」，請確認名稱是否正確。");
        }
        if (matched.size() > 1) {
            String names = matched.stream().map(User::getName).collect(java.util.stream.Collectors.joining("、"));
            throw new IllegalArgumentException("找到多位符合的用戶：" + names + "，請提供更精確的名稱。");
        }

        User target = matched.get(0);
        log.info("Admin 指定目標用戶: targetName={} → userId={} name={}", targetName, target.getUserId(), target.getName());
        return target.getUserId();
    }

    /**
     * 建構單一 function declaration（所有參數皆 required）
     */
    private JsonObject buildFunction(String name, String description, Map<String, Map<String, String>> params) {
        return buildFunction(name, description, params, new java.util.ArrayList<>(params.keySet()));
    }

    /**
     * 建構單一 function declaration（指定 required 欄位）
     */
    private JsonObject buildFunction(String name, String description, Map<String, Map<String, String>> params, List<String> requiredFields) {
        JsonObject func = new JsonObject();
        func.addProperty("name", name);
        func.addProperty("description", description);

        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "OBJECT");

        JsonObject properties = new JsonObject();

        for (var entry : params.entrySet()) {
            JsonObject prop = new JsonObject();
            prop.addProperty("type", entry.getValue().get("type"));
            prop.addProperty("description", entry.getValue().get("description"));
            properties.add(entry.getKey(), prop);
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
