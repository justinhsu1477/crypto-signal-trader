# Crypto Signal Trader

Discord 交易訊號自動跟單系統 — 監聽 Discord 頻道訊號，AI 解析後自動在 Binance Futures 下單。

支援**單人模式**（個人自動交易）與**多用戶模式**（SaaS 訊號廣播跟單）。

## 系統架構

```
Discord Desktop (CDP 模式)
    │  Chrome DevTools Protocol
    ▼
Python Monitor (discord-monitor/)
    │  過濾頻道 → Gemini AI 解析 JSON（失敗 fallback regex）
    │  心跳回報 → Java API（每 30 秒）
    ▼
Spring Boot API (Docker, port 8080)
    │  風控檢查 → Binance 下單
    │  → DCA 補倉 + 部分平倉 + SL/TP 保護
    │  → Discord Webhook 通知（per-user）
    │  → 訊號廣播跟單（多用戶模式）
    ▼
Binance Futures API
    │
    ▼
WebSocket User Data Stream
    → SL/TP 觸發 → 真實數據寫入 DB + PnL 通知
    → SL 被取消 → Discord 告警（持倉裸奔）

資料庫: Neon 雲端 PostgreSQL (Singapore)
前端:   Web Dashboard (Next.js + shadcn/ui)
```

## 模組架構

```
com.trader/
├── trading/         # 交易核心（開倉/平倉/風控/WebSocket/廣播跟單）
├── shared/          # 共用元件（Config/DTO/工具類）
├── notification/    # 通知（Discord Webhook，per-user 支援）
├── auth/            # 認證（JWT 登入/註冊 + Monitor API Key）
├── user/            # 用戶（帳號/加密 API Key/交易參數/Discord Webhook）
├── subscription/    # 訂閱計費（Stripe Payment Links + Webhook）
├── dashboard/       # Dashboard API（績效分析/持倉/交易紀錄）
└── advisor/         # AI 交易顧問（Gemini 定時分析）
```

### 模組依賴

```
auth → user    subscription → user    dashboard → trading, user, subscription
trading → shared, notification         shared → (nothing)    user → (nothing)
```

**規則：** 不可循環依賴，不可反向依賴。

---

## 快速開始

### 環境變數

```bash
cp .env.example .env      # 複製範本，填入 API Keys
```

關鍵變數：

| 變數 | 說明 |
|------|------|
| `BINANCE_API_KEY` / `SECRET_KEY` | 幣安 API |
| `DISCORD_WEBHOOK_URL` | Discord 通知 |
| `GEMINI_API_KEY` | AI 訊號解析 |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | PostgreSQL (Neon) |
| `MULTI_USER_ENABLED` | `false`=單人, `true`=多用戶 |
| `TRADING_USER_ID` | 單人模式的用戶 ID |
| `JWT_SECRET` / `AES_ENCRYPTION_KEY` | 認證/加密 |

### 啟動 Discord（CDP 模式）

```bash
# 先關閉現有 Discord
killall Discord 2>/dev/null

# 用 CDP 模式重新啟動
/Applications/Discord.app/Contents/MacOS/Discord --remote-debugging-port=9222

# 等 Discord 完全載入後，確認 CDP 可連
curl http://127.0.0.1:9222/json
```

### 啟動

```bash
# Prod（正式, Neon 雲端 DB）
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build

# Dev（Testnet, 本地 PostgreSQL）
docker compose -f docker-compose.yml -f docker-compose.dev.yml --profile local-db up -d --build
```

### 驗證

```bash
curl http://localhost:8080/api/balance
curl http://localhost:8080/api/monitor-status
curl http://localhost:8080/api/stream-status
```

---

## 交易功能

### 以損定倉 (Fixed Fractional Risk)

每筆交易先決定願意虧多少，再反推倉位大小：

```
1R = 帳戶餘額 × risk-percent
數量 = 1R / |入場價 - 止損價|
DCA 補倉 = 2R / |入場價 - 止損價|
```

**特性：** 止損越窄→倉位越大、帳戶縮水→自動縮倉、DCA 用 2R 加碼。

### 三層倉位保護

| 保護層 | 作用 |
|--------|------|
| 名目 cap | 超過 `max-position-usdt` 就縮小 |
| 保證金 cap | 保證金超過餘額 90% 就縮小 |
| 最低下單量 | 名目 < 5 USDT 拒絕 |

### DCA 補倉

| 項目 | 說明 |
|------|------|
| 最大層數 | `max-dca-per-symbol`（預設 3 = 首倉 + 2 次補倉） |
| 補倉倉位 | 2R（`dca-risk-multiplier = 2.0`） |
| 方向自動判斷 | 跟隨既有持倉 |
| SL/TP 重掛 | 補倉後自動更新 |

### 部分平倉

`close_ratio: 0.5` → 平掉 50%，剩餘自動重掛 SL/TP。搭配 `new_stop_loss` 可做成本保護。

### 10 層風控

| # | 檢查 |
|---|------|
| 1 | 交易對白名單 |
| 1b | 帳戶餘額查詢（失敗直接拒絕） |
| 1c | 每日虧損熔斷 |
| 2 | 最大持倉數（DCA 層數限制） |
| 2b | 重複掛單檢查 |
| 2c | 訊號去重（5 分鐘窗口） |
| 3 | 止損必填 |
| 4 | 方向驗證（SL 不能在錯誤側） |
| 5 | 價格偏離檢查（>10% 拒絕） |
| 7 | 三層倉位保護 |

### Fail-Safe 安全機制

SL 下單失敗 → 取消入場單 → 若失敗 → 市價平倉 → 若全失敗 → Discord 紅色告警。

---

## 多用戶模式

透過 `MULTI_USER_ENABLED=true` 啟用，支援 SaaS 訊號廣播跟單。

### 核心機制

| 機制 | 單人模式 (`false`) | 多用戶模式 (`true`) |
|------|-------------------|-------------------|
| DB 查詢 | 全局（不分用戶） | 按 userId 隔離 |
| 交易參數 | 全局 RiskConfig | per-user UserTradeSettings（fallback RiskConfig） |
| 去重 | 全局 hash | Signal-level 全局 + Execution-level per-user |
| Discord 通知 | 全局 webhook | per-user webhook（fallback 全局） |
| 帳戶餘額 | 全局 API Key | per-user 加密 API Key |
| 每日摘要 | 全局查詢+全局 webhook | 遍歷用戶→個人查詢+個人 webhook |
| WebSocket | 單連線 | 單連線（per-user 規劃中） |

### 廣播跟單 (BroadcastTradeService)

ADMIN 發送訊號 → 系統遍歷所有 `enabled + autoTradeEnabled + hasApiKey` 的用戶 → 各自用 per-user API Key 下單。

- 共用線程池（core=10, max=50），30 秒超時
- 兩層去重：Signal-level（全局入口）+ Execution-level（per-user hash）
- 一個用戶失敗不影響其他用戶

### Per-User 交易參數 (TradeConfigResolver)

多用戶模式下，每個用戶可自訂 risk%、max position、daily loss limit、DCA 層數、槓桿等。未設定的參數 fallback 到全局 RiskConfig。

---

## 監控系統

### 心跳機制

Python monitor 每 30 秒回報心跳。Java 偵測：心跳停止 >90 秒（Python 掛了）、`status=reconnecting`（Discord 斷了）。

### WebSocket User Data Stream

| 事件 | 處理 |
|------|------|
| SL/TP 觸發 | 真實出場價+手續費寫入 DB + PnL 通知 |
| SL/TP 部分觸發 | 追蹤 remainingQuantity，維持 OPEN |
| SL 被取消 | 🚨 告警（持倉裸奔） |
| 斷線 | 指數退避重連（1s→60s，上限 20 次） |

**連線維護：** listenKey 每 30 分鐘延長，ping interval 20 秒。

### 每日排程

| 時間 | 排程 | 多用戶模式 |
|------|------|-----------|
| 07:55 | 殭屍清理（比對幣安持倉） | 遍歷用戶，per-user API Key 查持倉 |
| 08:00 | 每日摘要（6 大區塊） | 遍歷用戶，per-user 查詢+webhook |

**每日摘要 6 大區塊：** 帳戶餘額、昨日交易明細、當前持倉、今日風控、累計統計、系統狀態。

### AI 交易顧問

Gemini 2.0 Flash 定時分析近期交易表現（每日 6 次），提供交易建議。

---

## 安全架構

### 認證方式

| 方式 | 用途 |
|------|------|
| JWT (Bearer token) | 用戶登入後所有 API 呼叫 |
| Monitor API Key (X-Api-Key) | Python monitor 內部服務 |
| Stripe Webhook | 訂閱回調（公開端點） |

### 端點權限

| 端點 | 權限 |
|------|------|
| `/api/auth/**`, `/api/health` | 公開 |
| `/api/execute-signal`, `/api/broadcast-trade`, `/api/admin/**` | ADMIN |
| `/api/execute-trade`, `/api/dashboard/**`, `/api/trades/**` | 認證用戶 |
| `/api/subscription/webhook` | 公開（Stripe） |

### 資料安全

- 密碼：BCrypt 加密
- API Key：AES-256-GCM 加密存儲
- JWT：含 role claim，支援 refresh token 旋轉

---

## REST API

### 交易

| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/execute-trade` | POST | 結構化 JSON 下單（ENTRY/CLOSE/MOVE_SL/CANCEL） |
| `/api/execute-signal` | POST | 原始文字解析+下單 (ADMIN) |
| `/api/broadcast-trade` | POST | 廣播跟單給所有用戶 (ADMIN) |
| `/api/parse-signal` | POST | 測試解析（不下單）(ADMIN) |
| `/api/balance` | GET | 帳戶餘額 |
| `/api/positions` | GET | 當前持倉 |
| `/api/trades` | GET | 交易紀錄（`?status=OPEN/CLOSED`） |
| `/api/trades/{id}` | GET | 單筆詳情 |
| `/api/trades/{id}/events` | GET | 事件日誌 |
| `/api/stats/summary` | GET | 盈虧統計摘要 |
| `/api/admin/cleanup-trades` | POST | 手動殭屍清理 (ADMIN) |

### Dashboard

| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/dashboard/overview` | GET | 持倉+風控+訂閱摘要 |
| `/api/dashboard/performance` | GET | 績效分析（勝率/PF/回撤/分組統計） |
| `/api/dashboard/trades` | GET | 交易紀錄（分頁） |
| `/api/dashboard/trade-settings` | GET/PUT | 交易參數管理 |
| `/api/dashboard/discord-webhooks` | GET/POST | Discord Webhook 管理 |
| `/api/dashboard/auto-trade-status` | GET/POST | 自動跟單開關 |

### 認證 / 用戶

| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/auth/register` | POST | 註冊 |
| `/api/auth/login` | POST | 登入（回傳 JWT + refresh token） |
| `/api/auth/refresh` | POST | Token 刷新 |
| `/api/user/me` | GET | 當前用戶資訊 |
| `/api/user/api-keys` | GET/PUT | API Key 管理（加密存儲） |

### 訂閱

| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/subscription/plans` | GET | 可用方案 |
| `/api/subscription/status` | GET | 訂閱狀態 |
| `/api/subscription/cancel` | POST | 取消訂閱 |
| `/api/subscription/upgrade` | POST | 升降級 |

### 監控

| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/heartbeat` | POST | Python monitor 心跳 |
| `/api/monitor-status` | GET | Monitor + AI 狀態 |
| `/api/stream-status` | GET | WebSocket 連線狀態 |

### execute-trade 範例

```bash
# ENTRY 開倉
curl -X POST http://localhost:8080/api/execute-trade \
  -H "Content-Type: application/json" \
  -d '{"action":"ENTRY","symbol":"BTCUSDT","side":"LONG","entry_price":95000,"stop_loss":93000,"take_profit":98000}'

# DCA 補倉
curl -X POST http://localhost:8080/api/execute-trade \
  -d '{"action":"ENTRY","symbol":"BTCUSDT","is_dca":true,"entry_price":93000,"new_stop_loss":91000}'

# 平倉 50% + 成本保護
curl -X POST http://localhost:8080/api/execute-trade \
  -d '{"action":"CLOSE","symbol":"BTCUSDT","close_ratio":0.5,"new_stop_loss":null}'

# 全部平倉
curl -X POST http://localhost:8080/api/execute-trade \
  -d '{"action":"CLOSE","symbol":"BTCUSDT"}'
```

---

## 設定

### 風控參數 (`application.yml`)

```yaml
binance:
  risk:
    risk-percent: 0.20            # 單筆風險比例 (20%)
    max-position-usdt: 50000      # 單筆最大名目價值
    max-daily-loss-usdt: 2000     # 每日虧損熔斷上限
    max-dca-per-symbol: 3         # 同幣種最多 3 層
    dca-risk-multiplier: 2.0      # 補倉 2R
    fixed-leverage: 20            # 逐倉槓桿
    allowed-symbols:
      - BTCUSDT
```

### 帳戶規模建議

| 帳戶 | risk-percent | max-daily-loss |
|------|-------------|---------------|
| 100~500 USDT | 10~20% | 200~500 |
| 500~2,000 USDT | 10~20% | 500~1,000 |
| 2,000~10,000 USDT | 5~20% | 1,000~3,000 |
| 10,000+ USDT | 2~10% | 2,000~5,000 |

---

## 技術棧

| 元件 | 技術 |
|------|------|
| 交易引擎 | Java 17 + Spring Boot 3.2.5 |
| 認證 | Spring Security + JWT (JJWT 0.12.6) |
| AI 解析 | Python 3 + Gemini 2.0 Flash |
| 前端 | Next.js 14 + React + shadcn/ui |
| 資料庫 | PostgreSQL 16 (Neon 雲端) + Flyway 遷移 |
| API 通訊 | OkHttp + WebSocket |
| 計費 | Stripe (Payment Links + Webhook) |
| 加密 | AES-256-GCM (API Key) + BCrypt (密碼) |
| 部署 | Docker Compose (Dev/Prod 分離) |
| 測試 | JUnit 5 + Mockito — **614 tests passed** |

### 資料庫

Flyway 管理 schema 遷移（7 個版本），`ddl-auto: validate` 確保 entity 與 DB 一致。

| 表 | 說明 |
|---|------|
| `trades` | 交易主紀錄（入場/出場/盈虧/DCA/訊號來源） |
| `trade_events` | 事件日誌（ENTRY/CLOSE/SL_LOST/FAIL_SAFE 等） |
| `users` | 用戶帳號 |
| `user_api_keys` | 加密 API Key |
| `user_trade_settings` | per-user 交易參數 |
| `user_discord_webhooks` | per-user Discord webhook |
| `subscriptions` | Stripe 訂閱紀錄 |
| `subscription_plans` | 方案定義 |
| `payment_history` | 付款紀錄 |

---

## 開發路線圖

| 狀態 | 項目 |
|------|------|
| ✅ | 交易核心（開倉/平倉/風控/DCA/WebSocket/部分平倉） |
| ✅ | Discord 監聽 + Gemini AI 解析 |
| ✅ | 多用戶架構（userId 隔離/per-user config/廣播跟單/兩層去重） |
| ✅ | 每日摘要 per-user 改造（per-user API Key 餘額/per-user webhook） |
| ✅ | 認證系統（JWT + API Key + RBAC） |
| ✅ | 用戶管理（加密 API Key + 交易參數 + Discord Webhook） |
| ✅ | Dashboard API（績效分析/回撤/分組統計/20+ 指標） |
| ✅ | 訂閱計費（Stripe Payment Links + Webhook） |
| ✅ | AI 交易顧問（Gemini 定時分析） |
| ✅ | Neon 雲端 DB + Flyway 遷移 |
| ✅ | Docker Dev/Prod 環境分離 |
| ✅ | Web Dashboard 前端 (Next.js + shadcn/ui) |
| 📋 | RabbitMQ 非同步化（目前 Thread Pool 同步） |
| 📋 | Per-user Binance WebSocket |
| 📋 | VPS 部署（24/7 雲端運行） |
