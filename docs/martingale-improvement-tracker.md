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

- [x] **狀態：已完成（2026-03-21）**
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

- [x] **狀態：已完成（2026-03-21）**
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

- [x] **狀態：已完成（2026-03-21）**
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

- [x] **狀態：已完成（2026-03-21）**
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

- [x] **狀態：已完成（2026-03-21）**
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

- [x] **狀態：已完成（2026-03-21）**
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

## Phase 2F — 已知限制與後續優化方向（Known Limitations & Roadmap）

> 基於 2026-03-21 端到端系統審查產出。每項記錄問題本質與建議修復方向，供後續逐一排期。

---

### 2F-1 狀態無持久化 — 重啟遺失所有 Session

- [x] **狀態：已完成（2026-03-22）**
- **嚴重度：** 高
- **類型：** 架構限制
- **影響檔案：** 新增 `MartingaleStateStore.java`；修改 `MartingaleStrategy.java`、`MartingaleStopLossWatcher.java`、`MartingaleTpManager.java`、`MartingaleFillListener.java`、`LayerFillTracker.java`
- **問題描述：**
  Session、LayerFillTracker、breakevenActivated 等所有運行狀態存在 ConcurrentHashMap / AtomicInteger 中。
  應用重啟 = 全部狀態遺失，但 Binance 端的掛單和持倉仍然存在。
  Phase 2B-3 的 RecoveryTask 能從 Binance 反推部分狀態，但無法恢復：
  - 各層成交的加權均價（LayerFillTracker 資料）
  - 保本是否已觸發（breakevenActivated）
  - Session 的原始計劃層數
- **實際影響：**
  - SL Watcher 重啟後用 baseEntryPrice 而非正確的加權均價 → SL 位置偏差
  - 保本機制重啟後重置 → 可能重複觸發或遺漏
- **修復方向：**
  1. **方案 A（推薦）：Redis 快取**
     - Session + LayerFillTracker 寫入 Redis（JSON 序列化）
     - 每次狀態變更同步寫入，啟動時從 Redis 恢復
     - 優點：低延遲、現有 Redis 基礎設施可復用
     - 缺點：Redis 本身重啟也會遺失（可用 RDB/AOF 緩解）
  2. **方案 B：PostgreSQL 持久化**
     - 新增 `martingale_session` 和 `martingale_layer_fill` 表
     - 狀態變更寫入 DB，啟動時查詢恢復
     - 優點：持久可靠、可做歷史分析
     - 缺點：寫入延遲較高，需考慮高頻更新場景
  3. **建議優先級：** 與 2E-1 合併實作，先用 Redis 做熱快取 + DB 做冷持久化
- **關聯項目：** 2E-1

---

### 2F-2 EMA 趨勢過濾不適用於加密貨幣市場

- [x] **狀態：已完成（2026-03-21）**
- **嚴重度：** 高
- **類型：** 風控邏輯缺陷
- **影響檔案：** `RiskManager.java`、`MarketIndicatorService.java`
- **問題描述：**
  目前使用 EMA50/EMA200 黃金交叉/死亡交叉作為趨勢過濾。這在傳統股票市場有一定參考價值，但在加密貨幣市場存在嚴重問題：

  **1. 嚴重滯後性：**
  EMA200 需要 200 根 K 線才能穩定。在 1 分鐘 K 線下，黃金交叉形成時行情往往已走完一大半。
  BTC 常見的 V 型反轉（閃崩後快速恢復）中，EMA 交叉信號會在反彈結束後才出現。

  **2. 24/7 市場特性：**
  加密貨幣無休市，不像股票有開盤/收盤的均值回歸效應。EMA 在週末低流動性期間容易產生假信號。

  **3. 敘事驅動而非技術驅動：**
  加密貨幣價格高度受新聞、監管政策、鏈上事件影響。EMA 交叉無法反映這些外部因素。

  **4. 馬丁策略的矛盾：**
  馬丁策略本質是「逆勢加倉 → 等待均值回歸」，但 EMA 過濾要求「順勢才開倉」。
  這意味著：趨勢明確向下時（EMA 死亡交叉），系統拒絕 LONG 馬丁，但這恰恰是馬丁策略最有機會的場景（超跌反彈）。

- **修復方向（多指標風控體系）：**

  **取代 EMA 的候選指標（依加密貨幣適用性排序）：**

  | 指標 | 適用原因 | 實作難度 | 數據來源 |
  |------|---------|---------|---------|
  | **Funding Rate** | 永續合約獨有指標，直接反映多空情緒。極端正值 = 過度看多（做空機會），極端負值 = 過度看空（做多機會）。與馬丁逆勢邏輯天然契合 | 低 | Binance `/fapi/v1/fundingRate` |
  | **Open Interest 變化** | OI 急增 + 價格下跌 = 大量空頭開倉（可能超賣），OI 急減 = 平倉潮（趨勢可能反轉）。能捕捉 EMA 無法反映的倉位變化 | 低 | Binance `/fapi/v1/openInterest` |
  | **RSI (14)** | 超買超賣判斷。RSI < 30 做多馬丁、RSI > 70 做空馬丁，與均值回歸策略邏輯一致 | 低 | 現有 K 線計算 |
  | **Bollinger Band %B** | 價格在布林帶下軌以下 = 統計上的超賣。用 %B 替代 EMA 判斷入場時機 | 中 | 現有 K 線計算 |
  | **清算熱力圖 / 大額清算** | 大量清算後通常伴隨反彈（流動性缺口被填補）。作為馬丁入場的加分條件 | 高 | 第三方 API (Coinglass) |
  | **成交量異常** | 恐慌性拋售（量能激增 + 價格暴跌）後的反彈概率高。作為馬丁入場的確認信號 | 中 | 現有 K 線計算 |

  **建議實作方案：**
  ```
  RiskManager v2 — 多因子評分制（取代二元 EMA 過濾）

  分數 = fundingRateScore + oiScore + rsiScore + volatilityScore
  閾值 = configurable（建議 60/100 以上才允許）

  fundingRateScore (0~30):
    LONG: funding < -0.01% → 30分, [-0.01%, 0] → 15分, > 0 → 0分
    SHORT: funding > 0.03% → 30分, [0, 0.03%] → 15分, < 0 → 0分

  oiScore (0~20):
    近 4 小時 OI 變化 > 5% 且方向與入場方向相反 → 20分
    OI 平穩 → 10分
    OI 與入場方向同向 → 0分

  rsiScore (0~30):
    LONG: RSI < 25 → 30分, [25, 35] → 20分, [35, 50] → 10分, > 50 → 0分
    SHORT: RSI > 75 → 30分, [65, 75] → 20分, [50, 65] → 10分, < 50 → 0分

  volatilityScore (0~20):
    ATR% 在 [1x, 2x] 參考值 → 20分（適度波動最佳）
    ATR% < 0.5x → 5分（太平靜，馬丁難成交）
    ATR% > 3x → 5分（太劇烈，風險過高）
  ```

  **實作步驟：**
  1. `MarketIndicatorService` 新增 `getFundingRate()`、`getOpenInterest()`、`getRSI()` 方法
  2. 新增 `RiskScoreCalculator`，取代 `RiskManager` 中的 EMA 邏輯
  3. Config 新增各指標權重和閾值
  4. 保留 EMA 作為可選的額外過濾（`emaFilterEnabled: false` 預設關閉）

---

### 2F-3 單幣種單 Session 限制

- [x] **狀態：已完成（2026-03-22）**
- **嚴重度：** 中
- **類型：** 策略限制
- **影響檔案：** `MartingaleStrategy.java`
- **問題描述：**
  同一幣種同時只能有一個 Martingale session。當 session 在第 3 層等待回歸時，新的入場訊號會被直接丟棄。
  在高波動市場中，這可能錯過更好的入場點。
- **實際影響：**
  - 行情持續下跌 → Layer 3 成交後盤整 → 新訊號指出更低的入場點 → 被拒絕
  - 無法利用新訊號動態調整尚未成交的掛單
- **修復方向：**
  1. **方案 A（推薦）：動態調整未成交掛單**
     - 收到新訊號時，不開新 session，而是：
     - 取消尚未成交的 ENTRY 掛單
     - 以新訊號價格重新計算並掛出剩餘層
     - 保留已成交層的狀態不變
  2. **方案 B：允許同幣種多 Session**
     - 每個 session 獨立追蹤，但共享倉位上限
     - 複雜度高，容易導致 TP/SL 互相干擾
  3. **建議優先級：** 方案 A 較安全，先實作 A

---

### 2F-4 TP 更新窗口風險

- [x] **狀態：已完成（2026-03-21）**
- **嚴重度：** 中
- **類型：** 競爭條件
- **影響檔案：** `MartingaleTpManager.java`
- **問題描述：**
  Layer 成交 → 取消舊 TP → 掛新 TP 之間存在數百毫秒窗口。
  此窗口內無 TP 保護。若價格在此期間觸及 TP 位置，會錯過獲利機會。
- **實際影響：**
  極端快速行情（如 BTC 瞬間拉升 2%）中，可能錯過 TP 出場。
  SL Watcher（5 秒間隔）提供兜底保護，但無法捕捉毫秒級的價格觸及。
- **修復方向：**
  1. **方案 A（推薦）：先掛新 TP 再取消舊 TP**
     - 短暫同時存在兩張 TP，但保證不存在無保護窗口
     - 需處理 Binance 可能拒絕重複 TP 的情況
  2. **方案 B：使用 Binance 的 contingent order**
     - 利用 Binance 的 OCO / conditional order 機制
     - 取消和掛單原子化
  3. **方案 C：接受風險，加強 SL Watcher 頻率**
     - SL Watcher 頻率從 5 秒改為 1 秒
     - 加入「若無 TP 掛單且有持倉，立即補掛」的邏輯
  4. **建議優先級：** 方案 A 最可行

---

### 2F-5 無 Trailing Stop（追蹤止盈）

- [x] **狀態：已完成（2026-03-21）**
- **嚴重度：** 中
- **類型：** 策略限制
- **影響檔案：** `MartingaleStopLossWatcher.java`、`MartingaleTpManager.java`
- **問題描述：**
  保本機制（breakeven）只觸發一次，觸發後 TP 固定在 breakeven 位置。
  若價格持續上漲（例如 BTC 從均價 58,000 漲到 62,000），系統仍然在 58,116（breakeven + 0.2%）出場。
  大行情下只能獲得微利，完全無法捕捉趨勢行情的超額收益。
- **實際影響：**
  - 馬丁策略的勝率應該靠「小賺多次」累積，但目前每次只能賺 1%
  - 趨勢行情時 1% 的 TP 與潛在的 15% SL 不成比例
- **修復方向：**
  1. **方案 A（推薦）：階梯式 Trailing TP**
     ```
     階段 1: 價格達 avgPrice × 1.008 → TP 移至 avgPrice × 1.002（保本）
     階段 2: 價格達 avgPrice × 1.015 → TP 移至 avgPrice × 1.008（鎖定 0.8%）
     階段 3: 價格達 avgPrice × 1.025 → TP 移至 avgPrice × 1.015（鎖定 1.5%）
     階段 4: 價格達 avgPrice × 1.040 → TP 移至 avgPrice × 1.025（鎖定 2.5%）
     ```
     - 每個階段觸發後不可回退，持續追蹤上漲
     - 漲越多鎖利越多，同時容許合理回調空間
  2. **方案 B：百分比 Trailing Stop**
     - 價格超過 breakeven 後，TP = 最高價 × (1 - trailingPercent)
     - 簡單但在震盪市容易被掃出
  3. **建議優先級：** 方案 A（階梯式）更穩健，不易被假突破掃出

---

### 2F-6 固定層數與倍率

- [x] **狀態：已完成（2026-03-22）**
- **嚴重度：** 低
- **類型：** 策略限制
- **影響檔案：** `MartingaleStrategyConfig.java`、`MartingaleStrategy.java`、`MartingaleStopLossWatcher.java`、`MartingaleTpManager.java`、`MartingaleRecoveryTask.java`
- **問題描述：**
  `maxLayers=5` 和 `sizeMultiplier=2.0` 是全域設定，所有幣種共用。
  但不同幣種的波動性、流動性、價格區間差異極大：
  - BTC：日振幅 2-5%，流動性極佳 → 5 層 2x 適合
  - 山寨幣：日振幅 10-20%，可能需要更多層、更寬間距
  - 穩定幣對：日振幅 < 0.5%，5 層完全多餘
- **修復方向：**
  1. **方案 A（推薦）：per-symbol 配置覆寫**
     - Config 新增 `symbol-overrides` map
     ```yaml
     trading.strategy.martingale:
       max-layers: 5           # 全域預設
       size-multiplier: 2.0
       symbol-overrides:
         ETHUSDT:
           max-layers: 7
           size-multiplier: 1.5
           step-percent: 0.03
         SOLUSDT:
           max-layers: 3
           step-percent: 0.05
     ```
     - `MartingaleStrategy` 在 `execute()` 時查詢 symbol-specific 配置，無則用全域預設
  2. **方案 B：基於 ATR 動態調整層數**
     - ATR 已用於調整層距，可擴展到層數：ATR% > 3% → 減少至 3 層，ATR% < 1% → 增至 7 層
  3. **建議優先級：** 方案 A 先做（明確可控），方案 B 後續疊加

---

### 2F-7 SHORT 方向未充分驗證

- [x] **狀態：已完成（2026-03-21）**
- **嚴重度：** 低
- **類型：** 測試覆蓋不足
- **影響檔案：** `MartingaleStrategyTest.java`、`MartingaleStopLossWatcher.java`
- **問題描述：**
  所有單元測試和情境分析均基於 LONG 方向。SHORT 的邏輯（層價格向上、SL 向上、TP 向下）理論上由程式碼中的 `side == LONG ? ... : ...` 處理，但缺乏專門測試。
  - `buildLayerPrices` 的 SHORT 路徑（`1 + effectiveStep`）未測試
  - `isGlobalStopLossTriggered` 的 SHORT 路徑未測試
  - `buildOrders` 的 SHORT TP 計算（`1 - tpPercent`）未測試
  - 保本機制在 SHORT 方向的觸發邏輯未測試
- **修復方向：**
  1. 為 `MartingaleStrategyTest` 新增完整的 SHORT 測試套件（mirror 現有 LONG 測試）
  2. `MartingaleStopLossWatcher` 加入 SHORT 方向的 SL 和 breakeven 測試
  3. 端對端測試：SHORT 訊號 → 層級建立 → 成交 → TP 動態更新 → 出場

---

### 2F-8 無動態出場策略

- [x] **狀態：已完成（2026-03-22）**
- **嚴重度：** 低
- **類型：** 策略限制
- **影響檔案：** `MartingaleStopLossWatcher.java`、`MartingaleSession.java`、`MartingaleStrategyConfig.java`、`MartingaleNotifier.java`
- **問題描述：**
  TP 只有固定百分比（1%）和保本兩種出場方式。無法根據：
  - 技術面支撐/阻力位
  - 成交量變化
  - 持倉時間長短
  - 市場結構（區間震盪 vs 趨勢行情）
  來動態調整出場策略。
- **實際影響：**
  - 震盪市中 1% TP 可能合適
  - 趨勢行情中 1% TP 過早出場，錯失大幅獲利
  - 低波動盤整中 1% TP 可能數小時都不會觸及
- **修復方向：**
  1. **與 2F-5 (Trailing Stop) 結合**
     - Trailing 處理趨勢行情，固定 TP 處理震盪行情
  2. **基於持倉時間的 TP 衰減**
     - 持倉超過 N 小時後，逐步降低 TP 目標（避免長時間浮虧）
     ```
     0~2h:  TP = avgPrice × 1.01（正常）
     2~4h:  TP = avgPrice × 1.005（降低預期）
     4~6h:  TP = avgPrice × 1.002（接近保本）
     6h+:   TP = avgPrice × 1.0（保本出場）
     ```
  3. **建議優先級：** 先完成 2F-5 (Trailing)，再疊加時間衰減

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
| 2D-1 | TP 成交偵測 + 清理 | P0 致命 | ✅ 完成 | 功能缺失 |
| 2D-2 | 初始 TP 數量修正 | P1 高 | ✅ 完成 | 邏輯缺陷 |
| 2D-3 | Session 超時重新設計 | P2 中 | ✅ 完成 | 策略邏輯 |
| 2D-4 | SL 改用加權均價 | P2 中 | ✅ 完成 | 風控邏輯 |
| 2D-5 | 保本移動 TP | P3 低 | ✅ 完成 | 策略增強 |
| 2D-6 | ATR 自適應層距 | P3 低 | ✅ 完成 | 策略優化 |
| 2E-1 | Session 持久化到 DB | Future | 未開始 | 架構改善 |
| 2E-2 | 回測框架 | Future | 未開始 | 工具 |
| 2E-3 | 多用戶事件分發 | Future | 未開始 | 功能缺口 |
| 2F-1 | 狀態無持久化 | 高 | 未開始 | 架構限制 |
| 2F-2 | EMA 不適用加密貨幣 | 高 | ✅ 完成 | 風控缺陷 |
| 2F-3 | 單幣種單 Session 限制 | 中 | 未開始 | 策略限制 |
| 2F-4 | TP 更新窗口風險 | 中 | ✅ 完成 | 競爭條件 |
| 2F-5 | 無 Trailing Stop | 中 | ✅ 完成 | 策略限制 |
| 2F-6 | 固定層數與倍率 | 低 | 未開始 | 策略限制 |
| 2F-7 | SHORT 方向未充分驗證 | 低 | ✅ 完成 | 測試覆蓋 |
| 2F-8 | 無動態出場策略 | 低 | 未開始 | 策略限制 |
