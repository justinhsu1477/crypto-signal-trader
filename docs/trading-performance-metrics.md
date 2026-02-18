# 交易績效評比指標完整說明

> API Endpoint: `GET /api/dashboard/performance?days=30`

---

## 一、基礎指標

### 1. 勝率 (Win Rate)
- **公式**: `獲利筆數 / 總交易筆數 × 100%`
- **意義**: 每 100 筆交易中有幾筆賺錢
- **參考值**: 40~60% 對趨勢跟單系統已算不錯；高於 70% 要注意是否風報比偏低
- **欄位**: `summary.winRate`

### 2. 利潤因子 (Profit Factor)
- **公式**: `毛利總和 / 毛損總和`
- **意義**: 每虧 1 元能賺幾元。大於 1 代表整體獲利
- **參考值**: > 1.5 穩健，> 2.0 優秀
- **欄位**: `summary.profitFactor`

### 3. 總淨利 (Total Net Profit)
- **公式**: `所有已平倉交易的 netProfit 總和`
- **意義**: 扣除手續費後的實際盈虧
- **欄位**: `summary.totalNetProfit` (USDT)

### 4. 平均每筆盈虧 (Average Profit per Trade)
- **公式**: `totalNetProfit / totalTrades`
- **意義**: 每筆交易平均賺或賠多少
- **欄位**: `summary.avgProfitPerTrade` (USDT)

### 5. 最大單筆獲利 / 虧損 (Max Win / Max Loss)
- **意義**: 單筆交易的極端值，用來評估尾部風險
- **欄位**: `summary.maxWin` / `summary.maxLoss`

### 6. 總手續費 (Total Commission)
- **公式**: `入場 maker 0.02% + 出場 taker 0.04%`
- **意義**: 手續費對總體績效的侵蝕
- **欄位**: `summary.totalCommission` (USDT)

---

## 二、進階風險指標

### 7. 平均獲利 / 平均虧損 (Avg Win / Avg Loss)
- **公式**: 分別對獲利交易和虧損交易取平均
- **意義**: 贏的時候平均賺多少，輸的時候平均賠多少
- **欄位**: `summary.avgWin` / `summary.avgLoss` (USDT)

### 8. 風報比 (Risk-Reward Ratio)
- **公式**: `|avgWin| / |avgLoss|`
- **意義**: 平均每次獲利是平均每次虧損的幾倍
- **參考值**: > 1.5 合理，> 2.0 優秀
- **關鍵認知**: 勝率低但風報比高的系統，長期一樣能賺錢
- **欄位**: `summary.riskRewardRatio`

### 9. 期望值 (Expectancy)
- **公式**: `(winRate × avgWin) - (lossRate × |avgLoss|)`
- **意義**: 每筆交易的數學期望盈利。正數 = 系統長期有正期望值
- **範例**: 勝率 60%, avgWin=200, avgLoss=100 → (0.6×200)-(0.4×100) = 80 USDT
- **欄位**: `summary.expectancy` (USDT)

### 10. 最大回撤 (Max Drawdown, MDD)
- **公式**: 從權益曲線的最高點到最低點的跌幅
- **意義**: 歷史上最糟糕的連續虧損有多大，是風控最重要的指標之一
- **三個維度**:
  - `summary.maxDrawdown` — 金額 (USDT)
  - `summary.maxDrawdownPercent` — 百分比 (%)
  - `summary.maxDrawdownDays` — 持續天數
- **參考值**: MDD% 控制在 20% 以內為佳

### 11. 最大連勝 / 連敗 (Max Consecutive Wins/Losses)
- **公式**: 按時間排序，計算最長連續獲利/虧損筆數
- **意義**: 評估心理承受力和系統穩定性
- **欄位**: `summary.maxConsecutiveWins` / `summary.maxConsecutiveLosses`

### 12. 平均持倉時間 (Avg Holding Hours)
- **公式**: `所有交易 (exitTime - entryTime) 的平均值`
- **意義**: 了解交易風格（短線 < 4h, 波段 4~48h, 中長線 > 48h）
- **欄位**: `summary.avgHoldingHours`

---

## 三、分組統計

### 13. 幣種別績效 (Symbol Stats)
- **分組**: 按交易對 (BTCUSDT, ETHUSDT, ...)
- **指標**: trades, wins, winRate, netProfit, avgProfit
- **排序**: 按 netProfit 降序
- **用途**: 找出最擅長 / 最不擅長的幣種
- **欄位**: `symbolStats[]`

### 14. 多空對比 (Side Comparison)
- **分組**: LONG vs SHORT
- **指標**: trades, wins, winRate, netProfit, avgProfit, profitFactor
- **用途**: 判斷做多和做空哪個更擅長，決定是否偏重某個方向
- **欄位**: `sideComparison.longStats` / `sideComparison.shortStats`

### 15. 出場原因分布 (Exit Reason Breakdown)
- **分類**: STOP_LOSS, SIGNAL_CLOSE, MANUAL_CLOSE, FAIL_SAFE
- **指標**: 各原因的次數
- **用途**: 如果 STOP_LOSS 佔比過高，可能訊號品質有問題
- **欄位**: `exitReasonBreakdown`

### 16. 訊號來源排名 (Signal Source Ranking)
- **分組**: 按訊號發送者（Discord 作者名稱）
- **指標**: trades, winRate, netProfit
- **排序**: 按 netProfit 降序
- **用途**: 評估各訊號來源品質，篩選優質訊號源
- **欄位**: `signalSourceRanking[]`

---

## 四、時間維度分析

### 17. 週統計 (Weekly Stats)
- **分組**: 按 ISO 週（週一起算）
- **指標**: weekStart, weekEnd, trades, netProfit, winRate
- **用途**: 觀察每週績效波動，找出表現好/差的週期
- **欄位**: `weeklyStats[]`

### 18. 月統計 (Monthly Stats)
- **分組**: 按月份 (yyyy-MM)
- **指標**: month, trades, netProfit, winRate
- **用途**: 長期趨勢觀察，月度績效追蹤
- **欄位**: `monthlyStats[]`

### 19. 星期幾績效 (Day of Week Stats)
- **分組**: MONDAY ~ SUNDAY（7 天全列，無交易的顯示 0）
- **指標**: trades, netProfit, winRate
- **用途**: 找出最佳/最差交易日，決定是否特定天不交易
- **欄位**: `dayOfWeekStats[]`

---

## 五、策略分析

### 20. DCA 補倉效果 (DCA Analysis)
- **分組**: 有補倉 (dcaCount > 0) vs 無補倉 (dcaCount = 0)
- **指標**: trades, winRate, avgProfit
- **用途**: 評估 DCA 策略是否真的改善績效
- **欄位**: `dcaAnalysis`

---

## 六、盈虧曲線 (PnL Curve)

每日資料點包含：
| 欄位 | 說明 |
|------|------|
| `date` | 日期 (yyyy-MM-dd) |
| `dailyPnl` | 當日淨利 (USDT) |
| `cumulativePnl` | 累計淨利 (USDT) |
| `drawdown` | 當日回撤金額 (USDT, 0 或負數) |
| `drawdownPercent` | 當日回撤百分比 (%, 0 或負數) |

- **用途**: 前端繪製權益曲線 + 回撤疊加圖
- **欄位**: `pnlCurve[]`

---

## 七、指標關係圖

```
                    ┌─────────────────┐
                    │   Win Rate (%)  │
                    └────────┬────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
      ┌───────────┐  ┌───────────┐  ┌───────────────┐
      │  Avg Win  │  │ Avg Loss  │  │ Profit Factor │
      └─────┬─────┘  └─────┬─────┘  └───────────────┘
            │              │
            ▼              ▼
      ┌─────────────────────────┐
      │    Risk-Reward Ratio    │
      └────────────┬────────────┘
                   │
                   ▼
          ┌──────────────┐
          │  Expectancy  │  ← 系統核心指標
          └──────────────┘
```

**解讀優先順序**:
1. **Expectancy > 0** — 系統有正期望值（最重要）
2. **Max Drawdown** — 能承受多大風險
3. **Profit Factor > 1.5** — 整體穩定獲利
4. **Win Rate + Risk-Reward** — 兩者搭配看，互補

---

## 八、常見問題

**Q: 勝率 40% 的系統可以賺錢嗎？**
A: 可以。只要風報比夠高（例如 3:1），勝率 40% 的期望值 = (0.4×3)-(0.6×1) = 0.6 > 0，長期有利。

**Q: 為什麼回撤百分比可能超過 100%？**
A: 當累計淨利從正轉負時（例如 peak=200, 目前=-100），drawdown=-300，百分比=-150%。

**Q: DCA 補倉一定比較好嗎？**
A: 不一定。DCA 降低平均入場價但增加風險敞口。dcaAnalysis 對比有/無補倉的勝率和平均獲利來客觀評估。

**Q: 為什麼星期六日也有交易？**
A: 加密貨幣 7×24 交易，不像傳統金融有休市日。
