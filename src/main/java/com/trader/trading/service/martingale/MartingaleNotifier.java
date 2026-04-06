package com.trader.trading.service.martingale;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.shared.model.TradeSignal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MartingaleNotifier {

    private static final int COLOR_GREEN = 0x2ECC71;
    private static final int COLOR_RED = 0xE74C3C;
    private static final int COLOR_ORANGE = 0xF39C12;
    private static final int COLOR_BLUE = 0x3498DB;

    private final DiscordWebhookService discordWebhookService;

    public MartingaleNotifier(DiscordWebhookService discordWebhookService) {
        this.discordWebhookService = discordWebhookService;
    }

    public void notifySessionStarted(String symbol, TradeSignal.Side side, int layers, double baseEntry) {
        send("Martingale 建倉",
                String.format("%s %s | %d 層 | 基準價 %.4f", symbol, side, layers, baseEntry),
                COLOR_BLUE);
    }

    public void notifyLayerFilled(String symbol, double price, double qty, double avgPrice, double totalQty) {
        send("Martingale 層成交",
                String.format("%s | 成交 %.6f @ %.4f\n均價 %.4f | 總量 %.6f", symbol, qty, price, avgPrice, totalQty),
                COLOR_BLUE);
    }

    public void notifyTpUpdated(String symbol, double tpPrice, double qty) {
        send("Martingale TP 更新",
                String.format("%s | TP %.4f | 數量 %.6f", symbol, tpPrice, qty),
                COLOR_GREEN);
    }

    public void notifyStopLossTriggered(String symbol, TradeSignal.Side side, double baseEntry, double markPrice) {
        send("Martingale 止損觸發",
                String.format("%s %s | 基準 %.4f | 觸發價 %.4f", symbol, side, baseEntry, markPrice),
                COLOR_RED);
    }

    public void notifySessionTimeout(String symbol) {
        send("Martingale 超時清理",
                String.format("%s | Session 閒置超時，已市價平倉", symbol),
                COLOR_ORANGE);
    }

    public void notifyBreakevenActivated(String symbol, double breakevenTpPrice, double qty) {
        send("Martingale 保本 TP 啟動",
                String.format("%s | 保本 TP %.4f | 數量 %.6f", symbol, breakevenTpPrice, qty),
                COLOR_GREEN);
    }

    public void notifyTrailingStopAdvanced(String symbol, int level, double tpPrice, double qty) {
        String[] levelNames = {"", "保本", "鎖利 Lv2", "鎖利 Lv3", "鎖利 Lv4"};
        String levelName = level > 0 && level < levelNames.length ? levelNames[level] : "Lv" + level;
        send("Martingale Trailing TP " + levelName,
                String.format("%s | Level %d | TP %.4f | 數量 %.6f", symbol, level, tpPrice, qty),
                COLOR_GREEN);
    }

    public void notifyTpDecay(String symbol, int level, double tpPercent, double tpPrice) {
        send("Martingale TP 時間衰減",
                String.format("%s | 衰減 Lv%d | TP%.4f%% → %.4f", symbol, level, tpPercent * 100, tpPrice),
                COLOR_ORANGE);
    }

    public void notifyTpHit(String symbol, TradeSignal.Side side) {
        send("Martingale TP 成交",
                String.format("%s %s | TP 觸發平倉，Session 已清理", symbol, side),
                COLOR_GREEN);
    }

    public void notifyAllEntryFailed(String symbol) {
        send("Martingale 送單全部失敗",
                String.format("%s | 全部 ENTRY 送單失敗，Session 已清理", symbol),
                COLOR_RED);
    }

    public void notifyGhostPosition(String symbol, double remainingQty) {
        send("⚠ Martingale 幽靈倉位",
                String.format("%s | 平倉後仍有剩餘倉位 %.6f，需人工處理", symbol, remainingQty),
                COLOR_RED);
    }

    public void notifyPersistFailure(String symbol, String type) {
        send("⚠ Martingale Redis 持久化失敗",
                String.format("%s | %s 寫入 Redis 失敗（重試 3 次），重啟可能狀態錯亂", symbol, type),
                COLOR_RED);
    }

    private void send(String title, String message, int color) {
        try {
            discordWebhookService.sendNotification(title, message, color);
        } catch (Exception e) {
            log.debug("Martingale 通知發送失敗: {}", e.getMessage());
        }
    }
}
