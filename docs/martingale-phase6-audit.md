# Martingale Phase 6 生產審查報告

> 2026-04-07 由 3 個並行審查 agent 產出，涵蓋核心流程、風控清理、設定持久化。
> 每項修復後勾選 checkbox。

---

## Agent 1 — 核心執行流程（策略 → 下單 → 成交 → TP/exit）

### P0

- [x] **A1-P0-1: `startSession(putIfAbsent)` 返回 EXITING 殭屍 session**
  - 檔案：`MartingaleSessionManager.java:43`
  - 問題：`putIfAbsent` 遇到 EXITING session 會返回舊的殭屍 session，導致新訊號的 TP 管理失效
  - 修復：改用 `compute`，EXITING 時覆蓋 → **6-3 已修**

- [x] **A1-P0-2: FillListener `markFilledLayer` + `persistSession` 在 lock 外執行**
  - 檔案：`MartingaleFillListener.java:83-97`
  - 問題：與 `adjustExistingSession` 讀 `filledLayers` 產生 TOCTOU 競爭，可能導致 session 被錯誤重建
  - 修復：整個 recorded 區塊包進 symbol lock → **6-4 已修**

- [x] **A1-P0-3: `Order.quantity/price` 是 boxed `Double`，NPE 風險**
  - 檔案：`OrderExecutor.java:78`, `Order.java:14-15`
  - 問題：CLOSE order 的 `.price(null)` 在 auto-unbox 時可能 NPE；ENTRY 的 `order.getQuantity() < minQty` 亦有風險
  - 修復：在 OrderExecutor switch 前加 null guard → **Phase 6 後續修**

- [x] **A1-P0-4: SHORT TP 價格可能 ≤ 0**
  - 檔案：`MartingaleStrategy.java:480-482`, `MartingaleTpManager.java:74-75`, `MartingaleStopLossWatcher.java:320`
  - 問題：`takeProfitPercent >= 1.0` 時 SHORT TP 變成零或負數
  - 修復：三處加 `if (tpPrice <= 0)` guard → **6-6 已修**

### P1

- [x] **A1-P1-1: Fallback TP 偵測過於寬泛 — 任何 MARKET FILLED 都觸發 session 清理**
  - 檔案：`MartingaleFillListener.java:101-111`
  - 問題：CLOSE 流程的市價平倉 fill 可能在 `markExiting` 之前到達，觸發 fallback 清理 session
  - 修復：加 `filledLayers > 0` 過濾，確保有成交才觸發 fallback → **Phase 6 後續修**

- [x] **A1-P1-5: OrderExecutor ENTRY eager fill recording 導致雙倍計算**
  - 檔案：`OrderExecutor.java:96-99`
  - 問題：LIMIT 下單後立刻記錄 fill（不經 dedup），WebSocket 到達後再記一次
  - 修復：移除 eager recording → **6-7 已修**

- [x] **A1-P1-7: `executeNewSession` 缺 `maxConcurrentSessions` 檢查**
  - 檔案：`MartingaleStrategy.java:341`
  - 問題：`adjustExistingSession` 的 filled=0 路徑重建 session 時未檢查併發上限
  - 修復：在 `executeNewSession` 頂部加上限檢查 → **Phase 6 後續修**

- [x] **A1-P2-2: `UserDataEventDispatcher` observer 異常用 DEBUG 級別**
  - 檔案：`UserDataEventDispatcher.java:31`
  - 問題：FillListener 異常在生產環境不可見，fill 事件靜默丟失
  - 修復：改為 `log.warn` → **Phase 6 後續修**

---

## Agent 2 — 風控與清理（止損 → 超時 → session 生命週期）

### P0

- [x] **A2-P0-1: `removeLockIfIdle` 競爭條件 — lock 被移除後其他 thread 持有舊引用**
  - 檔案：`SymbolLockRegistry.java:35-37`
  - 問題：Thread A 持有 L1 引用 → Thread B 移除 L1 → Thread C 取得新 L2 → A 和 C 用不同 lock 同時操作
  - 修復：完全移除 `removeLockIfIdle` → **6-2 已修**

- [x] **A2-P0-3: `handleIdleEntryTimeout` 缺 `stateStore.removeSession` — 重啟後幽靈 session**
  - 檔案：`MartingaleSessionCleanupTask.java:146-148`
  - 問題：Redis 狀態未清理，重啟後恢復為殭屍 session
  - 修復：加 `markExiting` + `stateStore.removeSession` → **6-1 已修**

### P1

- [x] **A2-P1-2: `handleIdleEntryTimeout` 缺 `markExiting` — 狀態機違反**
  - 檔案：`MartingaleSessionCleanupTask.java:146`
  - 修復：加 `markExiting` → **6-1 已修**

- [x] **A2-P1-5: `isStopLossTriggered` 重新取 session — 快速輪替時可能讀錯 SL**
  - 檔案：`MartingaleStopLossWatcher.java:52-77`
  - 問題：loop 用 snapshot session 但 `isStopLossTriggered` 重新呼叫 `getActiveSession`，快速 end+start 後可能取到新 session 的 SL
  - 修復：改為直接傳 session 引用，不重新取 → **Phase 6 後續修**

- [x] **A2-P1-7: OrderExecutor CLOSE/failed 路徑缺 Redis 清理**
  - 檔案：`OrderExecutor.java:137,153-154`
  - 修復：注入 `MartingaleStateStore`，加 `stateStore.removeSession` → **6-8 已修**

- [x] **A2-P1-8: `executeStopLoss` teardown 順序不一致 — endSession 先於 removeSession**
  - 檔案：`MartingaleStopLossWatcher.java:360-362`
  - 修復：調整為 `stateStore.removeSession` → `sessionManager.endSession` → **6-9 已修**

- [x] **A2-P1-9: `MartingaleRecoveryTask` Redis 丟失後不設 filledLayers**
  - 檔案：`MartingaleRecoveryTask.java:82-83`
  - 問題：Redis 丟失後恢復的 session filledLayers=0，導致 SL 基準價錯誤、超時策略錯誤、trailing/decay 失效
  - 修復：恢復時 `session.markFilledLayer()` + `layerFillTracker.recordFillDirect` → **Phase 6 後續修**

- [x] **A2-P1-12: `handleTpFilled` 未驗證倉位已平 — 可能倉位洩漏**
  - 檔案：`MartingaleTpManager.java:108-139`
  - 修復：加 `getCurrentPositionAmount` 驗證 + 未平倉則保留 EXITING → **6-10 已修**

### P2

- [ ] **A2-P2-4: `getSessionsSnapshot` 返回 live view 非真正 snapshot**
  - 檔案：`MartingaleSessionManager.java:67-69`
  - 修復：改為 `List.copyOf(sessions.values())`

- [ ] **A2-P2-13: `processedFills` Set 無上限 — 緩慢記憶體洩漏**
  - 檔案：`LayerFillTracker.java:33`
  - 修復：目前 `clearSymbol` 已覆蓋，可加 TTL 或 max-size guard

---

## Agent 3 — 設定、持久化、通知、外部服務

### P0

- [x] **A3-1.1: Config 無任何驗證 — 危險值靜默接受**
  - 檔案：`MartingaleStrategyConfig.java:100-154`
  - 修復：建構子加 `validate()` → **6-11 已修**

- [x] **A3-2.1: `createdAt` 未持久化 — 重啟後超時時鐘重設**
  - 檔案：`MartingaleStateStore.java:147,186-215`
  - 修復：`SessionSnapshot` 加 `createdAt` 字串 + 恢復時 `setCreatedAt` → **6-5 已修**

### P1

- [x] **A3-1.2: `tpDecayIntervalMinutes=0` 除以零**
  - 檔案：`MartingaleStrategyConfig.java:121`
  - 修復：`validate()` 涵蓋 → **6-11 已修**

- [x] **A3-1.3: `SymbolOverride` 值無驗證**
  - 檔案：`MartingaleStrategyConfig.java:57-72`
  - 問題：per-symbol override 如 `maxLayers:-1` 或 `stepPercent:0` 靜默生效
  - 修復：`getEffective*` 方法加 sanity check，無效值 fallback 全域 → **Phase 6 後續修**

- [x] **A3-2.2: `exitRetryCount` 未持久化 — 跨重啟無限重試**
  - 檔案：`MartingaleStateStore.java:186-215`
  - 修復：`SessionSnapshot` 加 `exitRetryCount` → **6-5 已修**

- [x] **A3-3.1: 通知失敗 DEBUG 級別 — 生產不可見**
  - 檔案：`MartingaleNotifier.java:107`
  - 問題：Discord webhook 掛掉時 operator 完全不知
  - 修復：改為 `log.warn` → **Phase 6 後續修**

- [x] **A3-4.4: `getMarkPrice` 實際返回 last trade price 非 mark price**
  - 檔案：`BinanceFuturesService.java:273-282`
  - 問題：高波動時 last price 與 mark price 偏離，影響 SL/TP 判斷
  - 修復：改用 `/fapi/v1/premiumIndex` 取 `markPrice` 欄位 → **Phase 6 後續修**

- [x] **A3-5.1: `trailingLevel`/`tpDecayLevel` 用 volatile 非 AtomicInteger**
  - 檔案：`MartingaleSession.java:28-30`
  - 問題：check-then-act 非原子，可能送出重複 TP 更新
  - 修復：改用 `AtomicInteger`（與 `filledLayers` 一致） → **Phase 6 後續修**

- [x] **A3-5.2: `setBreakevenActivated(false)` 會摧毀 trailing 進度**
  - 檔案：`MartingaleSession.java:147-154`
  - 問題：從 level 3/4 直接重設為 0，移除 trailing 保護
  - 修復：加 guard `trailingLevel <= 1` 時才允許 reset → **Phase 6 後續修**

### P2

- [ ] **A3-2.3: `new ObjectMapper()` 未用 Spring 管理的實例**
  - 檔案：`MartingaleStateStore.java:42`
  - 修復：改注入 Spring `ObjectMapper`

- [ ] **A3-2.6: `restoreFromRedis` 用 `KEYS` 指令（阻塞 Redis）**
  - 檔案：`MartingaleStateStore.java:119`
  - 修復：改用 `SCAN`

- [ ] **A3-4.2: `getCurrentPositionAmount` log 寫死 "BTC"**
  - 檔案：`BinanceFuturesService.java:228`
  - 修復：移除硬編碼

- [ ] **A3-4.6: `formatPrice` 用固定小數位而非交易所 tick size**
  - 檔案：`BinanceFuturesService.java:1453-1461`
  - 修復：載入 `PRICE_FILTER.tickSize` 並使用

---

## 修復進度總覽

| 嚴重度 | 總數 | 已修 | 剩餘 |
|--------|------|------|------|
| P0     | 8    | 8    | 0    |
| P1     | 16   | 16   | 0    |
| P2     | 7    | 1    | 6    |
| **合計** | **31** | **25** | **6** |
