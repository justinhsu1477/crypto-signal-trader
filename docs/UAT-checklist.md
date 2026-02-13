# UAT 測試清單 — Crypto Signal Trader

> 預計測試期：一週，使用小額測試帳戶（100-500 USDT）

---

## 第一階段：環境確認（Day 1）

### 基礎設施

| 項目 | 驗證方式 | 通過標準 |
|------|---------|---------|
| Binance API 權限 | `curl localhost:8080/api/balance` | 回傳 USDT 餘額，非 403/401 |
| API key 有 Futures 權限 | Binance 後台 → API Management | 確認勾了 "Enable Futures" |
| Discord CDP 連線 | 殺 Discord → `--remote-debugging-port=9222` 重啟 | Python monitor 日誌顯示 connected |
| Webhook 通知 | `curl -X POST $WEBHOOK_URL -d '{"content":"test"}'` | Discord 頻道收到訊息 |
| H2 資料庫 | `ls -la data/trading.mv.db` | 檔案存在且可讀寫 |
| 時區 | 檢查日誌時間戳 | UTC 時間，每日熔斷在 UTC 0:00 重置 |

### 建議 UAT 參數（保守起步）

```yaml
risk:
  max-position-usdt: 10000      # 先用小的，不要一上來就 50000
  max-daily-loss-usdt: 500      # 先設 500，觀察一週
  risk-percent: 0.05            # 5%，不是 20%
  max-positions: 1              # 只允許 1 個持倉
  fixed-leverage: 20
  dedup-enabled: true
  allowed-symbols:
    - BTCUSDT                   # 只開 BTC，別加其他的
```

穩定一週後再逐步放寬：`0.05 → 0.10 → 0.15 → 0.20`

---

## 第二階段：全流程測試（Day 2-3）

### ✅ 正常交易流程

| # | 測試案例 | 操作 | 預期結果 |
|---|---------|------|---------|
| 1 | ENTRY 開倉 | 發一則 📢 交易訊號發布 | Binance 開倉 + Discord 通知 + DB 紀錄 |
| 2 | MOVE_SL 移動止損 | 發 TP-SL 修改訊號 | 舊 SL 取消、新 SL 掛上 |
| 3 | CLOSE 平倉 | 發平倉訊號 | 平倉成功 + netProfit 含手續費 |
| 4 | CANCEL 取消 | 掛單後發 ⚠️ 掛單取消 | 掛單被取消、DB 狀態 = CANCELLED |

### 🔢 數量計算驗算

| # | 測試案例 | 操作 | 預期結果 |
|---|---------|------|---------|
| 5 | 以損定倉公式 | 手動算 `qty = (balance × 0.05) / |entry - SL|` | 對照 DB entryQuantity，誤差 < 0.001 |
| 6 | 名目上限 cap | 窄止損訊號（算出名目 > maxPositionUsdt） | 數量被 cap 到 maxPositionUsdt / entry |
| 7 | 保證金檢查 | 大倉位訊號 | 保證金 ≤ 餘額 × 90%，超過就縮量 |
| 8 | 最小名目值 | 極小餘額（< 50 USDT） | 名目 < 5 USDT 時拒絕 |

### 💰 手續費驗算

| # | 測試案例 | 操作 | 預期結果 |
|---|---------|------|---------|
| 9 | 入場手續費 | 開倉後查 DB | `entryCommission = entry × qty × 0.0002` |
| 10 | 總手續費 | 平倉後查 DB | `commission = entryCom + exit × qty × 0.0004` |
| 11 | 淨利計算 | 平倉後查 DB | `netProfit = grossProfit - commission` |

---

## 第三階段：風控攔截測試（Day 3-4）

### 🛡️ 防護機制

| # | 測試案例 | 操作 | 預期結果 |
|---|---------|------|---------|
| 12 | 重複訊號（5 分鐘內） | 同一訊號發兩次 | 第二次被擋：`重複訊號攔截` |
| 13 | 白名單攔截 | 發 ETHUSDT 訊號（不在白名單） | 拒絕：`交易對不在白名單` |
| 14 | 已有持倉 | OPEN 狀態下再發 ENTRY | 拒絕：`持倉數已達上限` |
| 15 | 已有掛單 | 有 LIMIT 掛單時再發 ENTRY | 拒絕：`已有未成交的入場掛單` |
| 16 | 價格偏離 | 入場價偏離市價 > 10% | 拒絕：`入場價偏離市價超過 10%` |
| 17 | 止損方向錯誤 | 做多但 SL > entry | 拒絕：`做多止損不應高於入場價` |
| 18 | 缺少止損 | ENTRY 訊號沒給 SL | 拒絕：`ENTRY 訊號必須包含 stop_loss` |
| 19 | 每日虧損熔斷 | 連虧到接近 maxDailyLossUsdt | 達上限後拒絕 + Discord 🚨 |
| 20 | 重複取消（30 秒內） | 同一幣種發兩次 CANCEL | 第二次被擋 |

---

## 第四階段：故障模擬（Day 4-5）

### 💥 異常場景

| # | 故障場景 | 模擬方式 | 預期結果 |
|---|---------|---------|---------|
| 21 | Binance API 斷線 | 斷網 → 發訊號 | 拒絕交易 + log ERROR，不會開空倉 |
| 22 | Discord CDP 斷線 | 關閉 Discord App | Python 持續重連，不 crash |
| 23 | 快速連發訊號 | 1 秒內 POST 5 次相同訊號 | 只有 1 次成功（symbolLock + dedup） |
| 24 | App 重啟後去重 | 重啟 Spring Boot → 5 分鐘內發相同訊號 | DB 層去重仍能擋住 |
| 25 | Webhook 失敗 | 用無效 webhook URL | 交易繼續執行，只是通知丟失 |
| 26 | Fail-Safe 觸發 | （觀察日誌）SL 掛失敗的情況 | 自動取消入場單 → Discord 告警 |

### 🔄 並發測試

```bash
# 同一訊號連發 5 次，驗證只成功 1 次
for i in {1..5}; do
  curl -s -X POST localhost:8080/api/execute-signal \
    -H "Content-Type: application/json" \
    -d '{"message":"📢 交易訊號發布: BTCUSDT\n做多 LONG 🟢\n入場價格 (Entry)\n95000\n止損價格 (SL)\n93000"}' &
done
wait
# 檢查：只有 1 筆 OPEN trade
curl localhost:8080/api/trades?status=OPEN
```

---

## 第五階段：持續監控（Day 5-7 + 上線後）

### 📋 每日必做檢查

```bash
# 1. 餘額 — 確認沒異常減少
curl localhost:8080/api/balance

# 2. 今日統計 — 關注虧損金額
curl localhost:8080/api/stats/summary

# 3. 持倉 — 跟 Binance App 對照
curl localhost:8080/api/positions

# 4. 交易紀錄 — 確認每筆都有記到
curl localhost:8080/api/trades?status=OPEN

# 5. 錯誤日誌
grep "ERROR\|CRITICAL\|Fail-Safe\|熔斷" logs/trading.log

# 6. 備份 DB（每天）
cp data/trading.mv.db data/trading.mv.db.backup-$(date +%Y%m%d)
```

### 🔴 緊急處理指南

| 日誌關鍵字 | 嚴重性 | 意思 | 處理方式 |
|-----------|--------|------|---------|
| `Fail-Safe 全部失敗` | 🔴 致命 | 入場了但 SL 沒掛上，自動平倉也失敗 | **立刻上 Binance 手動平倉** |
| `每日虧損熔斷` | 🟠 高 | 當日虧損 ≥ maxDailyLossUsdt | 今天停止交易，隔天 UTC 0:00 自動重置 |
| `Binance API 連線中斷` | 🟠 高 | API 打不通 | 檢查網路 + Binance 狀態頁 |
| `前置檢查失敗` | 🟡 中 | 餘額/持倉查詢失敗 | API key 可能過期或 IP 被封 |
| `重複訊號攔截` | 🟢 正常 | 去重機制正常運作 | 頻繁出現需檢查訊號來源 |
| `止盈單失敗` | 🟡 中 | TP 掛不上但 SL 正常 | 手動在 Binance 設定 TP |

---

## ⚠️ 常見踩坑

| 坑 | 說明 | 防範 |
|----|------|------|
| risk-percent 設太高 | 0.20 = 每筆冒 20% 餘額，2 次虧損 -36% | 先用 0.05 跑一週再調 |
| 槓桿 20x 的清算價 | BTC 做多 95000，清算價約 90250（-5%） | 確認 SL 一定比清算價先觸發 |
| Binance 手動操作衝突 | 在 Binance App 手動平倉，系統不知道 | 系統會認為還有持倉 → 下次訊號異常 |
| Webhook 沒收到不知道 | Webhook 失敗是 non-blocking，不 crash | 每小時看一次 Discord 頻道 |
| H2 資料庫沒備份 | 掛了全部紀錄消失，去重也失效 | **每天備份** |
| Docker 重啟丟快取 | symbolLock 和 recentSignals 清空 | DB 層去重保底，但 5 分鐘窗口小心 |
| 時區不對 | 每日熔斷在錯的時間重置 | Docker 設 `TZ=UTC` |

---

## 📊 UAT 通過標準

完成以下所有項目才算通過：

- [ ] 26 個測試案例全部通過
- [ ] 全流程（ENTRY → MOVE_SL → CLOSE）至少成功跑過 3 次
- [ ] 手續費計算與手動驗算一致
- [ ] 風控攔截（重複/白名單/熔斷）全部有效
- [ ] 並發測試：5 個相同訊號只成功 1 個
- [ ] Fail-Safe 日誌出現時有正確處理
- [ ] 連續 3 天每日檢查無異常
- [ ] DB 備份 + 還原測試成功

---

## 📈 上線後參數調整路線圖

| 階段 | 時間 | risk-percent | max-daily-loss-usdt | max-position-usdt |
|------|------|-------------|--------------------|--------------------|
| UAT | 第 1 週 | 0.05 (5%) | 500 | 10,000 |
| 觀察期 | 第 2-3 週 | 0.10 (10%) | 1,000 | 25,000 |
| 穩定期 | 第 4 週+ | 0.15-0.20 | 2,000 | 50,000 |

每次調整前確認：
1. 前一階段勝率 > 40%
2. Profit Factor > 1.0
3. 無 Fail-Safe 事件
4. 無未預期的錯誤日誌
