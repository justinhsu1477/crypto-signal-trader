package com.trader.notification.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 限價入場成交批次通知服務
 *
 * 將多個用戶的 LIMIT 入場成交事件收集到佇列中，
 * 定期（每 60 秒）整合成一份彙總報告發送給所有 Admin。
 *
 * 報告格式類似廣播跟單報告：
 * - 成交人數統計
 * - 成交明細（最多 10 筆）
 *
 * 執行緒安全：使用 ConcurrentLinkedQueue，
 * 由 WebSocket event thread 寫入，由 @Scheduled thread 讀取。
 */
@Slf4j
@Service
public class LimitFillBatchService {

    private final NotificationService notificationService;

    /**
     * 限價入場成交事件
     */
    public record LimitFillEvent(
            String displayName,
            String symbol,
            String side,
            double price,
            double quantity
    ) {}

    /**
     * Thread-safe 佇列，WebSocket event thread 寫入，@Scheduled thread drain
     */
    private final ConcurrentLinkedQueue<LimitFillEvent> pendingFills = new ConcurrentLinkedQueue<>();

    public LimitFillBatchService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    /**
     * 新增一筆限價入場成交事件到批次佇列
     *
     * @param displayName 用戶顯示名稱（如 "Justin (justin@example.com)"）
     * @param symbol      交易對（如 "BTCUSDT"）
     * @param side        方向（"LONG" / "SHORT"）
     * @param price       成交價
     * @param quantity    成交數量
     */
    public void addFill(String displayName, String symbol, String side, double price, double quantity) {
        pendingFills.add(new LimitFillEvent(displayName, symbol, side, price, quantity));
        log.debug("限價入場成交加入批次: {} {} {} @ {} × {}", displayName, symbol, side, price, quantity);
    }

    /**
     * 定期刷新佇列，將收集到的成交事件整合成彙總報告發送給 Admin
     *
     * 每 60 秒執行一次。佇列為空時不發送。
     */
    @Scheduled(fixedDelay = 60000)
    public void flush() {
        List<LimitFillEvent> fills = drainQueue();
        if (fills.isEmpty()) return;

        String report = buildReport(fills);
        String title = "📊 限價入場成交彙總";

        notificationService.sendNotificationToAdmins(title, report, NotificationService.COLOR_GREEN);
        log.info("限價入場成交彙總已發送: {} 筆成交", fills.size());
    }

    /**
     * Drain 所有待處理事件（thread-safe）
     * Package-private 供測試呼叫
     */
    List<LimitFillEvent> drainQueue() {
        List<LimitFillEvent> fills = new ArrayList<>();
        LimitFillEvent event;
        while ((event = pendingFills.poll()) != null) {
            fills.add(event);
        }
        return fills;
    }

    /**
     * 建構彙總報告字串
     * Package-private 供測試驗證格式
     *
     * 格式：
     * 成交人數: N 人
     *
     * 成交明細:
     * - 用戶A: BTCUSDT LONG @ 65000.00 × 0.0010
     * - 用戶B: ETHUSDT SHORT @ 3200.00 × 0.1000
     * ...及其他 N 人
     */
    String buildReport(List<LimitFillEvent> fills) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("成交人數: %d 人", fills.size()));

        sb.append("\n\n成交明細:");
        int maxDetails = 10;
        int count = 0;
        for (LimitFillEvent fill : fills) {
            if (count >= maxDetails) break;
            sb.append(String.format("\n- %s: %s %s @ %.2f × %.4f",
                    fill.displayName(), fill.symbol(), fill.side(), fill.price(), fill.quantity()));
            count++;
        }
        if (fills.size() > maxDetails) {
            sb.append(String.format("\n...及其他 %d 人", fills.size() - maxDetails));
        }

        return sb.toString();
    }

    /**
     * 取得待處理事件數量（供測試用）
     */
    int getPendingCount() {
        return pendingFills.size();
    }
}
