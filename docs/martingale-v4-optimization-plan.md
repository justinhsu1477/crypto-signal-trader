# Martingale v4 參數優化計劃

> 2026-04-07 模擬分析結論，待下一版實作。

---

## 現況問題（v1 配置）

| 參數 | v1 值 | 問題 |
|------|-------|------|
| maxLayers | 5 | 全層投入 $3,100（31x 基礎），曝險過大 |
| stepPercent | 2% | 層距太大，不夠貼近正常波動 |
| sizeMultiplier | 2.0x | 指數增長太激進 |
| takeProfitPercent | 1% | TP 合理但搭配 15% SL → R:R 只有 0.07 |
| stopLossPercent | 15% | 太寬，SL 必經所有層，虧損最大化 |

**結構性問題**：1 次全層 SL 虧損 = 15 次全層 TP 獲利，任何訊號品質下 EV 都是負的。

---

## v4 馬丁本色 — 建議參數

| 參數 | v4 值 | 設計理由 |
|------|-------|----------|
| maxLayers | **4** | 最深 -3.57%，覆蓋大部分假跌 |
| stepPercent | **1.2%** | 貼近 BTC 1-4 小時正常波動 |
| baseSize | 100 | 不變 |
| sizeMultiplier | **1.4x** | 溫和攤平，4 層合計 $710（vs v1 $3,100） |
| takeProfitPercent | **1.5%** | 小反彈就收割（馬丁靈魂） |
| stopLossPercent | **5.5%** | fallback，通常靠訊號 SL 動態限制 |
| breakevenTriggerPercent | **1.2%** | 配合新 TP% |
| breakevenOffsetPercent | **0.4%** | 保本微利 |
| tpDecayFloorPercent | **0.4%** | 衰減最低 |

---

## 核心新機制：SL 距離門檻

模擬發現**馬丁只在窄 SL（3-4%）時 EV 為正**，寬 SL 時多層成交反而放大虧損。

### 建議邏輯

```
Signal SL 距離 ≤ 4%  → 啟用馬丁（動態層數，通常 2-3 層）
Signal SL 距離 4-6%  → 啟用馬丁但硬上限 2 層（不讓動態層數用滿）
Signal SL 距離 > 6%  → 不啟用馬丁，單筆進場
無 Signal SL          → 使用 config fallback SL 5.5%，啟用馬丁
```

### 需要新增的 Config 參數

```yaml
trading.strategy.martingale:
  martingale-max-sl-percent: 0.06    # SL 距離 > 6% 不啟用馬丁
  martingale-layer-cap-sl-percent: 0.04  # SL 距離 > 4% 時層數上限 2
  martingale-layer-cap: 2            # 上述情況的層數硬上限
```

### 實作位置

- `MartingaleStrategy.java` → `computeDynamicMaxLayers()` 加入 SL 距離判斷
- `MartingaleDecisionEngine.java` → 可在 AUTO 模式下整合此邏輯
- `MartingaleStrategyConfig.java` → 新增 3 個參數

---

## 模擬數據支撐

### 馬丁核心優勢：降低反彈門檻

| 跌幅 | 填入層 | 馬丁需反彈 | 單筆需反彈 | 馬丁勝率 | 單筆勝率 | 勝率提升 |
|------|--------|-----------|-----------|---------|---------|---------|
| 1.5% | L1-L2 | 2.32% | 3.05% | 60% | 44% | **+16%** |
| 2.5% | L1-L3 | 2.58% | 4.10% | 53% | 29% | **+25%** |
| 3.0% | L1-L3 | 3.10% | 4.64% | 43% | 24% | **+20%** |
| 4.0% | L1-L4 | 3.31% | 5.73% | 40% | 20% | **+20%** |

### EV 與 SL 距離的關係

| Signal SL 距離 | 動態層數 | 投入資金 | TP 利潤 | SL 虧損 | R:R | EV/筆 |
|---------------|---------|---------|--------|--------|-----|-------|
| 3% (窄) | 2 | $240 | +$3.60 | -$5.55 | 0.65 | **+$0.83** |
| 5% (中) | 4 | $710 | +$10.66 | -$19.74 | 0.54 | -$5.08 |
| 8% (寬) | 4 | $710 | +$10.66 | -$41.55 | 0.26 | -$16.38 |

### v1 vs v4 對比

| | v1 目前 | v4 建議 |
|---|---|---|
| 最大曝險 | $3,100 | **$710** |
| 全層 R:R | 0.07 | **0.54** |
| 1 次 SL = 幾次 TP | 15 次 | **1.9 次** |
| 優質訊號 EV | -$9.52 | **+$0.83** (窄SL) |

---

## 實作優先序

1. **改 Config 預設值** → 套用 v4 參數
2. **新增 SL 距離門檻** → `computeDynamicMaxLayers()` 加入判斷
3. **新增 Config 參數** → 3 個新欄位
4. **調整 Trailing Stop 參數** → 配合新 TP/breakeven
5. **測試 + 模擬驗證**

