# Martingale v4 參數優化

> 2026-04-08 模擬分析結論。Config 預設值已套用 v4，SL 距離門檻待下一版實作。

---

## v1 → v4 變更記錄

| 參數 | v1（舊） | v4（新） | 修改原因 |
|------|---------|---------|----------|
| maxLayers | 5 | **4** | 5 層 2x = 31 倍基礎曝險，4 層 1.4x = 7.1 倍，風險降 77% |
| stepPercent | 0.02 (2%) | **0.012 (1.2%)** | 2% 層距太大，1.2% 貼近 BTC 1-4 小時正常波動，自然觸發加碼 |
| sizeMultiplier | 2.0 | **1.4** | 2x 指數增長導致深層佔比過大（L5 佔 51.6%），1.4x 溫和攤平 |
| takeProfitPercent | 0.01 (1%) | **0.015 (1.5%)** | 馬丁靈魂是小反彈收割，1.5% 平衡獲利與觸發難度 |
| stopLossPercent | 0.15 (15%) | **0.055 (5.5%)** | 15% SL 必經所有層，虧損最大化；5.5% 作為 fallback，通常靠訊號 SL |
| breakevenTriggerPercent | 0.008 (0.8%) | **0.012 (1.2%)** | 配合新 TP 1.5%，觸發門檻 = TP 的 80% |
| breakevenOffsetPercent | 0.002 (0.2%) | **0.004 (0.4%)** | 保本偏移加大，避免手續費吃掉微利 |
| atrReferencePercent | 0.02 (2%) | **0.012 (1.2%)** | 與新 stepPercent 一致，ATR 參考基準 |
| tpDecayFloorPercent | 0.002 (0.2%) | **0.004 (0.4%)** | 衰減地板配合新 TP%，不低於保本偏移 |

未變更：baseSize(100)、maxCapitalUsage(0.30)、maxPositionSize(10000)、sessionMaxDurationMinutes(480)、entryIdleTimeoutMinutes(60)、maxConcurrentSessions(3)、tpDecayStartMinutes(120)、tpDecayIntervalMinutes(60)

---

## v4 設計哲學

```
馬丁的價值不是提高 R:R，而是提升勝率。

單筆進場跌 3% 後需反彈 4.5% 才到 TP（勝率 ~35%）
馬丁攤平後只需反彈 2.7%（勝率 ~60%，提升 +25%）

所以：
1. TP 要小（1.5%）→ 反彈門檻低 → 勝率高
2. 層距貼近波動（1.2%）→ 跟著正常波動攤平
3. 倍數溫和（1.4x）→ 攤平效果夠但不爆倉
4. SL 靠訊號動態調整 → 自動控制層數和風險
```

---

## 效果對比

### R:R 改善

| | v1 | v4 |
|---|---|---|
| 全層 TP 獲利 | $31 | $10.66 |
| 全層 SL 虧損 | $465 | $19.74 |
| **R:R** | **0.07** | **0.54** |
| 1 次 SL = 幾次 TP | 15 次 | **1.9 次** |
| 最大資金需求 | $3,100 | **$710** |

### 勝率提升（v4 馬丁 vs 單筆進場）

| 跌幅 | 填入層 | 馬丁需反彈 | 單筆需反彈 | 勝率提升 |
|------|--------|-----------|-----------|---------|
| 1.5% | L1-L2 | 2.32% | 3.05% | **+16%** |
| 2.5% | L1-L3 | 2.58% | 4.10% | **+25%** |
| 3.0% | L1-L3 | 3.10% | 4.64% | **+20%** |
| 4.0% | L1-L4 | 3.31% | 5.73% | **+20%** |

### EV 與 Signal SL 距離

| Signal SL 距離 | 動態層數 | R:R | EV/筆 |
|---------------|---------|-----|-------|
| 3% (窄) | 2 | 0.65 | **+$0.83** |
| 5% (中) | 4 | 0.54 | -$5.08 |
| 8% (寬) | 4 | 0.26 | -$16.38 |

**關鍵發現**：馬丁只在窄 SL（3-4%）時 EV 為正。

---

## 待實作：SL 距離門檻機制

目前 `computeDynamicMaxLayers()` 只根據 SL 距離算層數，不會拒絕啟用馬丁。
需要新增邏輯：SL 太遠時降低層數或改為單筆進場。

### 建議規則

```
Signal SL ≤ 4%  → 正常馬丁（動態層數 2-3 層，EV 為正）
Signal SL 4-6%  → 馬丁但層數上限 2（控制曝險）
Signal SL > 6%  → 不啟用馬丁，單筆進場
無 Signal SL     → 使用 config fallback SL 5.5%，正常馬丁
```

### 需新增的 Config 參數

```yaml
trading.strategy.martingale:
  martingale-max-sl-percent: 0.06        # SL > 6% 不啟用馬丁
  martingale-layer-cap-sl-percent: 0.04  # SL > 4% 時層數硬上限
  martingale-layer-cap: 2                # 上述硬上限值
```

### 修改位置

- `MartingaleStrategyConfig.java` → 新增 3 個參數欄位
- `MartingaleStrategy.java` → `computeDynamicMaxLayers()` 加入 SL 距離判斷
- `MartingaleDecisionEngine.java` → AUTO 模式可整合此邏輯
