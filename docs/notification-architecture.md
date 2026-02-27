# 通知架構 — Notification Architecture

> 上次更新：2026-02-27

## Interface 設計

```java
public interface NotificationService {
    void sendNotification(String title, String message, int color);           // 全局 webhook
    void sendNotificationToUser(String userId, String title, String message, int color);  // per-user webhook
    void sendNotificationToAdmins(String title, String message, int color);  // 所有 Admin per-user
    void sendNotificationToAdmins(String displayName, String title, String message, int color);  // 帶用戶前綴
    void evictUserCache(String userId);
    void evictAllCache();
}
```

實作：`DiscordWebhookService implements NotificationService`
未來擴展：`LineNotificationService`、`TelegramNotificationService` 可直接 implements

## 三種路由模式

### 1. notifyGlobal() — 風控告警（BinanceFuturesService 內部）
```
全局 webhook + 受影響用戶 per-user + Admin per-user（帶 displayName）
```
觸發條件：交易異常、風控熔斷
多用戶模式才發 per-user + admin（單用戶模式只發全局）

### 2. notifySystem() — 系統監控（MonitorHeartbeatService 內部）
```
全局 webhook + Admin per-user
```
觸發條件：心跳異常、服務狀態變更

### 3. 直接呼叫 — 業務通知（各 Service 自行呼叫）
```
sendNotificationToUser(userId, ...)    — 個別用戶
sendNotificationToAdmins(...)          — 所有 Admin
sendNotification(...)                  — 全局
```

## 通知情境分類

### A. 風控告警（notifyGlobal → 三路）
| 場景 | 來源 | 顏色 |
|------|------|------|
| 每日虧損熔斷 | BinanceFuturesService | RED |
| Fail-Safe 觸發（3 種） | BinanceFuturesService | RED |
| 止盈單失敗（2 種） | BinanceFuturesService | RED/YELLOW |
| SL 重掛失敗（2 種） | BinanceFuturesService | RED |
| 移動止損失敗 | BinanceFuturesService | RED |
| Algo 取消失敗 | BinanceFuturesService | RED |
| SL/TP 部分取消 | BinanceFuturesService | YELLOW |
| CLOSE 無持倉 | BinanceFuturesService | YELLOW |
| Symbol 修正 | BinanceFuturesService | YELLOW |
| 下單重試失敗 | BinanceFuturesService | RED |
| API 連線中斷 | BinanceFuturesService | RED |

### B. 系統監控（notifySystem → 雙路）
| 場景 | 來源 | 顏色 |
|------|------|------|
| Discord 連線中斷/恢復 | MonitorHeartbeatService | RED/GREEN |
| AI Agent 未啟用/已啟用 | MonitorHeartbeatService | YELLOW/GREEN |
| Monitor 離線 | MonitorHeartbeatService | RED |

### C. WebSocket 連線（全局 + Admin）
| 場景 | 來源 | 顏色 |
|------|------|------|
| 單用戶 WS 斷線/恢復/重連失敗 | BinanceUserDataStreamService | RED/GREEN |
| 多用戶 WS 斷線/恢復/重連失敗 | MultiUserDataStreamManager | RED/GREEN |

### D. 啟動對帳（全局 + Admin）
| 場景 | 來源 | 顏色 |
|------|------|------|
| 對帳完成摘要 | StartupReconciliationService | BLUE |
| 對帳失敗 | StartupReconciliationService | YELLOW |

### E. 廣播跟單（per-user + Admin 報告）
| 場景 | 接收者 | 顏色 |
|------|--------|------|
| 訊號已發送 | Admin per-user | BLUE |
| 跟單成功（enriched：成交價/數量/手續費） | 用戶 per-user | GREEN |
| 跟單失敗（含錯誤訊息） | 用戶 per-user | RED |
| 平倉成功（enriched：成交價/PnL/手續費） | 用戶 per-user | GREEN |
| 彙總報告 ENTRY（成交明細，前 10 筆） | Admin per-user | GREEN/YELLOW |
| 彙總報告 CLOSE（總損益/平均/明細） | Admin per-user | GREEN/YELLOW |

### F. 個人交易（per-user + 全局）
| 場景 | 接收者 | 顏色 |
|------|--------|------|
| execute-trade ENTRY 結果 | 操作用戶 + 全局 | GREEN/RED |
| execute-trade CLOSE 結果 | 操作用戶 + 全局 | GREEN/RED |
| execute-trade MOVE_SL 結果 | 操作用戶 + 全局 | GREEN/RED |
| execute-trade CANCEL 結果 | 操作用戶 + 全局 | GREEN/RED |

### G. 排程通知（per-user + 全局）
| 場景 | 來源 | 顏色 |
|------|------|------|
| 每日報告 | DailyReportService | BLUE |
| 訂閱到期提醒 | SubscriptionScheduler | YELLOW |

### H. WebSocket 交易事件（per-user）
| 場景 | 來源 | 顏色 |
|------|------|------|
| SL/TP 觸發 + PnL | OrderEventHandler | GREEN/RED |

## Admin 彙總報告格式

### ENTRY 報告
```
📊 廣播跟單報告
BTCUSDT ENTRY
成功: 3 人
失敗: 1 人
超時: 0 人
總計: 4 人

成交明細:
- User1 (a@b.com): 94950.5 × 0.010
- User2 (c@d.com): 94951.0 × 0.020
- User3 (e@f.com): 94949.8 × 0.015

失敗明細:
- User4 (g@h.com): Insufficient margin
```

### CLOSE 報告
```
📊 廣播平倉報告
BTCUSDT CLOSE
成功: 3 人
失敗: 0 人
總計: 3 人

總損益: +425.76 USDT
平均: +141.92 USDT

平倉明細:
- User1 (a@b.com): +150.32 USDT
- User2 (c@d.com): +180.44 USDT
- User3 (e@f.com): +95.00 USDT
```

## 快取機制

| 快取 | TTL | 用途 |
|------|-----|------|
| per-user webhook URL | 5 分鐘 | 避免每次通知查 DB |
| Admin userId 列表 | 5 分鐘 | sendNotificationToAdmins 用 |
| evictUserCache(userId) | 手動 | API Key 更新時清除 |
| evictAllCache() | 手動 | 配置變更時全清 |

## 擴展預留

### 新增通知頻道（LINE / Telegram）
1. 新增 `LineNotificationService implements NotificationService`
2. 用 `@Primary` 或 `@Qualifier` 切換
3. `int color` 參數可映射成嚴重度（INFO/WARN/ERROR）
4. per-user webhook URL 結構已支援多頻道（`user_notification_settings` 表可擴展）

### 新增通知分類
1. `NotificationCategory` enum 已預留（TRADE / RISK / SYSTEM / REPORT）
2. `sendNotificationToUser(userId, category, ...)` 重載已定義
3. 未來可讓用戶設定「只接收某分類的通知」

### 新增訊號來源
- 目前：Python Discord Monitor → `/api/execute-signal` → broadcast
- 未來：Webhook API（TradingView）→ `signal` 模組 → 標準化 TradeRequest → broadcast
- NotificationService 與訊號來源無耦合，新來源自動適用現有通知路由
