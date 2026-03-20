# Martingale 策略模組 — 缺陷與改善追蹤

> 基於 2026-03-20 深度架構審查產出，涵蓋架構、策略邏輯、風控、狀態一致性、真實場景驗證。
> 每項完成後勾選 checkbox 並註明完成日期。

---

## Phase 2B — 關鍵修復（Critical / Must Fix）

> 未修復前不應進入實盤測試。

### 2B-1 主動止損監控

- [ ] **狀態：待開發**
- **類型：** 功能缺失（致命）
- **影響檔案：** 新增 `MartingaleStopLossWatcher.java`
- **問題描述：**
  目前止損檢查只發生在 `MartingaleStrategy.execute()` 收到新信號時（MartingaleStrategy.java:123-136）。如果信號來源中斷或該 symbol 不再有新信號觸發，即使價格已跌破止損線，系統也不會平倉。`MartingaleSessionCleanupTask` 只處理閒置超時，不檢查止損條件。
- **風險場景：**
  BTC LONG 入場 $60,000，止損線 $51,000。BTC 跌到 $49,000 但無新信號進來，30 分鐘後才被 CleanupTask 超時清理，實際虧損遠超預設的 15%。
- **修復方案：**
  新增 `MartingaleStopLossWatcher`（`@Scheduled` 每 5~10 秒），遍歷所有 active session，呼叫 `getMarkPrice` 檢查止損條件，觸發時執行市價平倉 + 清理 session + 發送通知。
- **驗收條件：**
  - 無新信號的情況下，價格觸及止損線後 10 秒內平倉
  - 平倉後 session 和 tracker 狀態正確清理
  - 有對應單元測試

---

### 2B-2 動態 TP（Take Profit）更新

- [ ] **狀態：待開發**
- **類型：** 邏輯缺陷（致命）
- **影響檔案：** `MartingaleFillListener.java`、新增 TP 更新邏輯、`OrderExecutor.java`
- **問題描述：**
  策略在 `execute()` 時一次性送出所有 ENTRY 單 + 1 張 TP 單（MartingaleStrategy.java:226-247）。TP 價格和數量按**全部計劃層**的加權均價計算，而非實際成交的層。
- **具體問題：**
  1. **TP 價格錯誤：** 5 層 LONG 計劃均價 ≈ $56,280，TP 掛 $56,843。但若只有 Layer 1（$60,000）成交，TP 價格低於入場價，反彈時不會觸發，或觸發時是虧損出場。
  2. **TP 數量錯誤：** TP 數量是全部計劃層的總量，但實際只有部分層成交。Binance 可能拒絕或只能部分成交。
- **風險場景：**
  Layer 1 成交後價格反彈 2%，本應獲利出場，但 TP 掛在遠低於入場價的位置，錯過出場機會。
- **修復方案：**
  在 `MartingaleFillListener` 收到 TRADE 事件且為 ENTRY 單時：
  1. 更新 `LayerFillTracker`（已有）
  2. 重算所有已成交層的加權均價
  3. 取消舊 TP 單
  4. 按實際均價和實際總數量重新掛出 TP 單
- **驗收條件：**
  - 每次 ENTRY 層成交後，TP 自動更新為正確的價格和數量
  - 只有 Layer 1 成交時，TP = Layer 1 入場價 × 1.01
  - 全部層成交時，TP = 全部加權均價 × 1.01
  - 有對應單元測試

---

### 2B-3 服務重啟復原機制

- [ ] **狀態：待開發**
- **類型：** 功能缺失（致命）
- **影響檔案：** 新增 `MartingaleRecoveryTask.java`
- **問題描述：**
  `MartingaleSessionManager` 和 `LayerFillTracker` 皆為純記憶體狀態。服務重啟後所有狀態清零，但 Binance 上的掛單和持倉仍然存在。
- **重啟後的災難場景：**
  1. SessionManager 無 session → 新信號建立新 session → 掛出重複訂單
  2. LayerFillTracker 的 `orderRefs` 清空 → WebSocket fill 事件全部被忽略
  3. 已掛的 ENTRY 單繼續成交，但無對應 TP 管理
  4. **結果：幽靈倉位，無止盈無止損**
- **修復方案：**
  新增 `MartingaleRecoveryTask`（`@EventListener(ApplicationReadyEvent.class)`）：
  1. 掃描 Binance 現有持倉（`getPositions`），找出有倉位的 symbol
  2. 掃描 Binance 現有掛單（`getOpenOrders`），識別馬丁相關訂單
  3. 重建對應的 `MartingaleSession`（status=ACTIVE）
  4. 重建 `LayerFillTracker` 的 orderRefs 和 fill 狀態
  5. 檢查是否需要重掛 TP 單
- **驗收條件：**
  - 服務重啟後，已有馬丁持倉的 symbol 自動恢復 session
  - 恢復後止損監控和 TP 管理正常運作
  - 不會掛出重複訂單

---

### 2B-4 RiskManager 趨勢過濾方向修正

- [ ] **狀態：待開發**
- **類型：** 邏輯缺陷（嚴重）
- **影響檔案：** `RiskManager.java`
- **問題描述：**
  RiskManager.java:32-34 只檢查 `ema50 < ema200` 就拒絕，未區分交易方向。
  - LONG 時 EMA50 > EMA200（黃金交叉）才順勢 → **目前邏輯正確**
  - SHORT 時 EMA50 < EMA200（死亡交叉）才順勢 → **目前邏輯反了**
- **後果：**
  - 做空 + 趨勢向下 → 被拒絕（應該允許）
  - 做空 + 趨勢向上 → 被允許（逆勢做空，極度危險）
- **修復方案：**
  `evaluateMartingale` 新增 `TradeSignal.Side side` 參數：
  - LONG 時：`ema50 < ema200` → reject
  - SHORT 時：`ema50 > ema200` → reject
- **驗收條件：**
  - LONG 在死亡交叉時被拒絕
  - SHORT 在黃金交叉時被拒絕
  - 有對應單元測試覆蓋兩個方向

---

## Phase 2C — 重要改善（Important / Should Improve）

> 提升風控完整性，防止慢性資金流失。

### 2C-1 Drawdown 計算納入未實現 PnL

- [ ] **狀態：待開發**
- **類型：** 風控缺陷
- **影響檔案：** `MartingaleStrategy.java`
- **問題描述：**
  MartingaleStrategy.java:109-110 的 drawdown 計算只用 `getTodayRealizedLoss()`（已平倉虧損），未實現虧損（浮虧）完全被忽略。
- **風險場景：**
  帳戶 $10,000，BTC 馬丁 session 浮虧 $1,500（15%），但因未平倉所以 `todayLoss = 0`。系統認為 drawdown = 0%，繼續允許開新的 ETH 馬丁 session，導致資金過度暴露。
- **修復方案：**
  呼叫 `PositionService` 或 `BinanceFuturesService.getPositions()` 取得所有持倉的 `unRealizedProfit`，合併到 drawdown 計算：
  ```
  drawdown = (|todayRealizedLoss| + |totalUnrealizedLoss|) / sodBalance
  ```
- **驗收條件：**
  - 浮虧 15% 時，drawdown 正確反映為 15%
  - 浮虧超過閾值時，新 session 被拒絕

---

### 2C-2 全域 Session 數量與資金上限

- [ ] **狀態：待開發**
- **類型：** 風控缺失
- **影響檔案：** `MartingaleSessionManager.java`、`MartingaleStrategyConfig.java`
- **問題描述：**
  目前每個 symbol 限一個 session，但對同時存在的 session 總數和總資金使用無限制。理論上可同時開 N 個幣種的馬丁 session，累計資金使用可能超過帳戶餘額。
- **修復方案：**
  - Config 新增 `maxConcurrentSessions`（建議預設 3）
  - `startSession` 前檢查 `sessions.size() < maxConcurrentSessions`
  - 可選：計算所有 active session 的累計 notional，與帳戶餘額對比
- **驗收條件：**
  - 達到 session 上限後，新 session 被拒絕
  - 有對應單元測試

---

### 2C-3 送單失敗處理與 Session 降級

- [ ] **狀態：待開發**
- **類型：** 異常處理缺失
- **影響檔案：** `OrderExecutor.java`
- **問題描述：**
  OrderExecutor.java:62-78 中 ENTRY 送單失敗時（Binance 限流、餘額不足等），只回傳失敗結果，不會：
  1. 記錄哪些層失敗
  2. 更新 session 的實際計劃層數
  3. 重算 TP 單（TP 仍按原始全部層計算）
- **後果：**
  Layer 1~3 成功，Layer 4~5 失敗。TP 按 5 層均價計算，價格偏低。
- **修復方案：**
  - 記錄每層送單結果到 session（成功/失敗）
  - 全部送完後，根據實際成功層數重算 TP
  - 可選：失敗的層做一次重試（需注意冪等性）
- **驗收條件：**
  - 部分層送單失敗時，TP 按實際成功層計算
  - session 正確記錄每層狀態

---

### 2C-4 CLOSE 流程原子性與 Fallback

- [ ] **狀態：待開發**
- **類型：** 異常處理缺失
- **影響檔案：** `OrderExecutor.java`
- **問題描述：**
  OrderExecutor.java:91-101 的 CLOSE 流程：`cancelAllOrders` → `placeMarketOrder` → `markExiting` → `endSession` → `clearSymbol`。每步都是網路呼叫，可能失敗。
- **風險場景：**
  `cancelAllOrders` 成功但 `placeMarketOrder` 失敗 → 掛單被取消但倉位未平 → `endSession` 執行 → session 被清理 → **裸倉位，無任何管理**。
- **修復方案：**
  - `placeMarketOrder` 失敗時重試一次
  - 最終仍失敗：保留 session 為 EXITING 狀態，**不執行** `endSession` 和 `clearSymbol`
  - `MartingaleSessionCleanupTask` 增加對 EXITING 狀態的處理：嘗試重新平倉
- **驗收條件：**
  - 市價平倉失敗時 session 保持 EXITING，不被刪除
  - CleanupTask 能撿起 EXITING 的 session 重試平倉

---

### 2C-5 通知機制整合

- [ ] **狀態：待開發**
- **類型：** 功能缺失
- **影響檔案：** 止損 watcher、CleanupTask、FillListener 等
- **問題描述：**
  馬丁 session 的所有關鍵事件都是靜默發生，使用者無法即時得知：
  - Session 建立（開始建倉）
  - 每層成交
  - 止損觸發
  - 超時清理平倉
  - TP 成交（獲利出場）
- **修復方案：**
  在各關鍵節點呼叫現有的 `DiscordWebhookService` 發送通知。
- **驗收條件：**
  - 上述 5 種事件各有 Discord 通知
  - 通知內容包含 symbol、方向、價格、數量、盈虧等關鍵資訊

---

### 2C-6 `MartingaleSession.filledLayers` 未被更新

- [ ] **狀態：待開發**
- **類型：** 邏輯缺陷
- **影響檔案：** `MartingaleFillListener.java`、`MartingaleSession.java`
- **問題描述：**
  `MartingaleSession` 有 `filledLayers`（AtomicInteger）和 `markFilledLayer()` 方法，但整個程式碼中**沒有任何地方呼叫它**。`MartingaleFillListener` 只更新 `LayerFillTracker`，不更新 session 的 filledLayers。
- **後果：**
  `session.getFilledLayers()` 永遠回傳 0，無法正確判斷還有多少層未成交。
- **修復方案：**
  在 `MartingaleFillListener` 中，當某層完全成交（status=FILLED）時，呼叫 `sessionManager.getActiveSession(symbol)` → `session.markFilledLayer()`。
- **驗收條件：**
  - 每層完全成交後 `filledLayers` 正確遞增
  - `filledLayers == plannedLayers` 時可做為「全部成交」的判斷依據

---

## Phase 2D — 策略優化（Optional / Future Enhancement）

> 提升策略盈利能力與適應性。

### 2D-1 ATR 自適應間距

- [ ] **狀態：未開始**
- **類型：** 策略優化
- **問題描述：**
  固定 2% 間距在不同幣種和市場條件下表現差異極大。BTC 的 2% ≈ $1,200，但山寨幣 2% 可能在一根 K 線內被穿透，導致多層瞬間成交。
- **方案：**
  `stepPercent` 改為動態計算：`baseStepPercent × (currentATR / referenceATR)`。在 `MarketIndicatorService` 新增 ATR 計算，高波動時自動拉開間距。

---

### 2D-2 幣種級別參數 Profile

- [ ] **狀態：未開始**
- **類型：** 策略優化
- **問題描述：**
  所有幣種共用同一組參數（stepPercent、maxLayers、sizeMultiplier）。BTC 和山寨幣的波動特性完全不同。
- **方案：**
  `MartingaleStrategyConfig` 支援 per-symbol override，例如：
  ```yaml
  trading.strategy.martingale:
    default:
      step-percent: 0.02
      max-layers: 5
    overrides:
      BTCUSDT:
        step-percent: 0.01
        max-layers: 7
      DOGEUSDT:
        step-percent: 0.04
        max-layers: 3
  ```

---

### 2D-3 追加層機制

- [ ] **狀態：未開始**
- **類型：** 策略增強
- **問題描述：**
  當所有計劃層都成交後，如果價格繼續逆向移動，策略無法追加新層。馬丁策略的核心優勢（持續加碼攤平均價）在此失效。
- **方案：**
  當 `filledLayers == plannedLayers` 且價格繼續逆向移動時，在風控允許的前提下追加新層（需修改 session 模型支援動態增加 plannedLayers）。

---

### 2D-4 Session 持久化到 DB

- [ ] **狀態：未開始**
- **類型：** 架構改善
- **問題描述：**
  純記憶體狀態無法跨節點共享，也無法在重啟時完整恢復。Phase 2B-3 的復原機制是從 Binance 反推狀態，有資訊損失。
- **方案：**
  `MartingaleSession` 和 `LayerFillTracker` 關鍵狀態寫入 PostgreSQL。Session 狀態變更時同步更新 DB，啟動時從 DB 恢復。

---

### 2D-5 回測框架

- [ ] **狀態：未開始**
- **類型：** 工具
- **問題描述：**
  目前無法在歷史資料上驗證策略參數效果，參數調整只能靠實盤試錯。
- **方案：**
  獨立的 `MartingaleBacktester`，讀取 Binance 歷史 K 線，模擬分層入場、TP、止損邏輯，產出 PnL 報告和關鍵指標（勝率、最大回撤、平均持倉時間等）。

---

### 2D-6 多用戶模式下事件分發

- [ ] **狀態：未開始**
- **類型：** 功能缺口
- **影響檔案：** `MultiUserDataStreamManager.java`（觀察，不修改核心）、`UserDataEventDispatcher.java`
- **問題描述：**
  BinanceUserDataStreamService.java:291-293 只在 `!multiUserConfig.isEnabled()` 時才 dispatch 事件到 `UserDataEventDispatcher`。多用戶模式下 `MartingaleFillListener` 完全不會收到事件。
- **方案：**
  在不修改 `MultiUserDataStreamManager` 核心邏輯的前提下，研究如何讓多用戶模式也能分發事件給 observer（例如在 multi-user WebSocket callback 中加入 dispatch 呼叫）。
- **備註：**
  此項涉及核心系統邊界，需謹慎評估是否符合隔離原則。

---

## 附錄：快速索引

| 編號 | 標題 | 優先級 | 類型 |
|------|------|--------|------|
| 2B-1 | 主動止損監控 | Critical | 功能缺失 |
| 2B-2 | 動態 TP 更新 | Critical | 邏輯缺陷 |
| 2B-3 | 服務重啟復原 | Critical | 功能缺失 |
| 2B-4 | 趨勢過濾方向修正 | Critical | 邏輯缺陷 |
| 2C-1 | Drawdown 納入浮虧 | Important | 風控缺陷 |
| 2C-2 | 全域 Session 上限 | Important | 風控缺失 |
| 2C-3 | 送單失敗處理 | Important | 異常處理 |
| 2C-4 | CLOSE 流程原子性 | Important | 異常處理 |
| 2C-5 | 通知機制整合 | Important | 功能缺失 |
| 2C-6 | filledLayers 未更新 | Important | 邏輯缺陷 |
| 2D-1 | ATR 自適應間距 | Optional | 策略優化 |
| 2D-2 | 幣種級別參數 | Optional | 策略優化 |
| 2D-3 | 追加層機制 | Optional | 策略增強 |
| 2D-4 | Session 持久化 | Optional | 架構改善 |
| 2D-5 | 回測框架 | Optional | 工具 |
| 2D-6 | 多用戶事件分發 | Optional | 功能缺口 |
