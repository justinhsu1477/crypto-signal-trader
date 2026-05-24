package com.trader.trading.service;

import com.trader.notification.service.DiscordWebhookService;
import com.trader.shared.model.TradeRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Guard 防 Gemini 把 MOVE_SL 訊號誤判成 CLOSE — 結構性偵測，不依賴文字。
 *
 * <h3>誤判 fingerprint</h3>
 * Gemini 看到「做成本保護止損修改入場價75100」這類訊息，會把它誤判輸出：
 * <pre>{action:CLOSE, close_ratio:null, new_stop_loss:75100}</pre>
 *
 * 這個 payload 組合在正常用法**結構矛盾**：
 * <ul>
 *   <li>{@code close_ratio=null} 在 backend 被解讀為「全平」</li>
 *   <li>但又同時帶 {@code new_stop_loss} → 都要全平了還設止損？</li>
 * </ul>
 *
 * 後端如果照 CLOSE 跑下去 → 全平倉 → 用戶錯過後續行情（已有真實案例：2026-05 BTC 多單浮盈中
 * 收到此誤判 → 全平 → 錯過大波段）。
 *
 * <h3>處理策略</h3>
 * <p>Convert（而非 reject）：把 action 改成 MOVE_SL，保留 new_stop_loss，倉位不動。
 *
 * <p><strong>設計取捨</strong>：「漏關（可手動補關）優於誤平（資金損失不可救）」。
 * 真實 CLOSE 訊號不會帶 new_stop_loss（全平不需設止損），所以不會 false positive。
 *
 * <h3>Kill switch</h3>
 * env var {@code SIGNAL_GUARD_SUSPICIOUS_CLOSE_ENABLED=false} 一鍵 disable 直至下次重啟。
 *
 * <h3>觀測</h3>
 * 每次 convert 都會 (1) log WARN (2) 寄 Discord admin notification，方便人工 review。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SuspiciousClosePayloadGuard {

    private final DiscordWebhookService discordWebhookService;

    @Value("${signal.guard.suspicious-close.enabled:true}")
    private boolean enabled;

    public enum Result {
        /** Payload 正常或 guard 關閉 — 不動。 */
        PASS_THROUGH,
        /** 偵測到誤判 fingerprint — 已把 action 從 CLOSE 改成 MOVE_SL。 */
        CONVERTED,
    }

    /**
     * Inspect + 必要時 in-place convert CLOSE → MOVE_SL。
     *
     * <p>呼叫端：{@code TradeController#broadcastTrade} 跟 {@code executeTrade} 在 symbol 驗證後、
     * dedup / record / executor 之前。
     *
     * <p>Caller 不用檢查 result — 即使 CONVERTED，後續流程拿到的 request 已經是修正版，
     * 照原本 MOVE_SL flow 跑即可。
     */
    public Result inspect(TradeRequest request) {
        if (!enabled || request == null) {
            return Result.PASS_THROUGH;
        }
        if (request.getAction() == null || !"CLOSE".equalsIgnoreCase(request.getAction())) {
            return Result.PASS_THROUGH;
        }
        if (request.getCloseRatio() != null) {
            // close_ratio 有給（0.5、1.0 都算）→ 是正常 partial / full close，不動
            return Result.PASS_THROUGH;
        }
        if (request.getNewStopLoss() == null) {
            // 沒帶 new_stop_loss → 是正常全平
            return Result.PASS_THROUGH;
        }

        // 三條件命中：CLOSE + close_ratio=null + new_stop_loss != null = 結構性矛盾
        Double sl = request.getNewStopLoss();
        String symbol = request.getSymbol();

        log.warn("🛡️ SuspiciousClosePayloadGuard 偵測到 CLOSE/MOVE_SL 誤判 fingerprint — 自動轉 MOVE_SL: " +
                        "symbol={} new_stop_loss={} (原 close_ratio=null + new_stop_loss 非 null = 矛盾)",
                symbol, sl);

        request.setAction("MOVE_SL");
        // close_ratio 維持 null（無意義 for MOVE_SL）, new_stop_loss / new_take_profit 維持

        try {
            notifyAdmin(request, sl);
        } catch (Exception e) {
            // 通知失敗 must NOT block convert，主流程業務優先
            log.warn("guard admin notification failed (swallowed): {}", e.getMessage());
        }

        return Result.CONVERTED;
    }

    private void notifyAdmin(TradeRequest req, Double sl) {
        String title = "🛡️ SuspiciousClosePayloadGuard 觸發";
        StringBuilder body = new StringBuilder();
        body.append("Symbol: ").append(req.getSymbol() != null ? req.getSymbol() : "?").append("\n");
        body.append("原 payload: action=CLOSE, close_ratio=null, new_stop_loss=").append(sl).append("\n");
        body.append("已轉為:    action=MOVE_SL, new_stop_loss=").append(sl).append("\n");
        if (req.getNewTakeProfit() != null) {
            body.append("（同時保留 new_take_profit=").append(req.getNewTakeProfit()).append("）\n");
        }
        body.append("\n");
        body.append("原因：CLOSE 帶 new_stop_loss 但無 close_ratio 是結構矛盾 payload，疑似 Gemini 把\n");
        body.append("「做成本保護止損修改XXX」這類 MOVE_SL 訊息誤判 CLOSE。\n");
        body.append("Guard 自動降級保住倉位。如需真的關倉，請手動處理。\n");
        body.append("\n");
        body.append("Kill switch: env var SIGNAL_GUARD_SUSPICIOUS_CLOSE_ENABLED=false");
        discordWebhookService.sendNotification(title, body.toString(), DiscordWebhookService.COLOR_YELLOW);
    }
}
