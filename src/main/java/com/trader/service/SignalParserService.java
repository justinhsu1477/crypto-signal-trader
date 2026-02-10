package com.trader.service;

import com.trader.model.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 訊號解析器 - 解析陳哥的交易訊號格式
 *
 * 範例訊息:
 * ⚠️⚠️⚠️⚠️⚠️⚠️
 * 陈哥合约交易策略【限价】
 * BTC，70800-72000附近，做空
 * 止损预计: 72800
 * 止盈预计: 68400/66700
 * ⚠️⚠️⚠️⚠️⚠️⚠️
 *
 * 也支援觸發訊息:
 * 70800空單觸發入場。
 */
@Slf4j
@Service
public class SignalParserService {

    // ==================== 陳哥格式 ====================

    // 匹配策略訊號 (限價單)
    // 格式: BTC，70800-72000附近，做空/做多
    private static final Pattern SIGNAL_PATTERN = Pattern.compile(
            "([A-Z]+)[，,]\\s*(\\d+(?:\\.\\d+)?)\\s*[-–~]\\s*(\\d+(?:\\.\\d+)?)\\s*附近[，,]\\s*(做空|做多)"
    );

    // 匹配止損
    private static final Pattern STOP_LOSS_PATTERN = Pattern.compile(
            "止[损損][预預]?[计計]?[:：]\\s*(\\d+(?:\\.\\d+)?)"
    );

    // 匹配止盈 (可能有多個, 用 / 分隔)
    private static final Pattern TAKE_PROFIT_PATTERN = Pattern.compile(
            "止[盈]?[预預]?[计計]?[:：]\\s*([\\d.]+(?:/[\\d.]+)*)"
    );

    // 匹配觸發入場訊息
    // 格式: 70800空單觸發入場 or 70800多單觸發入場
    private static final Pattern TRIGGER_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)(空單|多單)[触觸]发發?入[场場]"
    );

    // ==================== Discord 頻道格式 ====================

    // 📢 交易訊號發布: ETHUSDT
    private static final Pattern DISCORD_ENTRY_SYMBOL = Pattern.compile(
            "交易訊號發布[:：]\\s*([A-Z]+)"
    );

    // 做多 LONG 🟢 or 做空 SHORT 🔴
    private static final Pattern DISCORD_SIDE = Pattern.compile(
            "(做多\\s*LONG|做空\\s*SHORT)"
    );

    // 入場價格 (Entry)\n2650
    private static final Pattern DISCORD_ENTRY_PRICE = Pattern.compile(
            "入場價格\\s*\\(Entry\\)\\s*\\n\\s*(\\d+\\.?\\d*)"
    );

    // 止盈目標 (TP)\n2790 or 止盈目標 (TP)\n未設定
    private static final Pattern DISCORD_TP = Pattern.compile(
            "止盈目標\\s*\\(TP\\)\\s*\\n\\s*(\\d+\\.?\\d*|未設定)"
    );

    // 止損價格 (SL)\n2580 or 止損價格 (SL)\n未設定
    private static final Pattern DISCORD_SL = Pattern.compile(
            "止損價格\\s*\\(SL\\)\\s*\\n\\s*(\\d+\\.?\\d*|未設定)"
    );

    // ⚠️ 掛單取消: ETHUSDT
    private static final Pattern DISCORD_CANCEL_SYMBOL = Pattern.compile(
            "掛單取消[:：]\\s*([A-Z]+)"
    );

    // 掛單價格 (Price)\n2850
    private static final Pattern DISCORD_CANCEL_PRICE = Pattern.compile(
            "掛單價格\\s*\\(Price\\)\\s*\\n\\s*(\\d+\\.?\\d*)"
    );

    /**
     * 解析交易訊號
     *
     * @param message 原始訊息文字
     * @return 解析後的 TradeSignal, 如果無法解析則返回 empty
     */
    public Optional<TradeSignal> parse(String message) {
        if (message == null || message.isBlank()) {
            return Optional.empty();
        }

        log.debug("開始解析訊號: {}", message);

        // 嘗試解析 Discord 頻道格式 (📢 交易訊號發布)
        Optional<TradeSignal> discordEntry = parseDiscordEntrySignal(message);
        if (discordEntry.isPresent()) {
            return discordEntry;
        }

        // 嘗試解析 Discord 掛單取消 (⚠️ 掛單取消)
        Optional<TradeSignal> discordCancel = parseDiscordCancelSignal(message);
        if (discordCancel.isPresent()) {
            return discordCancel;
        }

        // 嘗試解析陳哥策略訊號 (限價單)
        Optional<TradeSignal> limitSignal = parseLimitSignal(message);
        if (limitSignal.isPresent()) {
            return limitSignal;
        }

        // 嘗試解析陳哥觸發訊息
        Optional<TradeSignal> triggerSignal = parseTriggerSignal(message);
        if (triggerSignal.isPresent()) {
            return triggerSignal;
        }

        log.debug("無法解析訊號: {}", message);
        return Optional.empty();
    }

    // ==================== Discord 頻道格式解析 ====================

    /**
     * 解析 Discord 開單訊號
     * 格式:
     * 📢 交易訊號發布: ETHUSDT
     * 做多 LONG 🟢 (限價單)
     * 入場價格 (Entry)
     * 2650
     * 止盈目標 (TP)
     * 2790
     * 止損價格 (SL)
     * 2580
     */
    private Optional<TradeSignal> parseDiscordEntrySignal(String message) {
        Matcher symbolMatcher = DISCORD_ENTRY_SYMBOL.matcher(message);
        if (!symbolMatcher.find()) {
            return Optional.empty();
        }

        String symbol = symbolMatcher.group(1);
        if (!symbol.endsWith("USDT")) {
            symbol = symbol + "USDT";
        }

        // 解析方向
        Matcher sideMatcher = DISCORD_SIDE.matcher(message);
        if (!sideMatcher.find()) {
            log.warn("Discord訊號缺少方向: {}", message);
            return Optional.empty();
        }
        TradeSignal.Side side = sideMatcher.group(1).contains("做空")
                ? TradeSignal.Side.SHORT
                : TradeSignal.Side.LONG;

        // 解析入場價格
        Matcher entryMatcher = DISCORD_ENTRY_PRICE.matcher(message);
        if (!entryMatcher.find()) {
            log.warn("Discord訊號缺少入場價格: {}", message);
            return Optional.empty();
        }
        double entryPrice = Double.parseDouble(entryMatcher.group(1));

        // 解析止盈 (可能是「未設定」)
        List<Double> takeProfits = new ArrayList<>();
        Matcher tpMatcher = DISCORD_TP.matcher(message);
        if (tpMatcher.find() && !"未設定".equals(tpMatcher.group(1))) {
            takeProfits.add(Double.parseDouble(tpMatcher.group(1)));
        }

        // 解析止損 (可能是「未設定」)
        double stopLoss = 0;
        Matcher slMatcher = DISCORD_SL.matcher(message);
        if (slMatcher.find() && !"未設定".equals(slMatcher.group(1))) {
            stopLoss = Double.parseDouble(slMatcher.group(1));
        }

        TradeSignal signal = TradeSignal.builder()
                .symbol(symbol)
                .side(side)
                .entryPriceLow(entryPrice)
                .entryPriceHigh(entryPrice)
                .stopLoss(stopLoss)
                .takeProfits(takeProfits)
                .signalType(TradeSignal.SignalType.ENTRY)
                .rawMessage(message)
                .build();

        log.info("解析Discord開單訊號: {} {} 入場:{} 止損:{} 止盈:{}",
                symbol, side, entryPrice, stopLoss, takeProfits);

        return Optional.of(signal);
    }

    /**
     * 解析 Discord 掛單取消訊號
     * 格式:
     * ⚠️ 掛單取消: ETHUSDT
     * 方向 (Side)
     * 做多 LONG
     * 掛單價格 (Price)
     * 2850
     */
    private Optional<TradeSignal> parseDiscordCancelSignal(String message) {
        Matcher symbolMatcher = DISCORD_CANCEL_SYMBOL.matcher(message);
        if (!symbolMatcher.find()) {
            return Optional.empty();
        }

        String symbol = symbolMatcher.group(1);
        if (!symbol.endsWith("USDT")) {
            symbol = symbol + "USDT";
        }

        // 解析方向
        Matcher sideMatcher = DISCORD_SIDE.matcher(message);
        TradeSignal.Side side = TradeSignal.Side.LONG;
        if (sideMatcher.find()) {
            side = sideMatcher.group(1).contains("做空")
                    ? TradeSignal.Side.SHORT
                    : TradeSignal.Side.LONG;
        }

        TradeSignal signal = TradeSignal.builder()
                .symbol(symbol)
                .side(side)
                .signalType(TradeSignal.SignalType.CANCEL)
                .rawMessage(message)
                .build();

        log.info("解析Discord取消訊號: {} {}", symbol, side);

        return Optional.of(signal);
    }

    // ==================== 陳哥格式解析 ====================

    /**
     * 解析限價策略訊號
     */
    private Optional<TradeSignal> parseLimitSignal(String message) {
        Matcher signalMatcher = SIGNAL_PATTERN.matcher(message);
        if (!signalMatcher.find()) {
            return Optional.empty();
        }

        String coin = signalMatcher.group(1);                // BTC
        double priceLow = Double.parseDouble(signalMatcher.group(2));  // 70800
        double priceHigh = Double.parseDouble(signalMatcher.group(3)); // 72000
        String direction = signalMatcher.group(4);            // 做空 or 做多

        TradeSignal.Side side = "做空".equals(direction)
                ? TradeSignal.Side.SHORT
                : TradeSignal.Side.LONG;

        // 解析止損
        Matcher slMatcher = STOP_LOSS_PATTERN.matcher(message);
        double stopLoss = 0;
        if (slMatcher.find()) {
            stopLoss = Double.parseDouble(slMatcher.group(1));
        } else {
            log.warn("未找到止損價格, 訊號不完整");
            return Optional.empty();
        }

        // 解析止盈
        Matcher tpMatcher = TAKE_PROFIT_PATTERN.matcher(message);
        List<Double> takeProfits = new ArrayList<>();
        if (tpMatcher.find()) {
            String tpStr = tpMatcher.group(1);
            for (String tp : tpStr.split("/")) {
                takeProfits.add(Double.parseDouble(tp.trim()));
            }
        }

        if (takeProfits.isEmpty()) {
            log.warn("未找到止盈價格");
        }

        String symbol = coin + "USDT";

        TradeSignal signal = TradeSignal.builder()
                .symbol(symbol)
                .side(side)
                .entryPriceLow(priceLow)
                .entryPriceHigh(priceHigh)
                .stopLoss(stopLoss)
                .takeProfits(takeProfits)
                .rawMessage(message)
                .build();

        log.info("解析成功: {} {} 入場:{}-{} 止損:{} 止盈:{}",
                symbol, side, priceLow, priceHigh, stopLoss, takeProfits);

        return Optional.of(signal);
    }

    /**
     * 解析觸發入場訊息
     * 這類訊息表示已經到達入場價, 可以用市價單入場
     */
    private Optional<TradeSignal> parseTriggerSignal(String message) {
        Matcher triggerMatcher = TRIGGER_PATTERN.matcher(message);
        if (!triggerMatcher.find()) {
            return Optional.empty();
        }

        double triggerPrice = Double.parseDouble(triggerMatcher.group(1));
        String directionStr = triggerMatcher.group(2);

        TradeSignal.Side side = "空單".equals(directionStr)
                ? TradeSignal.Side.SHORT
                : TradeSignal.Side.LONG;

        // 觸發訊息通常沒有完整的止損止盈, 需要參考之前的策略訊號
        TradeSignal signal = TradeSignal.builder()
                .symbol("BTCUSDT") // 預設 BTC, 因為觸發訊息可能不帶幣種
                .side(side)
                .entryPriceLow(triggerPrice)
                .entryPriceHigh(triggerPrice)
                .rawMessage(message)
                .build();

        log.info("解析觸發訊號: {} {} @ {}", signal.getSymbol(), side, triggerPrice);
        return Optional.of(signal);
    }
}
