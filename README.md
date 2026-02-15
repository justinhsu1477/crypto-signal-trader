# Crypto Signal Trader

Discord 交易訊號自動跟單系統 — 監聽 Discord 頻道訊號，自動在 Binance Futures 下單。

## 架構

```
Discord Desktop (CDP 模式)
    │  Chrome DevTools Protocol
    ▼
Python Monitor (discord-monitor/)
    │  過濾頻道 → Gemini AI 解析成 JSON
    │              ↓ (AI 失敗時 fallback)
    │            regex 解析原始文字
    │  心跳回報 → Java API (每 30 秒)
    ▼
Spring Boot API (Docker, port 8080)
    │  風控檢查 → Binance 下單
    │  → Discord Webhook 通知結果
    │  → 訊號來源追蹤 (平台/頻道/作者)
    ▼
Binance Futures API
```

## 快速開始

### Step 1: 設定環境變數

```bash
cp .env.example .env
# 編輯 .env 填入必要設定
```

### Step 2: 啟動 Discord（帶 CDP）

```bash
cd discord-monitor
chmod +x launch_discord.sh
./launch_discord.sh

# 等 Discord 完全載入後，確認 CDP 可連
curl http://127.0.0.1:9222/json
```

### Step 3: 一鍵啟動（Docker Compose）

```bash
docker compose up --build -d
```

這會同時啟動兩個服務：
- **trading-api** — Java Spring Boot 交易引擎（port 8080）
- **discord-monitor** — Python CDP 監聽 + AI 解析

### 常用 Docker 指令

| 指令 | 說明 |
|------|------|
| `docker compose up --build -d` | 建置 + 背景啟動 |
| `docker compose logs -f` | 查看所有服務 log |
| `docker logs -f trading-api` | 只看 Java API log |
| `docker logs -f discord-monitor` | 只看 Python monitor log |
| `docker compose restart trading-api` | 重啟 Java API |
| `docker compose restart discord-monitor` | 重啟 Python monitor |
| `docker compose down` | 停止所有服務 |
| `docker compose ps` | 查看服務狀態 |

### 健康檢查 / 驗證

```bash
# 確認 Java API 正常
curl http://localhost:8080/api/balance

# 確認 Monitor 心跳正常
curl http://localhost:8080/api/monitor-status

# 測試解析訊號（不下單）
curl -X POST http://localhost:8080/api/parse-signal \
  -H "Content-Type: application/json" \
  -d '{"message": "📢 交易訊號發布: BTCUSDT\n做多 LONG 🟢 (限價單)\n入場價格 (Entry)\n95000\n止盈目標 (TP)\n98000\n止損價格 (SL)\n93000"}'
```

---

## 監控系統

### 心跳機制

Python monitor 每 30 秒向 Java API 回報心跳，Java 端偵測兩種異常：

| 異常類型 | 偵測方式 | 告警 |
|---------|---------|------|
| Python 掛了 | 心跳停止超過 90 秒 | Discord 紅色告警 |
| Discord 斷了但 Python 還活著 | 心跳帶 `status=reconnecting` | Discord 紅色告警 |
| 恢復正常 | 心跳恢復 `status=connected` | Discord 綠色恢復通知 |

### AI Agent 狀態監控

Python monitor 同時回報 AI parser 狀態（`aiStatus`）：

| 狀態 | 說明 | 告警 |
|------|------|------|
| `active` | Gemini AI 正常運作 | 無（正常） |
| `disabled` | AI 未啟用（API key 缺失或無效） | Discord 黃色告警，提示使用 regex fallback |
| 恢復 `active` | AI 重新連線成功 | Discord 綠色恢復通知 |

### 查詢監控狀態

```bash
curl http://localhost:8080/api/monitor-status
# 回傳: lastHeartbeat, elapsedSeconds, online, monitorStatus, aiStatus, alertSent
```

---

## 交易策略與風控系統

### 核心理念：以損定倉 (Fixed Fractional Risk)

每筆交易**先決定你願意虧多少，再反推倉位大小**。不管什麼幣種、什麼價位，每筆的最大虧損都是帳戶的固定比例。

#### 公式

```
1R = 帳戶可用餘額 × risk-percent
數量 = 1R / |入場價 - 止損價|
```

#### 舉例（帳戶餘額 1,000 USDT，risk-percent = 20%）

| 情境 | 入場價 | 止損價 | 風險距離 | 1R | 算出數量 | 名目價值 |
|------|--------|--------|----------|-----|---------|---------|
| BTC 做多（寬止損）| 95,000 | 93,000 | 2,000 | 200 | 0.1 BTC | 9,500 |
| BTC 做多（窄止損）| 95,000 | 94,500 | 500 | 200 | 0.4 BTC | 38,000 |
| ETH 做多 | 2,650 | 2,580 | 70 | 200 | 2.857 ETH | 7,571 |
| BTC 做空 | 95,000 | 96,000 | 1,000 | 200 | 0.2 BTC | 19,000 |

**特性：**
- 止損越窄 → 倉位越大（因為風險距離小）
- 止損越寬 → 倉位越小（因為風險距離大）
- 帳戶越大 → 1R 越大 → 倉位越大（自動縮放）
- 連虧縮倉：餘額縮水 → 1R 縮水 → 下一單自動縮小

### 三層倉位保護

以損定倉算出數量後，還有三道安全閥依序檢查，取最小值：

```
步驟 7:  以損定倉 → 原始數量
步驟 7b: 名目價值 cap → 超過 max-position-usdt 就縮
步驟 7c: 保證金充足性 → 保證金超過餘額 90% 就縮
步驟 7d: 最低下單量 → 名目 < 5 USDT 就拒絕
```

| 保護層 | 作用 | 觸發條件 |
|--------|------|----------|
| **名目 cap (7b)** | 防止窄止損產生超大倉位 | `入場價 × 數量 > max-position-usdt` |
| **保證金 cap (7c)** | 確保帳戶付得起保證金 | `入場價 × 數量 / 槓桿 > 餘額 × 90%` |
| **最低下單量 (7d)** | 帳戶太小無法交易時拒絕 | `入場價 × 數量 < 5 USDT` |

### 完整風控層（10 層）

| # | 檢查 | 說明 |
|---|------|------|
| 1 | 交易對白名單 | 只允許 `allowed-symbols` 裡的幣種 |
| 1b | 查帳戶餘額 | API 失敗直接拒絕，不會用 0 餘額算倉位 |
| 1c | 每日虧損熔斷 | 當日已實現虧損 ≥ `max-daily-loss-usdt` → 停止交易 |
| 2 | 最大持倉數 | 同時最多 `max-positions` 個持倉 |
| 2b | 重複掛單檢查 | 同幣種已有 LIMIT 單 → 拒絕 |
| 2c | 訊號去重 | 5 分鐘內相同訊號 → 拒絕 |
| 3 | 止損必填 | 沒有 SL 的 ENTRY 訊號直接拒絕 |
| 4 | 方向驗證 | 做多 SL 不能高於入場價、做空 SL 不能低於入場價 |
| 5 | 價格偏離檢查 | 入場價偏離市價 >10% → 拒絕 |
| 7 | 三層倉位保護 | 以損定倉 + 名目 cap + 保證金 cap + 最低量 |

### Fail-Safe 安全機制

當止損單下單失敗時，自動觸發保護流程：

```
SL 下單失敗
  → 取消入場單
    → 若取消失敗 → 市價平倉
      → 若全部失敗 → Discord 紅色告警（需人工介入）
```

### 手續費追蹤

每筆交易自動記錄手續費：

| 階段 | 費率 | 說明 |
|------|------|------|
| 入場 | 0.02% (maker) | 開倉時即計算並記錄 |
| 出場 | 0.04% (taker) | 平倉時計算，保守估算 |

盈虧公式：`淨利 = 毛利 - (入場手續費 + 出場手續費)`

### 訊號來源追蹤

每筆交易自動記錄訊號來源：

| 欄位 | 說明 | 範例 |
|------|------|------|
| `sourcePlatform` | 來源平台 | DISCORD, TELEGRAM, MANUAL |
| `sourceChannelId` | 頻道 ID | 1325133886509944983 |
| `sourceGuildId` | 伺服器 ID | 862188678876233748 |
| `sourceAuthorName` | 訊號發送者 | 陳哥 |
| `sourceMessageId` | 原始訊息 ID | 用於追溯原始訊號 |

通用設計，未來支援 Telegram 等其他平台不需改架構。

### Discord 通知

每次操作結果即時推送到你的 Discord 頻道：

| 事件 | Emoji | 顏色 |
|------|-------|------|
| ENTRY 成功 | ✅ | 綠色 |
| 操作失敗 | ❌ | 紅色 |
| CANCEL 取消 | 🚫 | 藍色 |
| TP/SL 修改 | 🔄 | 藍色 |
| CLOSE 平倉 | 💰 | 綠色 |
| 風控攔截 | ⚠️ | 黃色 |
| 重複跳過 | ⏭️ | 黃色 |
| TP 失敗告警 | ⚠️ | 黃色 |
| 每日熔斷 | 🚨 | 紅色 |
| Fail-Safe 失敗 | 🚨 | 紅色 |
| Discord 連線中斷 | 🚨 | 紅色 |
| Discord Monitor 離線 | 🚨 | 紅色 |
| AI Agent 未啟用 | ⚠️ | 黃色 |
| 連線/AI 恢復 | ✅ | 綠色 |

入場通知會顯示風控摘要：`餘額 | 1R | 保證金需求`，以及訊號來源資訊。

---

## 設定說明

### `.env` 環境變數

```env
SPRING_PROFILES_ACTIVE=dev          # dev=Testnet假錢, prod=正式真錢
BINANCE_API_KEY=your_key
BINANCE_SECRET_KEY=your_secret
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/你的ID/你的TOKEN
DISCORD_WEBHOOK_ENABLED=true
GEMINI_API_KEY=your_gemini_key      # AI 訊號解析用

# Discord 監聽設定 (Python)
DISCORD_CHANNEL_IDS=your_channel_id
DISCORD_GUILD_IDS=your_guild_id
```

### 風控參數 (`application.yml`)

```yaml
binance:
  risk:
    max-position-usdt: 50000      # 單筆最大名目價值 (USDT)
    max-daily-loss-usdt: 2000     # 每日虧損熔斷上限 (USDT)
    risk-percent: 0.20            # 單筆風險比例 (20%)
    max-positions: 1              # 最大同時持倉數
    fixed-leverage: 20            # 固定槓桿 (逐倉 ISOLATED)
    allowed-symbols:              # 交易對白名單
      - BTCUSDT
```

#### 參數調整指南

| 參數 | 預設值 | 建議範圍 | 說明 |
|------|--------|----------|------|
| `risk-percent` | 0.20 (20%) | 0.01 ~ 0.25 | **最重要的參數。** 每筆願意虧帳戶的幾 %。保守型 1~5%，積極型 10~25% |
| `max-position-usdt` | 50,000 | 看帳戶大小 | 單筆名目價值上限。防止窄止損算出超大倉位 |
| `max-daily-loss-usdt` | 2,000 | 看能接受的日虧損 | **固定值，不隨餘額浮動。** 建議設為帳戶的 20~50% |
| `max-positions` | 1 | 1~5 | 同時持倉數。1 = 一次只做一單，最安全 |
| `fixed-leverage` | 20 | 5~20 | 槓桿倍數 (逐倉)。槓桿高不代表風險高（以損定倉已控制），但滑點影響會放大 |
| `allowed-symbols` | BTCUSDT | - | 白名單外的幣種全部拒絕 |

#### 不同帳戶規模建議

| 帳戶大小 | risk-percent | max-position-usdt | max-daily-loss-usdt |
|---------|-------------|-------------------|-------------------|
| 100~500 USDT | 0.10~0.20 | 5,000~10,000 | 200~500 |
| 500~2,000 USDT | 0.10~0.20 | 10,000~30,000 | 500~1,000 |
| 2,000~10,000 USDT | 0.05~0.20 | 20,000~50,000 | 1,000~3,000 |
| 10,000+ USDT | 0.02~0.10 | 50,000~100,000 | 2,000~5,000 |

> **重點：帳戶越大，`risk-percent` 建議越低。** 100U 帳戶拿 20% 冒險合理（每筆虧 20U），但 10,000U 帳戶 20% = 每筆虧 2,000U 可能太激進。

### Discord 監聽 (`discord-monitor/config.yml`)

```yaml
discord:
  channel_ids:
    - "頻道ID"        # 右鍵頻道 → 複製頻道 ID
  guild_ids:
    - "伺服器ID"      # 右鍵伺服器 → 複製伺服器 ID
  author_ids: []      # 空 = 所有人，填入 = 指定作者

ai:
  enabled: true               # true = AI 解析, false = 純 regex
  model: "gemini-2.0-flash"   # Gemini 模型
  api_key_env: "GEMINI_API_KEY"
  timeout: 15
```

---

## AI 訊號解析

使用 Gemini Flash AI 解析 Discord 訊號，不依賴固定 emoji 或格式。

### 解析策略

```
AI 開啟時：所有訊息 → Gemini AI 判斷 action → 結構化 JSON → /api/execute-trade
                        ↓ (AI 失敗)
              fallback → 原始文字 → /api/execute-signal (regex)

AI 關閉時：emoji/keyword 過濾 → 只有 ACTIONABLE → regex 解析
```

### AI 辨識的訊號類型

| Action | 觸發條件 | 說明 |
|--------|---------|------|
| ENTRY | 出現入場價、做多/做空等交易訊號 | 限價單 + TP + SL |
| CANCEL | 出現取消掛單相關字詞 | 取消該幣種掛單 |
| MOVE_SL | 出現 TP-SL 修改、訂單修改 | 重新掛 TP/SL |
| CLOSE | 出現「平倉」二字 | 全部平倉 |
| INFO | 成交通知、盈虧更新、閒聊 | 跳過不處理 |

AI 靠語意理解判斷，不綁死特定 emoji 或格式。不同群主的訊號格式都能處理。

---

## REST API

### Binance 帳戶查詢

| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/balance` | GET | 帳戶餘額 |
| `/api/positions` | GET | 當前持倉 |
| `/api/exchange-info` | GET | 交易對資訊 |
| `/api/open-orders?symbol=BTCUSDT` | GET | 未成交訂單 |

### 交易操作

| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/parse-signal` | POST | 解析訊號（不下單） |
| `/api/execute-signal` | POST | 解析 + 下單（原始文字） |
| `/api/execute-trade` | POST | 結構化 JSON 下單 |
| `/api/leverage` | POST | 手動設定槓桿 |
| `/api/orders?symbol=BTCUSDT` | DELETE | 取消所有訂單 |

### 監控

| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/heartbeat` | POST | Python monitor 心跳回報 |
| `/api/monitor-status` | GET | 查詢 monitor 連線與 AI 狀態 |

### 交易紀錄與統計

| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/trades` | GET | 交易紀錄（可選 `?status=OPEN/CLOSED`） |
| `/api/trades/{id}` | GET | 單筆詳情（含訊號來源） |
| `/api/trades/{id}/events` | GET | 事件日誌 |
| `/api/stats/summary` | GET | 盈虧統計摘要 |

### execute-trade JSON 格式

```bash
# ENTRY 開倉（含訊號來源）
curl -X POST http://localhost:8080/api/execute-trade \
  -H "Content-Type: application/json" \
  -d '{"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":95000,"stop_loss":93000,"take_profit":98000,"source":{"platform":"DISCORD","channel_id":"123","author_name":"陳哥"}}'

# CLOSE 平倉（全部）
curl -X POST http://localhost:8080/api/execute-trade \
  -H "Content-Type: application/json" \
  -d '{"action":"CLOSE","symbol":"BTCUSDT"}'

# CLOSE 平倉（50%）
curl -X POST http://localhost:8080/api/execute-trade \
  -H "Content-Type: application/json" \
  -d '{"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.5}'

# MOVE_SL 移動止損
curl -X POST http://localhost:8080/api/execute-trade \
  -H "Content-Type: application/json" \
  -d '{"action":"MOVE_SL","symbol":"BTCUSDT","new_stop_loss":94500}'

# CANCEL 取消掛單
curl -X POST http://localhost:8080/api/execute-trade \
  -H "Content-Type: application/json" \
  -d '{"action":"CANCEL","symbol":"BTCUSDT"}'
```

### 統計摘要回傳欄位

```json
{
  "closedTrades": 15,
  "winningTrades": 9,
  "winRate": "60.0%",
  "totalNetProfit": 1250.50,
  "grossWins": 2800.00,
  "grossLosses": 1549.50,
  "profitFactor": 1.81,
  "avgProfitPerTrade": 83.37,
  "totalCommission": 45.20,
  "openPositions": 1
}
```

---

## 技術細節

### 技術棧

- **Java 17** + **Spring Boot 3.2.5** — 交易引擎
- **Python 3** — Discord CDP 監聽 + Gemini AI 解析
- **H2 Database** — 交易紀錄持久化
- **OkHttp** — Binance API 通訊
- **Docker Compose** — 容器化部署（一鍵啟動）
- **Gradle 8.13** — 建置工具

### SL/TP 下單重試機制

止損單和止盈單使用 idempotent key（`newClientOrderId`）確保重試安全：

- 只在 IOException（網路斷線/timeout）時重試，HTTP 回應不重試
- 最多重試 2 次（間隔 1s → 3s）
- 全部失敗 → Discord 紅色告警

### 資料庫 (H2)

交易紀錄存在 `./data/trading` 檔案中（H2 嵌入式資料庫）。

兩張表：
- **trade** — 交易主紀錄（入場、出場、盈虧、手續費、訊號來源）
- **trade_event** — 事件日誌（每個動作的詳細記錄）

### 時區

所有時間戳記（交易紀錄、事件日誌、Discord 通知）統一使用 **Asia/Taipei (UTC+8)** 時區。
