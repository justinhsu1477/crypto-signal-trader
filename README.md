# Crypto Signal Trader

Discord 交易訊號自動跟單系統 — 監聽 Discord 頻道訊號，自動在 Binance Futures 下單。

> 正在擴展為 **SaaS 多用戶平台**（auth / subscription / dashboard 模組開發中）

## 系統架構

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
Spring Boot API (Docker, port 8080/8081)
    │  風控檢查 → Binance 下單
    │  → 部分平倉 + SL/TP 重掛保護
    │  → DCA 補倉（2R 加碼）
    │  → Symbol 智能 Fallback
    │  → Discord Webhook 通知結果
    │  → 訊號來源追蹤 (平台/頻道/作者)
    ▼
Binance Futures API
    │
    ▼
WebSocket User Data Stream（即時回報）
    → SL/TP 觸發 → 真實數據寫入 DB（支援部分平倉）
    → SL/TP 被取消 → Discord 告警（持倉裸奔）

資料庫:
    Prod → Neon 雲端 PostgreSQL (Singapore)  ← 資料永久保存
    Dev  → 本地 PostgreSQL Docker container

前端:
    Web Dashboard (Next.js + React + shadcn/ui, port 3000/3001)
```

## 模組架構

專案採用 **package-level 模組化**，按業務領域拆分，每個模組有獨立的 controller / service / entity / dto。

```
com.trader/
├── trading/         # 交易核心（開倉/平倉/風控/WebSocket）  ← 生產中 ✅
├── shared/          # 共用元件（Config/DTO/工具類）
├── notification/    # 通知（Discord Webhook，未來擴展 Email/LINE）
├── auth/            # 認證（JWT 登入/註冊）                ← 骨架 🔨
├── user/            # 用戶（帳號/API Key 管理）            ← 骨架 🔨
├── subscription/    # 訂閱計費（Stripe 整合）              ← 骨架 🔨
└── dashboard/       # 前端 Dashboard API（聚合層）         ← 骨架 🔨
```

### 模組依賴方向

```
┌──────────┐     ┌──────────┐
│   auth   │────▶│   user   │
└──────────┘     └──────────┘
                       ▲
┌──────────────┐       │
│ subscription │───────┘
└──────────────┘
       ▲
       │
┌──────────────┐     ┌──────────┐     ┌────────────────┐
│  dashboard   │────▶│ trading  │────▶│  notification  │
└──────────────┘     └──────────┘     └────────────────┘
                          │
                          ▼
                     ┌──────────┐
                     │  shared  │ ◀── 所有模組都可用
                     └──────────┘
```

**規則：箭頭方向 = 可以 import 的方向，反向禁止，不可循環依賴。**

### 模組說明

| 模組 | 職責 | 狀態 |
|------|------|------|
| **trading** | 幣安合約交易（開倉/平倉/風控/DCA/WebSocket 監聽） | ✅ 生產中 |
| **shared** | 共用 Config（Binance/Risk/Webhook）+ DTO + 簽名工具 | ✅ 生產中 |
| **notification** | Discord Embed 通知（非同步 enqueue） | ✅ 生產中 |
| **auth** | Spring Security + JWT 認證（登入/註冊/token 刷新） | 🔨 骨架 |
| **user** | 用戶帳號 + 加密 API Key 管理 | 🔨 骨架 |
| **subscription** | Stripe 訂閱計費（checkout/webhook/狀態查詢） | 🔨 骨架 |
| **dashboard** | 前端 API 聚合層（持倉摘要/績效統計/交易紀錄） | 🔨 骨架 |

> **骨架模組**：class / interface / 方法簽名已定義，方法體為 TODO，不影響現有功能。

---

## 快速開始

### Step 1: 設定環境變數

```bash
# Dev 環境（Binance Testnet 假錢測試）
cp .env.example .env.dev
# 編輯 .env.dev 填入 Testnet API Keys

# Prod 環境（Binance 正式真錢交易）
cp .env.example .env.prod
# 編輯 .env.prod 填入正式 API Keys + Neon DB 連線
```

### Step 2: 啟動 Discord（帶 CDP）

```bash
# 先關閉現有 Discord
killall Discord 2>/dev/null

# 用 CDP 模式重新啟動
/Applications/Discord.app/Contents/MacOS/Discord --remote-debugging-port=9222

# 等 Discord 完全載入後，確認 CDP 可連
curl http://127.0.0.1:9222/json
```

### Step 3: 啟動服務（Docker Compose）

系統支援 Dev / Prod 環境分離，可同時跑兩套互不干擾。

```bash
# === Prod 環境（正式, port 8081, Neon 雲端 DB）===
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

# === Dev 環境（Testnet, port 8080, 本地 PostgreSQL）===
docker compose -f docker-compose.yml -f docker-compose.dev.yml --profile local-db up -d --build
```

| 環境 | API Port | Dashboard Port | Binance | DB | Container 前綴 |
|------|----------|----------------|---------|-----|---------------|
| **Prod** | 8081 | 3001 | 正式（真錢） | Neon 雲端 PostgreSQL | `*-prod` |
| **Dev** | 8080 | 3000 | Testnet（假錢） | 本地 PostgreSQL container | `*-dev` |

### 換版部署 SOP（Prod）

```bash
# 1. 拉最新程式碼
git pull

# 2. 只重建 app 服務（DB 在 Neon 雲端，不受影響）
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

# 3. 確認健康
docker ps
curl http://localhost:8081/api/balance
```

> Prod 環境使用 Neon 雲端 DB，`docker compose down` 不會影響資料。
> Dev 環境的本地 DB 資料存在 `pg-data-dev` volume，只要不加 `-v` 就不會丟。

### 常用 Docker 指令

```bash
# === Prod 環境 ===
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build   # 啟動
docker compose -f docker-compose.yml -f docker-compose.prod.yml logs -f          # 查看 log
docker compose -f docker-compose.yml -f docker-compose.prod.yml down             # 停止（資料安全）

# === Dev 環境 ===
docker compose -f docker-compose.yml -f docker-compose.dev.yml --profile local-db up -d --build   # 啟動
docker compose -f docker-compose.yml -f docker-compose.dev.yml --profile local-db logs -f          # 查看 log
docker compose -f docker-compose.yml -f docker-compose.dev.yml --profile local-db down             # 停止

# === 單一服務 log ===
docker logs -f trading-api-prod      # Prod Java API log
docker logs -f discord-monitor-prod  # Prod Python monitor log
docker logs -f trading-api-dev       # Dev Java API log
docker logs -f discord-monitor-dev   # Dev Python monitor log

# === 查看狀態 ===
docker ps                            # 所有運行中的 container
```

### 健康檢查 / 驗證

```bash
# Prod 環境 (port 8081)
curl http://localhost:8081/api/balance
curl http://localhost:8081/api/monitor-status
curl http://localhost:8081/api/stream-status

# Dev 環境 (port 8080)
curl http://localhost:8080/api/balance
curl http://localhost:8080/api/monitor-status
curl http://localhost:8080/api/stream-status

# 測試解析訊號（不下單，Dev 環境）
curl -X POST http://localhost:8080/api/parse-signal \
  -H "Content-Type: application/json" \
  -d '{"message": "📢 交易訊號發布: BTCUSDT\n做多 LONG 🟢 (限價單)\n入場價格 (Entry)\n95000\n止盈目標 (TP)\n98000\n止損價格 (SL)\n93000"}'
```

---

## 交易功能

### DCA 補倉系統

支援同幣種多次加倉（Dollar Cost Averaging），適合趨勢行情中的逐步建倉策略。

| 項目 | 說明 |
|------|------|
| 最大層數 | `max-dca-per-symbol`（預設 3 = 首次入場 + 2 次補倉） |
| 補倉倉位 | 用 2R 計算（`dca-risk-multiplier = 2.0`），首倉是 1R |
| 方向自動判斷 | 補倉方向跟隨既有持倉（不用再寫 LONG/SHORT） |
| 均價計算 | 自動更新加權平均入場價 |
| SL/TP 重掛 | 補倉後自動以新的止損止盈重掛保護單 |
| 無持倉防護 | DCA 訊號在無持倉時自動拒絕，不會誤開新倉 |

```
首次入場 (1R) → 補倉 1 (2R) → 補倉 2 (2R) → 達到上限，拒絕
                    ↓               ↓
              更新均價/SL/TP    更新均價/SL/TP
```

### 部分平倉 + SL/TP 重掛保護

平倉可以指定比例（例如 50%），剩餘倉位自動重掛 SL/TP。

| 操作 | 說明 |
|------|------|
| `close_ratio: 0.5` | 平掉 50% 倉位 |
| `close_ratio: null` | 全部平倉（預設） |
| 搭配 `new_stop_loss` | 剩餘倉位掛新 SL |
| 搭配 `new_take_profit` | 剩餘倉位掛新 TP |

SL 重掛優先級：`new_stop_loss` > 開倉價（成本保護）> 舊 SL

**場景舉例：** 「止盈50%做成本保護」→ 平掉一半，剩餘倉位 SL 移到開倉價。

### 成本保護（MOVE_SL）

當訊號是「做保本處理」「止損上移至成本附近」而**沒有具體價格**時：

```
MOVE_SL + new_stop_loss = null
  → 查 DB 開倉價 → 用開倉價當 SL
    → 查不到？ → 用舊 SL 重掛（至少不裸奔）
```

### Symbol 智能 Fallback

當訊號沒提到幣種（例如「全部平倉」「做成本保護」），AI 預設 BTCUSDT。如果 BTCUSDT 無持倉：

| DB OPEN trades | 行為 |
|---------------|------|
| 恰好 1 筆（例如 ETHUSDT） | 自動修正為該幣種 + Discord 通知 |
| 0 筆 | 回傳失敗「無持倉可平」 |
| 2+ 筆（BTC + ETH 同時持有） | 不猜測，回傳失敗（需訊號明確指定幣種） |

適用於 CLOSE 和 MOVE_SL 兩種操作。

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
curl http://localhost:8081/api/monitor-status
# 回傳: lastHeartbeat, elapsedSeconds, online, monitorStatus, aiStatus, alertSent
```

### WebSocket User Data Stream

透過幣安 WebSocket 即時監聽帳戶事件，解決 SL/TP 自動觸發後 DB 不同步的問題。

| 事件 | 處理方式 | 告警 |
|------|---------|------|
| SL 觸發 (STOP_MARKET FILLED) | 真實出場價 + 手續費更新 DB | Discord 紅色通知 |
| TP 觸發 (TAKE_PROFIT_MARKET FILLED) | 真實出場價 + 手續費更新 DB | Discord 綠色通知 |
| SL/TP **部分**觸發 | 追蹤 remainingQuantity，維持 OPEN | Discord 通知 |
| SL 被取消 (CANCELED/EXPIRED) | 記錄 SL_LOST 事件 | 🚨 紅色告警（持倉裸奔） |
| TP 被取消 (CANCELED/EXPIRED) | 記錄 TP_LOST 事件 | ⚠️ 黃色告警 |
| listenKey 過期 | 自動重建連線 | — |
| WebSocket 斷線 | 指數退避重連 (1s→2s→4s→...→60s，上限 20 次) | 🚨 紅色告警 |
| WebSocket 恢復 | 退避歸零 | ✅ 綠色恢復通知 |

**連線維護：** listenKey 每 30 分鐘 PUT 延長，WebSocket ping interval 20 秒。

```bash
# 查詢 WebSocket 連線狀態
curl http://localhost:8081/api/stream-status
# 回傳: connected, listenKeyActive, lastMessageTime, reconnectAttempts
```

### 每日自動排程

| 時間 (台灣) | 排程 | 說明 |
|------------|------|------|
| 07:55 | 殭屍清理 | 比對幣安持倉，清理 DB 中無對應持倉的 OPEN 紀錄 |
| 08:00 | 每日報告 | 推送每日交易摘要到 Discord |

#### 每日報告內容（6 大區塊）

| 區塊 | 內容 | 資料來源 |
|------|------|---------|
| 💰 帳戶餘額 | 可用餘額 USDT | Binance API |
| 📊 昨日交易 | 筆數、勝率、淨利 + 最多 5 筆明細 + 最大單筆虧損 | DB |
| 📍 當前持倉 | symbol、方向、入場價、SL、DCA 次數 | DB |
| 🛡️ 今日風控 | 已用額度 / 每日限額 + 熔斷狀態 | DB + Config |
| 📈 累計統計 | 總淨利、勝率、PF、平均每筆盈虧 | DB |
| ⚙️ 系統狀態 | Monitor 心跳 + AI Agent + WebSocket 連線 | Memory |

```bash
# 手動觸發殭屍清理
curl -X POST http://localhost:8081/api/admin/cleanup-trades
```

---

## 交易策略與風控系統

### 核心理念：以損定倉 (Fixed Fractional Risk)

每筆交易**先決定你願意虧多少，再反推倉位大小**。不管什麼幣種、什麼價位，每筆的最大虧損都是帳戶的固定比例。

#### 公式

```
1R = 帳戶可用餘額 × risk-percent
數量 = 1R / |入場價 - 止損價|

DCA 補倉：2R = 1R × dca-risk-multiplier
補倉數量 = 2R / |入場價 - 止損價|
```

#### 舉例（帳戶餘額 1,000 USDT，risk-percent = 20%）

| 情境 | 入場價 | 止損價 | 風險距離 | 1R | 算出數量 | 名目價值 |
|------|--------|--------|----------|-----|---------|---------|
| BTC 做多（寬止損）| 95,000 | 93,000 | 2,000 | 200 | 0.1 BTC | 9,500 |
| BTC 做多（窄止損）| 95,000 | 94,500 | 500 | 200 | 0.4 BTC | 38,000 |
| ETH 做多 | 2,650 | 2,580 | 70 | 200 | 2.857 ETH | 7,571 |
| BTC 做空 | 95,000 | 96,000 | 1,000 | 200 | 0.2 BTC | 19,000 |
| BTC DCA 補倉 | 93,000 | 91,000 | 2,000 | 400 (2R) | 0.2 BTC | 18,600 |

**特性：**
- 止損越窄 → 倉位越大（因為風險距離小）
- 止損越寬 → 倉位越小（因為風險距離大）
- 帳戶越大 → 1R 越大 → 倉位越大（自動縮放）
- 連虧縮倉：餘額縮水 → 1R 縮水 → 下一單自動縮小
- DCA 補倉用 2R，加碼力度更大但仍受風控限制

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
| 1b | 查帳戶餘額 | API 失敗直接拒絕（拋異常），不會用 0 餘額算倉位 |
| 1c | 每日虧損熔斷 | 當日已實現虧損 ≥ `max-daily-loss-usdt` → 停止交易 |
| 2 | 最大持倉數 | 同幣種最多 `max-dca-per-symbol` 層（DCA 允許多層） |
| 2b | 重複掛單檢查 | 同幣種已有 LIMIT 單 → 拒絕（DCA 除外） |
| 2c | 訊號去重 | 5 分鐘內相同訊號 hash → 拒絕 |
| 3 | 止損必填 | 沒有 SL 的 ENTRY 訊號直接拒絕 |
| 4 | 方向驗證 | 做多 SL 不能高於入場價、做空 SL 不能低於入場價 |
| 5 | 價格偏離檢查 | 入場價偏離市價 >10% → 拒絕 |
| 7 | 三層倉位保護 | 以損定倉 + 名目 cap + 保證金 cap + 最低量 |

**API 安全策略：** 所有 Binance API 查詢（餘額、持倉、市價等）失敗時直接拋 RuntimeException，絕不用 0 值靜默繼續。防止 0 餘額 → 0 倉位、假無持倉 → 重複開倉等危險場景。

### Fail-Safe 安全機制

當止損單下單失敗時，自動觸發保護流程：

```
SL 下單失敗
  → 取消入場單
    → 若取消失敗 → 市價平倉
      → 若全部失敗 → Discord 紅色告警（需人工介入）
```

所有失敗環節都會記錄 `FAIL_SAFE` 事件到 DB，方便事後追蹤。

### 手續費追蹤

| 階段 | 來源 | 說明 |
|------|------|------|
| 入場 | 估算 0.02% (maker) | 開倉時即計算並記錄到 `entryCommission` |
| 出場（主動平倉） | 估算 0.04% (taker) | 平倉時計算 |
| 出場（SL/TP 觸發） | WebSocket 真實值 | 幣安回傳的實際手續費（`o.n`） |

盈虧公式：`淨利 = 毛利 - (入場手續費 + 出場手續費)`

> WebSocket 觸發的出場使用真實手續費，手動平倉使用保守估算值。

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
| DCA 補倉成功 | 📈 | 綠色 |
| CLOSE 平倉成功 | 💰 | 綠色 |
| TP/SL 修改成功 | 🔄 | 藍色 |
| CANCEL 取消成功 | 🚫 | 藍色 |
| Symbol 自動修正 | 🔄 | 藍色 |
| 操作失敗 | ❌ | 紅色 |
| 風控攔截 | ⚠️ | 黃色 |
| 重複跳過 | ⏭️ | 黃色 |
| TP 失敗告警 | ⚠️ | 黃色 |
| 每日熔斷 | 🚨 | 紅色 |
| Fail-Safe 失敗 | 🚨 | 紅色 |
| SL 觸發（WebSocket） | 🛑 | 紅色 |
| TP 觸發（WebSocket） | 🎯 | 綠色 |
| SL 被取消（裸奔） | 🚨 | 紅色 |
| TP 被取消 | ⚠️ | 黃色 |
| WebSocket 斷線 | 🚨 | 紅色 |
| WebSocket 恢復 | ✅ | 綠色 |
| Discord 連線中斷 | 🚨 | 紅色 |
| Discord Monitor 離線 | 🚨 | 紅色 |
| AI Agent 未啟用 | ⚠️ | 黃色 |
| 連線/AI 恢復 | ✅ | 綠色 |
| Binance API 連線中斷 | 🔴 | 紅色 |
| 殭屍 Trade 清理 | 🧹 | 藍色 |
| 每日交易摘要 | 📊 | 藍色 |

入場通知會顯示風控摘要：`餘額 | 1R | 保證金需求`，以及訊號來源資訊。

---

## 設定說明

### 環境變數（`.env.dev` / `.env.prod`）

依環境建立對應的 `.env` 檔案，兩套互相隔離：

```env
# === 必填 ===
SPRING_PROFILES_ACTIVE=prod         # dev=Testnet假錢, prod=正式真錢
BINANCE_API_KEY=your_key
BINANCE_SECRET_KEY=your_secret
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/你的ID/你的TOKEN
DISCORD_WEBHOOK_ENABLED=true
GEMINI_API_KEY=your_gemini_key      # AI 訊號解析用

# === Discord 監聽設定 (Python) ===
DISCORD_CHANNEL_IDS=your_channel_id
DISCORD_GUILD_IDS=your_guild_id

# === 資料庫 ===
# Prod: Neon 雲端 PostgreSQL
DB_URL=jdbc:postgresql://ep-xxx.ap-southeast-1.aws.neon.tech/trading?sslmode=require
DB_USERNAME=neondb_owner
DB_PASSWORD=your_neon_password

# Dev: 本地 PostgreSQL container
# DB_URL=jdbc:postgresql://postgres:5432/trading
# DB_USERNAME=trading
# DB_PASSWORD=trading

# === SaaS 功能（骨架階段，尚未啟用）===
JWT_SECRET=your-jwt-secret-at-least-256-bits
AES_ENCRYPTION_KEY=your-aes-key-must-be-32-chars!!
STRIPE_SECRET_KEY=sk_test_xxx
STRIPE_WEBHOOK_SECRET=whsec_xxx
```

### 風控參數 (`application.yml`)

```yaml
binance:
  risk:
    max-position-usdt: 50000      # 單筆最大名目價值 (USDT)
    max-daily-loss-usdt: 2000     # 每日虧損熔斷上限 (USDT)
    risk-percent: 0.20            # 單筆風險比例 (20%)
    max-dca-per-symbol: 3         # 同幣種最多 3 層（首次入場 + 2 次補倉）
    dca-risk-multiplier: 2.0      # 補倉風險倍數（2R = 首倉的 2 倍風險金額）
    fixed-leverage: 20            # 固定槓桿 (逐倉 ISOLATED)
    default-symbol: BTCUSDT       # 訊號無幣種時的預設交易對
    allowed-symbols:              # 交易對白名單
      - BTCUSDT
```

#### 參數調整指南

| 參數 | 預設值 | 建議範圍 | 說明 |
|------|--------|----------|------|
| `risk-percent` | 0.20 (20%) | 0.01 ~ 0.25 | **最重要的參數。** 每筆願意虧帳戶的幾 %。保守型 1~5%，積極型 10~25% |
| `max-position-usdt` | 50,000 | 看帳戶大小 | 單筆名目價值上限。防止窄止損算出超大倉位 |
| `max-daily-loss-usdt` | 2,000 | 看能接受的日虧損 | **固定值，不隨餘額浮動。** 建議設為帳戶的 20~50% |
| `max-dca-per-symbol` | 3 | 1~5 | 同幣種最多幾層。1 = 不允許補倉，3 = 首倉 + 2 次補倉 |
| `dca-risk-multiplier` | 2.0 | 1.0~3.0 | 補倉用幾倍 R。2.0 = 補倉倉位是首倉風險的 2 倍 |
| `fixed-leverage` | 20 | 5~20 | 槓桿倍數 (逐倉)。槓桿高不代表風險高（以損定倉已控制），但滑點影響會放大 |
| `default-symbol` | BTCUSDT | - | 訊號無幣種時的預設值。系統還會 fallback 查 DB OPEN trade |
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
| ENTRY (DCA) | 已有持倉 + 出現補倉/加倉訊號 | `is_dca: true` + 新 SL/TP |
| CANCEL | 出現取消掛單相關字詞 | 取消該幣種掛單 |
| MOVE_SL | 出現 TP-SL 修改、成本保護 | 重新掛 TP/SL（支援 null = 查開倉價） |
| CLOSE | 出現「平倉」「止盈出局」「保本出局」 | 支援部分平倉 + SL/TP 重掛 |
| INFO | 成交通知、盈虧更新、閒聊 | 跳過不處理 |

AI 靠語意理解判斷，不綁死特定 emoji 或格式。不同群主的訊號格式都能處理。

---

## REST API

### 認證（🔨 開發中）

| 端點 | 方法 | 說明 | 狀態 |
|------|------|------|------|
| `/api/auth/register` | POST | 用戶註冊 | 骨架可用 |
| `/api/auth/login` | POST | JWT 登入 | TODO |
| `/api/auth/refresh` | POST | Token 刷新 | TODO |

### 用戶（🔨 開發中）

| 端點 | 方法 | 說明 | 狀態 |
|------|------|------|------|
| `/api/user/me` | GET | 取得當前用戶資訊 | TODO |
| `/api/user/api-keys` | PUT | 儲存交易所 API Key（加密） | TODO |
| `/api/user/api-keys` | GET | 查詢已綁定的交易所 | TODO |

### 訂閱計費（🔨 開發中）

| 端點 | 方法 | 說明 | 狀態 |
|------|------|------|------|
| `/api/subscription/plans` | GET | 查詢可用方案 | TODO |
| `/api/subscription/checkout` | POST | 建立 Stripe Checkout Session | TODO |
| `/api/subscription/status` | GET | 查詢訂閱狀態 | 骨架 |
| `/api/subscription/cancel` | POST | 取消訂閱 | TODO |
| `/api/subscription/webhook` | POST | Stripe Webhook 回調 | TODO |

### Dashboard（🔨 開發中）

| 端點 | 方法 | 說明 | 狀態 |
|------|------|------|------|
| `/api/dashboard/overview` | GET | 首頁摘要（持倉/今日盈虧/訂閱狀態） | 骨架 |
| `/api/dashboard/trades` | GET | 用戶交易紀錄（分頁） | TODO |
| `/api/dashboard/performance` | GET | 績效統計（勝率/PF/盈虧曲線） | 骨架 |

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
| `/api/stream-status` | GET | 查詢 WebSocket 連線狀態 |

### 管理

| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/admin/cleanup-trades` | POST | 手動觸發殭屍清理 |

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
curl -X POST http://localhost:8081/api/execute-trade \
  -H "Content-Type: application/json" \
  -d '{"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":95000,"stop_loss":93000,"take_profit":98000,"source":{"platform":"DISCORD","channel_id":"123","author_name":"陳哥"}}'

# DCA 補倉（已有 BTC 持倉，加倉 + 更新 SL/TP）
curl -X POST http://localhost:8081/api/execute-trade \
  -H "Content-Type: application/json" \
  -d '{"action":"ENTRY","symbol":"BTCUSDT","is_dca":true,"entry_price":93000,"new_stop_loss":91000,"new_take_profit":98000}'

# CLOSE 全部平倉
curl -X POST http://localhost:8081/api/execute-trade \
  -H "Content-Type: application/json" \
  -d '{"action":"CLOSE","symbol":"BTCUSDT"}'

# CLOSE 平倉 50% + 剩餘做成本保護
curl -X POST http://localhost:8081/api/execute-trade \
  -H "Content-Type: application/json" \
  -d '{"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.5,"new_stop_loss":null}'

# CLOSE 平倉 50% + 剩餘指定新 SL
curl -X POST http://localhost:8081/api/execute-trade \
  -H "Content-Type: application/json" \
  -d '{"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.5,"new_stop_loss":94500,"new_take_profit":99000}'

# MOVE_SL 移動止損（指定價格）
curl -X POST http://localhost:8081/api/execute-trade \
  -H "Content-Type: application/json" \
  -d '{"action":"MOVE_SL","symbol":"BTCUSDT","new_stop_loss":94500}'

# MOVE_SL 成本保護（不帶價格 → 用開倉價）
curl -X POST http://localhost:8081/api/execute-trade \
  -H "Content-Type: application/json" \
  -d '{"action":"MOVE_SL","symbol":"BTCUSDT"}'

# CANCEL 取消掛單
curl -X POST http://localhost:8081/api/execute-trade \
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
- **Spring Security** + **JJWT 0.12.6** — JWT 認證（骨架）
- **Python 3** — Discord CDP 監聽 + Gemini AI 解析
- **Next.js 14** + **React** + **shadcn/ui** — Web Dashboard 前端（深色主題）
- **PostgreSQL 16** — 交易紀錄持久化
  - Prod：**Neon** 雲端 (Singapore, `sslmode=require`)
  - Dev：本地 Docker container
- **OkHttp** — Binance API 通訊 + WebSocket 長連線
- **Stripe**（計畫整合） — 訂閱計費
- **Docker Compose** — 容器化部署（Dev/Prod 環境分離）
- **Gradle 8.13** — 建置工具

### 部署架構

```
┌────────────────────────────────────────────────┐
│                  本機 Docker                     │
│                                                 │
│  ┌──────────────┐  ┌───────────────────────┐    │
│  │ trading-api   │  │  web-dashboard        │    │
│  │ (Spring Boot) │  │  (Next.js, port 3001) │    │
│  │  port 8081    │  └───────────┬───────────┘    │
│  └──────┬────────┘              │                │
│         │              HTTP API 呼叫              │
│  ┌──────┴────────┐                               │
│  │ discord-      │                               │
│  │ monitor       │                               │
│  │ (Python)      │                               │
│  └───────────────┘                               │
└────────────┬─────────────────────────────────────┘
             │ DB 連線 (SSL)
             ▼
    ┌─────────────────┐
    │  Neon 雲端 PG    │  ← 資料永久保存
    │  (Singapore)     │     docker down 不影響
    └─────────────────┘
```

### SL/TP 下單重試機制

止損單和止盈單使用 idempotent key（`newClientOrderId`）確保重試安全：

- 只在 IOException（網路斷線/timeout）時重試，HTTP 回應不重試
- 最多重試 2 次（間隔 1s → 3s）
- 全部失敗 → Discord 紅色告警

### 資料庫 (PostgreSQL 16)

Hibernate `ddl-auto: update` 自動管理 schema（只增不刪，不會丟資料）。

| 環境 | 位置 | 連線方式 |
|------|------|---------|
| **Prod** | Neon 雲端 (Singapore) | `DB_URL` in `.env.prod`，SSL 加密 |
| **Dev** | 本地 Docker container | `postgres:5432`，`--profile local-db` 啟動 |

#### 交易相關

**trades 表** — 交易主紀錄：

| 欄位類別 | 欄位 |
|---------|------|
| 基本 | tradeId, symbol, side, status (OPEN/CLOSED/CANCELLED) |
| 入場 | entryPrice, entryQuantity, entryOrderId, entryCommission |
| 出場 | exitPrice, exitQuantity, exitOrderId, exitTime, exitReason |
| 部分平倉 | totalClosedQuantity, remainingQuantity |
| 盈虧 | grossProfit, netProfit, commission |
| 保護單 | stopLoss, takeProfit |
| DCA | dcaCount |
| 訊號來源 | sourcePlatform, sourceChannelId, sourceGuildId, sourceAuthorName, sourceMessageId |
| 去重 | signalHash |
| 時間 | createdAt, updatedAt |

**trade_events 表** — 事件日誌：

| 事件類型 | 說明 |
|---------|------|
| ENTRY_PLACED | 入場單下單 |
| DCA_ENTRY | DCA 補倉 |
| CLOSE_PLACED | 主動平倉 |
| PARTIAL_CLOSE | 部分平倉（主動） |
| STREAM_CLOSE | WebSocket 全平倉（SL/TP 觸發） |
| STREAM_PARTIAL_CLOSE | WebSocket 部分平倉 |
| MOVE_SL | 止損移動 |
| CANCEL | 取消掛單 |
| SL_LOST | 止損單被取消（持倉失去保護） |
| TP_LOST | 止盈單被取消 |
| FAIL_SAFE | 安全機制觸發 |

#### SaaS 相關（骨架，JPA auto DDL）

**users 表** — 用戶帳號：

| 欄位 | 說明 |
|------|------|
| userId (UUID) | 主鍵 |
| email (unique) | 登入帳號 |
| passwordHash | BCrypt 加密密碼 |
| name | 顯示名稱 |
| role | USER / ADMIN |
| createdAt, updatedAt | 時間戳 |

**user_api_keys 表** — 用戶交易所 API Key（加密儲存）：

| 欄位 | 說明 |
|------|------|
| userId | 外鍵 → users |
| exchange | 交易所名稱（BINANCE） |
| encryptedApiKey | AES 加密後的 API Key |
| encryptedSecretKey | AES 加密後的 Secret Key |

**subscriptions 表** — 訂閱紀錄：

| 欄位 | 說明 |
|------|------|
| userId | 外鍵 → users |
| stripeCustomerId | Stripe 客戶 ID |
| stripeSubscriptionId | Stripe 訂閱 ID |
| planId, status | 方案 / 狀態（TRIALING/ACTIVE/CANCELLED/PAST_DUE） |
| currentPeriodStart, currentPeriodEnd | 訂閱週期 |

### 並發安全

- **SymbolLockRegistry**：`@Component` 共享鎖管理器，BinanceFuturesService 和 BinanceUserDataStreamService 共用同一把 per-symbol `ReentrantLock`，確保跨服務互斥
- **訊號去重**：signalHash + 5 分鐘窗口（side 為 null 時安全處理，DCA 用 "DCA" 替代）
- **NPE 防護**：MOVE_SL `newStopLoss=null`、DCA `stopLoss=null` 均有 null 安全檢查

### 時區

所有時間戳記（交易紀錄、事件日誌、Discord 通知）統一使用 **Asia/Taipei (UTC+8)** 時區。

---

## 開發路線圖

| 優先級 | 項目 | 狀態 |
|--------|------|------|
| P0 | 交易核心（開倉/平倉/風控/WebSocket/DCA） | ✅ 完成 |
| P0 | Discord 監聽 + AI 解析 | ✅ 完成 |
| P0 | 模組化拆分 | ✅ 完成 |
| P0 | PostgreSQL 遷移（替換 H2） | ✅ 完成 |
| P0 | Neon 雲端 DB（Prod 環境資料永久保存） | ✅ 完成 |
| P0 | Docker Dev/Prod 環境分離 | ✅ 完成 |
| P0 | Web Dashboard 前端（Next.js + shadcn/ui） | ✅ 完成 |
| P0 | 交易績效分析（回撤、連勝連敗、風報比、分組統計） | ✅ 完成 |
| P0 | 穩定性修復（NPE 防護 + 跨服務共享鎖 + DCA 防護） | ✅ 完成 |
| P0 | 每日報告優化（6 區塊：餘額/明細/風控/系統狀態） | ✅ 完成 |
| P1 | auth + user 模組實作（JWT 認證完整流程） | 🔨 骨架完成 |
| P1 | subscription 模組實作（Stripe 整合） | 🔨 骨架完成 |
| P1 | dashboard 模組實作（前端 API） | 🔨 骨架完成 |
| P2 | signal 模組（訊號廣播給多用戶） | 📋 計畫中 |
| P2 | per-user Binance WebSocket | 📋 計畫中 |
| P2 | VPS 部署（24/7 雲端運行） | 📋 計畫中 |
