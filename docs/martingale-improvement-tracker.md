# Martingale 策略模組 — 缺陷與改善追蹤

> 基於 2026-03-20 深度架構審查產出，涵蓋架構、策略邏輯、風控、狀態一致性、真實場景驗證。
> 每項完成後勾選 checkbox 並註明完成日期。

---

## Phase 2B — 關鍵修復（Critical / Must Fix）

> 未修復前不應進入實盤測試。

### 2B-1 主動止損監控

- [x] **狀態：已完成（2026-03-20）**
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

- [x] **狀態：已完成（2026-03-20）**
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

- [x] **狀態：已完成（2026-03-20）**
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

- [x] **狀態：已完成（2026-03-20）**
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

- [x] **狀態：已完成（2026-03-21）**
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

- [x] **狀態：已完成（2026-03-21）**
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

- [x] **狀態：已完成（2026-03-21）**
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

- [x] **狀態：已完成（2026-03-21）**
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

- [x] **狀態：已完成（2026-03-21）**
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

- [x] **狀態：已完成（2026-03-21）**
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

## Phase 2D — 策略優化與實盤安全（Real-World Trading Safety & Optimization）

> 基於 2026-03-21 實際交易面深度分析，重新排序。
> P0 = 不修會直接虧錢；P1 = 短暫窗口風險；P2 = 違反策略核心邏輯；P3 = 策略效能優化。

### 2D-1 TP 成交偵測 + Session / 掛單清理（P0 — 會直接虧錢）

- [ ] **狀態：待開發**
- **類型：** 功能缺失（致命）
- **影響檔案：** `MartingaleFillListener.java`、`MartingaleSessionManager.java`、`OrderExecutor.java`
- **問題描述：**
  當 TP Algo 單在 Binance 被觸發成交後，系統沒有任何元件偵測此事件：
  - `MartingaleFillListener` 只追蹤已註冊的 ENTRY orderId，**不認識 TP 的成交事件**
  - Session 仍為 `ACTIVE` 狀態
  - 未成交的 ENTRY LIMIT 掛單仍掛在 Binance 上
  - 唯一安全網是 30 分鐘 idle timeout，期間殘留掛單可能成交
- **風險場景：**
  LONG 5 層，Layer 1-2 成交，TP 觸發平倉獲利。但 Layer 3-5 的 LIMIT 掛單仍在 Binance。
  價格繼續下跌 → Layer 3 成交 → **產生無 TP / 無 SL 的裸倉位** → 持續虧損直到 idle timeout。
- **修復方案：**
  在 `MartingaleFillListener` 中增加 TP 成交偵測：
  1. 比對 `session.getCurrentTpOrderId()` 與成交事件中的 orderId
  2. 或偵測「非 ENTRY 的 REDUCE_ONLY 成交」（TP 觸發的市價單會帶有 `reduceOnly=true`）
  3. 偵測到 TP 成交後：`cancelAllOrders(symbol)` → `endSession` → `clearSymbol` → 通知
- **驗收條件：**
  - TP 成交後 session 自動結束
  - TP 成交後所有殘留 ENTRY 掛單被取消
  - 不會產生無保護的裸倉位
  - 有對應單元測試

---

### 2D-2 初始 TP 數量修正（P1 — 競爭窗口風險）

- [ ] **狀態：待開發**
- **類型：** 邏輯缺陷
- **影響檔案：** `MartingaleStrategy.java`（`buildOrders` 方法）
- **問題描述：**
  `buildOrders()` 將初始 TP 單的數量設為**所有計劃層的 totalQty**。但 Layer 1 的 LIMIT 價格 = 當前市場價，會立即成交（等同市價單）。
  在 Layer 1 成交 → WebSocket fill event → 動態 TP 更新 的數百毫秒窗口內：
  - TP 數量 = 5 層總量，實際倉位 = Layer 1 的量
  - 若此時 TP 被觸發 → Binance 嘗試賣出超過持倉的數量 → 可能產生反向倉位或被拒絕
- **修復方案：**
  初始 TP 的數量和價格只用 Layer 1（第一層必定成交的那層）來計算，而非全部計劃層。
  後續層成交時，由 `MartingaleTpManager.updateTakeProfit()` 動態修正。
- **驗收條件：**
  - 初始 TP 單的數量 = Layer 1 的 quantity
  - 初始 TP 單的價格 = Layer 1 price × (1 ± takeProfitPercent)
  - 後續層成交後 TP 正確更新為累計量和加權均價

---

### 2D-3 Session 超時機制重新設計（P2 — 違反馬丁核心邏輯）

- [ ] **狀態：待開發**
- **類型：** 策略邏輯缺陷
- **影響檔案：** `MartingaleSessionCleanupTask.java`、`MartingaleStrategyConfig.java`
- **問題描述：**
  當前 idle timeout = 30 分鐘。馬丁策略的核心是**等待價格回歸均值**，如果 Layer 1-3 成交後價格盤整 31 分鐘，系統會在最虧的時候強制市價平倉，完全違反策略本質。
- **修復方案：**
  將 idle timeout 重新定義為**持倉總時間上限**（而非無成交時間），建議：
  - `sessionMaxDurationMinutes`：session 從建立到強制平倉的最大持續時間（建議 4~8 小時）
  - `layerIdleTimeoutMinutes`：最後一層成交後的等待時間（建議 120 分鐘）
  - 只有在沒有任何層成交（純掛單無人接）的情況下才用短超時取消掛單
- **驗收條件：**
  - 有成交的 session 不會在 30 分鐘內被強制平倉
  - 純掛單（0 層成交）的 session 仍會被合理超時清理
  - 持倉超過最大時間後強制平倉

---

### 2D-4 SL 改用加權均價為基準（P2 — 風險比不一致）

- [ ] **狀態：待開發**
- **類型：** 風控邏輯缺陷
- **影響檔案：** `MartingaleStopLossWatcher.java`
- **問題描述：**
  止損以 `baseEntryPrice`（Layer 1 價格）計算。但不同成交深度下，加權均價與 baseEntryPrice 差異顯著：
  - 只有 Layer 1 成交：均價 = baseEntryPrice → SL = -15%（符合預期）
  - 5 層全成交：均價 ≈ baseEntryPrice × 0.94 → SL 在均價下方僅 ~9.6%（風險比不一致）

  更重要的是：5 層全成交時倉位最大，但 SL 保護最弱（離均價更近）。
- **修復方案：**
  `MartingaleStopLossWatcher` 使用 `LayerFillTracker.getAggregatedFill()` 的加權均價計算 SL：
  - 有成交時：`SL = avgPrice × (1 - stopLossPercent)`
  - 無成交時（純掛單）：仍用 `baseEntryPrice`
- **驗收條件：**
  - 無論幾層成交，最大虧損比例一致（均為 stopLossPercent）
  - 每次新層成交後 SL 基準自動更新

---

### 2D-5 保本移動 TP — Breakeven Protection（P3 — 策略效能優化）

- [ ] **狀態：待開發**
- **類型：** 策略增強
- **影響檔案：** `MartingaleTpManager.java`、`MartingaleStrategyConfig.java`
- **問題描述：**
  多層成交後均價為 58,000，價格反彈到 59,800（接近 Layer 1 的 60,000），但 TP 在 58,580。
  如果價格未達 TP 即反轉 → 浮動利潤全部回吐，最終可能觸發 SL。
  這是實盤馬丁常見的「眼看要回本卻又虧回去」的痛點。
- **修復方案：**
  新增 breakeven 機制：當 markPrice 超過 `avgPrice × (1 + breakevenTriggerPercent)` 時，將 TP 移至 `avgPrice × (1 + breakevenOffsetPercent)`（微利出場）。
  ```yaml
  breakeven-trigger-percent: 0.008   # 價格回到均價上方 0.8% 時觸發
  breakeven-offset-percent: 0.002    # TP 移到均價上方 0.2%（保本 + 微利）
  ```
- **驗收條件：**
  - 價格回到觸發閾值時 TP 自動移到保本位
  - 保本 TP 被觸發後 session 正確清理
  - 不影響正常 TP 流程（價格直接到達原 TP 時仍正常出場）

---

### 2D-6 ATR 自適應層距（P3 — 策略效能優化）

- [ ] **狀態：待開發**
- **類型：** 策略優化
- **影響檔案：** `MartingaleStrategy.java`（`buildLayerPrices`）、`MarketIndicatorService.java`、`MartingaleStrategyConfig.java`
- **問題描述：**
  固定 2% 間距在不同幣種和市場條件下表現差異極大：
  - BTC 高波動時（日振幅 5%+）：2% 太窄，閃崩時所有層瞬間成交，失去分批建倉的意義
  - BTC 低波動時（日振幅 1%）：2% 太寬，Layer 2+ 永遠不會成交，資金效率低
  - 山寨幣 2% 可能在一根 K 線內被穿透
- **修復方案：**
  `stepPercent` 改為動態計算：`baseStepPercent × (currentATR / referenceATR)`。
  在 `MarketIndicatorService` 新增 ATR 計算，高波動時自動拉開間距，低波動時縮小。
- **驗收條件：**
  - 高波動市場層距自動拉開
  - 低波動市場層距自動縮小
  - 有配置項控制 ATR 計算週期和參考值

---

## Phase 2E — 架構與工具（Future Enhancement）

> 非交易邏輯層面的改善，可在實盤穩定後排期。

### 2E-1 Session 持久化到 DB

- [ ] **狀態：未開始**
- **類型：** 架構改善
- **問題描述：**
  純記憶體狀態無法跨節點共享，也無法在重啟時完整恢復。Phase 2B-3 的復原機制是從 Binance 反推狀態，有資訊損失。
- **方案：**
  `MartingaleSession` 和 `LayerFillTracker` 關鍵狀態寫入 PostgreSQL。Session 狀態變更時同步更新 DB，啟動時從 DB 恢復。

---

### 2E-2 回測框架

- [ ] **狀態：未開始**
- **類型：** 工具
- **問題描述：**
  目前無法在歷史資料上驗證策略參數效果，參數調整只能靠實盤試錯。
- **方案：**
  獨立的 `MartingaleBacktester`，讀取 Binance 歷史 K 線，模擬分層入場、TP、止損邏輯，產出 PnL 報告和關鍵指標（勝率、最大回撤、平均持倉時間等）。

---

### 2E-3 多用戶模式下事件分發

- [ ] **狀態：未開始**
- **類型：** 功能缺口
- **影響檔案：** `MultiUserDataStreamManager.java`（觀察，不修改核心）、`UserDataEventDispatcher.java`
- **問題描述：**
  BinanceUserDataStreamService.java:291-293 只在 `!multiUserConfig.isEnabled()` 時才 dispatch 事件到 `UserDataEventDispatcher`。多用戶模式下 `MartingaleFillListener` 完全不會收到事件。
- **方案：**
  在不修改 `MultiUserDataStreamManager` 核心邏輯的前提下，研究如何讓多用戶模式也能分發事件給 observer。
- **備註：**
  此項涉及核心系統邊界，需謹慎評估是否符合隔離原則。

---

## 附錄：快速索引

| 編號 | 標題 | 優先級 | 狀態 | 類型 |
|------|------|--------|------|------|
| 2B-1 | 主動止損監控 | Critical | ✅ 完成 | 功能缺失 |
| 2B-2 | 動態 TP 更新 | Critical | ✅ 完成 | 邏輯缺陷 |
| 2B-3 | 服務重啟復原 | Critical | ✅ 完成 | 功能缺失 |
| 2B-4 | 趨勢過濾方向修正 | Critical | ✅ 完成 | 邏輯缺陷 |
| 2C-1 | Drawdown 納入浮虧 | Important | ✅ 完成 | 風控缺陷 |
| 2C-2 | 全域 Session 上限 | Important | ✅ 完成 | 風控缺失 |
| 2C-3 | 送單失敗處理 | Important | ✅ 完成 | 異常處理 |
| 2C-4 | CLOSE 流程原子性 | Important | ✅ 完成 | 異常處理 |
| 2C-5 | 通知機制整合 | Important | ✅ 完成 | 功能缺失 |
| 2C-6 | filledLayers 未更新 | Important | ✅ 完成 | 邏輯缺陷 |
| 2D-1 | TP 成交偵測 + 清理 | P0 致命 | 待開發 | 功能缺失 |
| 2D-2 | 初始 TP 數量修正 | P1 高 | 待開發 | 邏輯缺陷 |
| 2D-3 | Session 超時重新設計 | P2 中 | 待開發 | 策略邏輯 |
| 2D-4 | SL 改用加權均價 | P2 中 | 待開發 | 風控邏輯 |
| 2D-5 | 保本移動 TP | P3 低 | 待開發 | 策略增強 |
| 2D-6 | ATR 自適應層距 | P3 低 | 待開發 | 策略優化 |
| 2E-1 | Session 持久化到 DB | Future | 未開始 | 架構改善 |
| 2E-2 | 回測框架 | Future | 未開始 | 工具 |
| 2E-3 | 多用戶事件分發 | Future | 未開始 | 功能缺口 |
