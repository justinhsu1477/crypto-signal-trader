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

import com.trader.chatbot.service.IntentClassifier.Intent;

import java.util.*;;

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
     * Intent → 該意圖可用的 function 名稱（GenBI 式 intent-based routing）
     */
    private static final Map<Intent, Set<String>> INTENT_FUNCTIONS = new EnumMap<>(Intent.class);
    static {
        INTENT_FUNCTIONS.put(Intent.ACCOUNT_STATUS, Set.of("get_trade_settings", "get_my_positions", "query_trading_data", "get_user_balance"));
        INTENT_FUNCTIONS.put(Intent.TRADE_QUERY, Set.of("query_trading_data", "get_signal_report"));
        INTENT_FUNCTIONS.put(Intent.SIGNAL_EXPLAIN, Set.of("query_trading_data"));
        INTENT_FUNCTIONS.put(Intent.SETTING_CHANGE, Set.of("get_trade_settings", "update_risk_percent", "update_max_leverage", "update_max_dca_layers", "toggle_auto_sl_tp"));
        INTENT_FUNCTIONS.put(Intent.MARKET_DATA, Set.of("get_market_data", "get_my_positions"));
        INTENT_FUNCTIONS.put(Intent.OPERATION_GUIDE, Set.of());
        INTENT_FUNCTIONS.put(Intent.ANOMALY_REPORT, Set.of());
        INTENT_FUNCTIONS.put(Intent.GENERAL, Set.of("get_market_data", "query_trading_data"));
    }

    private static final Set<String> ADMIN_FUNCTIONS = Set.of(
            "get_all_users_summary", "get_source_list", "get_source_performance",
            "get_source_recent_trades", "get_recent_broadcasts", "update_source_mode",
            "get_trades_by_date", "query_trading_data",
            "get_all_user_balances", "get_today_signals_summary",
            "get_source_rolling_performance"
    );

    /**
     * 根據 intent + isAdmin 過濾 Gemini Function Calling 的 tools schema
     *
     * @return 過濾後的 tools JsonObject，若該 intent 不需要 tools 則回傳 null
     */
    public JsonObject buildToolsSchema(Intent intent, boolean isAdmin) {
        // 計算允許的 function 名稱
        Set<String> allowed = new HashSet<>(INTENT_FUNCTIONS.getOrDefault(intent, Set.of()));
        if (isAdmin) {
            allowed.addAll(ADMIN_FUNCTIONS);
        }

        if (allowed.isEmpty()) {
            return null;
        }

        // 建構所有 function declarations，只保留 allowed 的
        Map<String, JsonObject> allDeclarations = buildAllDeclarations();

        var declarations = new com.google.gson.JsonArray();
        for (String name : allowed) {
            JsonObject decl = allDeclarations.get(name);
            if (decl != null) {
                declarations.add(decl);
            }
        }

        if (declarations.isEmpty()) {
            return null;
        }

        JsonObject tools = new JsonObject();
        tools.add("function_declarations", declarations);
        return tools;
    }

    /**
     * 建構全部 function declarations（name → JsonObject）
     */
    private Map<String, JsonObject> buildAllDeclarations() {
        Map<String, JsonObject> map = new LinkedHashMap<>();

        map.put("get_trade_settings", buildFunction(
                "get_trade_settings",
                "查詢用戶當前的交易設定（風險比例、槓桿、DCA 層數等）。Admin 可指定 target_user_name 查詢特定用戶。",
                Map.of("target_user_name", Map.of("type", "STRING", "description", "（Admin 專用，可選）目標用戶名稱，如「Edward Lin」。不填則查自己。")),
                List.of()
        ));

        map.put("update_risk_percent", buildFunction(
                "update_risk_percent",
                "修改用戶的風險比例。值為小數，例如 0.3 代表 30%。範圍：0.01 ~ 1.0。Admin 可指定 target_user_name 修改特定用戶。",
                Map.of(
                        "risk_percent", Map.of("type", "NUMBER", "description", "風險比例，例如 0.3 代表 30%"),
                        "target_user_name", Map.of("type", "STRING", "description", "（Admin 專用，可選）目標用戶名稱")
                ),
                List.of("risk_percent")
        ));

        map.put("update_max_leverage", buildFunction(
                "update_max_leverage",
                "修改用戶的最大槓桿倍數。範圍：1 ~ 125。Admin 可指定 target_user_name 修改特定用戶。",
                Map.of(
                        "max_leverage", Map.of("type", "INTEGER", "description", "最大槓桿倍數，例如 20"),
                        "target_user_name", Map.of("type", "STRING", "description", "（Admin 專用，可選）目標用戶名稱")
                ),
                List.of("max_leverage")
        ));

        map.put("update_max_dca_layers", buildFunction(
                "update_max_dca_layers",
                "修改用戶的最大 DCA（加倉/補倉）層數。範圍：0 ~ 10。Admin 可指定 target_user_name 修改特定用戶。",
                Map.of(
                        "max_dca_layers", Map.of("type", "INTEGER", "description", "最大 DCA 層數，例如 3"),
                        "target_user_name", Map.of("type", "STRING", "description", "（Admin 專用，可選）目標用戶名稱")
                ),
                List.of("max_dca_layers")
        ));

        map.put("toggle_auto_sl_tp", buildFunction(
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

        map.put("get_market_data", buildFunction(
                "get_market_data",
                "查詢 BTC 即時行情：價格、24h漲跌幅、成交量、資金費率（Funding Rate）、恐懼貪婪指數。用戶問到行情、市場、BTC 多少錢、適不適合做多時呼叫此函式。",
                Map.of()
        ));

        map.put("get_my_positions", buildFunction(
                "get_my_positions",
                "查詢用戶目前的持倉狀況，包含入場價、止損、槓桿、未實現損益。用戶問到持倉、倉位、我的單時呼叫此函式。",
                Map.of()
        ));

        map.put("get_signal_report", buildFunction(
                "get_signal_report",
                "查詢最近的訊號日報摘要：每日訊號數量、多空比例、AI 平均信心分數。用戶問到最近訊號表現、日報時呼叫此函式。",
                Map.of()
        ));

        // === Admin 專屬工具 ===

        map.put("get_all_users_summary", buildFunction(
                "get_all_users_summary",
                "查詢全部用戶的持倉與交易概覽：每位用戶持倉數、總損益、勝率。" +
                "支援時間區間參數（period）：7d=近7天 / 30d=近30天 / 90d=近90天 / all=全時間（預設）。" +
                "僅限 Admin 使用。管理員問到「本週用戶獲利」「最近30天表現」「所有用戶概覽」時呼叫。" +
                "注意：此工具回 DB 累積 PnL，不是即時餘額；要即時餘額用 get_all_user_balances。",
                Map.of("period", Map.of("type", "STRING", "description", "時間區間：7d / 30d / 90d / all（預設 all）"))
        ));

        // === 新增 Admin 工具：即時餘額 + 訊號狀況 ===

        map.put("get_user_balance", buildFunction(
                "get_user_balance",
                "查詢用戶當前的 Binance USDT 即時餘額（直接呼叫 Binance API，非 DB 快照）。" +
                "用戶問「我的餘額」「我有多少錢」「帳戶餘額多少」時呼叫。" +
                "Admin 可指定 target_user_id 查詢特定用戶。",
                Map.of("target_user_id", Map.of("type", "STRING", "description", "（Admin 專用，可選）目標用戶 ID。不填則查自己。")),
                List.of()
        ));

        map.put("get_all_user_balances", buildFunction(
                "get_all_user_balances",
                "查詢全部用戶的 Binance USDT 即時餘額（直接呼叫 Binance API）。" +
                "僅限 Admin 使用。管理員問「所有用戶餘額」「總共多少錢」「全部用戶帳戶」時呼叫。",
                Map.of()
        ));

        map.put("get_today_signals_summary", buildFunction(
                "get_today_signals_summary",
                "查詢今日訊號狀況：訊號總數、多空分布、AI 平均信心、廣播跟單成功/失敗/跳過數。" +
                "僅限 Admin 使用。管理員問「今天訊號狀況」「今天廣播」「今天跟單情形」時呼叫。",
                Map.of()
        ));

        map.put("get_source_list", buildFunction(
                "get_source_list",
                "查詢所有訊號來源清單，包含名稱、交易模式（AUTO/SHADOW）、啟用狀態。管理員問到「有哪些頻道」「訊號來源」「來源清單」時呼叫。",
                Map.of()
        ));

        map.put("get_source_performance", buildFunction(
                "get_source_performance",
                "查詢指定訊號來源的績效統計：交易數、勝率、總損益、平均損益、最大獲利/虧損、Profit Factor。當管理員提到任何頻道/來源名稱並搭配「表現」「績效」「勝率」等字眼時，直接將名稱作為 source_name 呼叫。",
                Map.of(
                        "source_name", Map.of("type", "STRING", "description", "來源名稱（支援模糊匹配，直接填入用戶提到的名稱即可，如「加密大漂亮」「飛揚」「陳哥」）"),
                        "period", Map.of("type", "STRING", "description", "時間區間：7d / 30d / 90d / all（預設 all）")
                )
        ));

        map.put("get_source_rolling_performance", buildFunction(
                "get_source_rolling_performance",
                "查詢指定訊號來源的 Rolling（滑動視窗）績效，並排 7 天 / 30 天 / 90 天。" +
                "避開月份切片陷阱（單月看起來很棒但實際正在衰退）。" +
                "當管理員問到「最近表現」「最近一週」「最近一個月」「rolling」「衰退」等字眼時呼叫，直接將來源名稱作為 source_name。" +
                "僅限 Admin 使用。",
                Map.of("source_name", Map.of("type", "STRING",
                        "description", "來源名稱（支援模糊匹配，例如「陳哥」「飛揚」）")),
                List.of("source_name")
        ));

        map.put("get_source_recent_trades", buildFunction(
                "get_source_recent_trades",
                "查詢指定訊號來源最近的交易紀錄明細（入場價、出場價、PnL、AI 信心分數）。當管理員提到任何頻道/來源名稱並搭配「最近交易」「最近的單」「紀錄」等字眼時，直接將名稱作為 source_name 呼叫，工具支援模糊匹配。",
                Map.of(
                        "source_name", Map.of("type", "STRING", "description", "來源名稱（支援模糊匹配，直接填入用戶提到的名稱即可，如「加密大漂亮」「飛揚」「陳哥」）"),
                        "count", Map.of("type", "INTEGER", "description", "筆數（預設 5，最多 10）")
                )
        ));

        map.put("get_recent_broadcasts", buildFunction(
                "get_recent_broadcasts",
                "查詢最近的廣播跟單紀錄，包含訊號動作、成功/失敗/跳過人數。可按來源篩選。管理員問到「最近廣播」「跟單紀錄」「廣播歷史」時呼叫。",
                Map.of(
                        "source_name", Map.of("type", "STRING", "description", "來源名稱篩選（模糊匹配，空字串代表查全部）"),
                        "count", Map.of("type", "INTEGER", "description", "筆數（預設 5，最多 10）")
                )
        ));

        map.put("update_source_mode", buildFunction(
                "update_source_mode",
                "修改訊號來源的交易模式。僅限 Admin 使用。管理員說「把 XX 改成影子模式」「XX 切換到 AUTO」時呼叫。直接將名稱作為 source_name 呼叫，工具支援模糊匹配。",
                Map.of(
                        "source_name", Map.of("type", "STRING", "description", "來源名稱（模糊匹配，如「陳哥」「飛揚」「加密大漂亮」）"),
                        "trade_mode", Map.of("type", "STRING", "description", "交易模式：AUTO（自動跟單）、SHADOW（影子模式）、MANUAL（手動）")
                )
        ));

        map.put("get_trades_by_date", buildFunction(
                "get_trades_by_date",
                "查詢指定日期範圍的所有會員交易紀錄，包含每位用戶的交易明細和損益統計。管理員問到「昨天交易」「今天成交」「給我某天的資料」「本週交易」時呼叫。",
                Map.of("date", Map.of("type", "STRING", "description", "日期描述：yesterday（昨天）、today（今天）、7d（近7天）、30d（近30天）、或 YYYY-MM-DD 格式"))
        ));

        map.put("query_trading_data", buildFunction(
                "query_trading_data",
                "使用自然語言查詢交易數據庫。可以問任何關於交易紀錄、損益統計、勝率分析、幣種表現、廣播紀錄等數據分析問題。" +
                "例如：「我這個月賺了多少」「哪個幣種勝率最高」「最近 10 筆交易明細」「做多和做空哪個表現好」。" +
                "當其他工具無法回答用戶的數據查詢需求時使用此工具。",
                Map.of("question", Map.of("type", "STRING", "description", "用戶的自然語言數據查詢問題")),
                List.of("question")
        ));

        return map;
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
                    String period = args.has("period") ? args.get("period").getAsString() : "all";
                    yield marketDataService.getAllUsersSummary(period);
                }
                case "get_user_balance" -> {
                    String targetUserId = args.has("target_user_id") ? args.get("target_user_id").getAsString() : userId;
                    if (!isAdmin && !targetUserId.equals(userId)) {
                        // 非 admin 不可指定別人
                        yield "查詢他人餘額僅限管理員。";
                    }
                    yield marketDataService.getUserBalance(targetUserId);
                }
                case "get_all_user_balances" -> {
                    if (!isAdmin) {
                        yield "此操作僅限管理員使用。";
                    }
                    yield marketDataService.getAllUserBalances();
                }
                case "get_today_signals_summary" -> {
                    if (!isAdmin) {
                        yield "此操作僅限管理員使用。";
                    }
                    yield marketDataService.getTodaySignalSummaryWithOutcomes();
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
                case "get_source_rolling_performance" -> {
                    if (!isAdmin) {
                        yield "此操作僅限管理員使用。";
                    }
                    String sourceName = args.has("source_name") ? args.get("source_name").getAsString() : "";
                    yield marketDataService.getSourceRollingPerformance(sourceName);
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
