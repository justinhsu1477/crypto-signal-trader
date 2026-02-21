# 環境變數完整參考手冊

> 本文件涵蓋 Crypto Signal Trader 所有環境變數的定義、預設值、各環境建議值及用途說明。
>
> 最後更新：2025-02-22

---

## 目錄

1. [Spring / 伺服器](#1-spring--伺服器)
2. [Binance API](#2-binance-api)
3. [風控參數（YAML-Only）](#3-風控參數yaml-only)
4. [多用戶模式](#4-多用戶模式)
5. [Discord Webhook](#5-discord-webhook)
6. [資料庫](#6-資料庫)
7. [JWT / 加密](#7-jwt--加密)
8. [AI 顧問](#8-ai-顧問)
9. [Stripe 訂閱](#9-stripe-訂閱)
10. [Monitor 認證](#10-monitor-認證)
11. [HikariCP 連線池](#11-hikaricp-連線池)

---

## 1. Spring / 伺服器

| 變數名稱 | 型別 | 預設值 | Dev | Prod | 說明 |
|----------|------|--------|-----|------|------|
| `SPRING_PROFILES_ACTIVE` | string | `dev` | `dev` | `prod` | Spring Boot profile；`dev` 使用 Testnet，`prod` 使用正式環境 |
| `SERVER_PORT` | int | `8080` | `8080` | `8080` | HTTP 伺服器端口 |

---

## 2. Binance API

| 變數名稱 | 型別 | 預設值 | Dev | Prod | 說明 |
|----------|------|--------|-----|------|------|
| `BINANCE_API_KEY` | string | — | Testnet Key | 正式 Key | Binance Futures API Key |
| `BINANCE_SECRET_KEY` | string | — | Testnet Secret | 正式 Secret | Binance Futures Secret Key |

- **Dev（Testnet）申請**：https://testnet.binancefuture.com → GitHub 登入 → API Management
- **Prod（正式）申請**：https://www.binance.com → 帳戶 → API 管理

> Base URL 由 profile 自動切換：
> - `dev` → `https://demo-fapi.binance.com`
> - `prod` → `https://fapi.binance.com`

---

## 3. 風控參數（YAML-Only）

以下參數直接在 `application.yml` 中設定，**不可由環境變數覆蓋**，以防止誤操作。

> 設定路徑：`binance.risk.*`
> Config Class：`com.trader.shared.config.RiskConfig`

| YAML Key | 型別 | 預設值 | 說明 |
|----------|------|--------|------|
| `binance.risk.risk-percent` | double | `0.20` | 單筆風險比例（1R = 帳戶餘額 × 此值） |
| `binance.risk.max-position-usdt` | double | `50000` | 單筆最大名目價值（USDT），防止窄止損產生超大倉位 |
| `binance.risk.max-daily-loss-usdt` | double | `2000` | 每日虧損熔斷上限（USDT） |
| `binance.risk.max-dca-per-symbol` | int | `3` | 同幣種最多加倉層數（含首次入場） |
| `binance.risk.dca-risk-multiplier` | double | `2.0` | 補倉風險倍數（2R = 首倉 2 倍風險金額） |
| `binance.risk.fixed-leverage` | int | `20` | 固定槓桿（逐倉 ISOLATED） |
| `binance.risk.allowed-symbols` | List | `[BTCUSDT]` | 允許的交易對白名單 |
| `binance.risk.default-symbol` | string | `BTCUSDT` | 訊號無幣種時的預設交易對 |
| `binance.risk.dedup-enabled` | boolean | `true` | 訊號去重開關（5 分鐘內同方向同幣種不重複） |

---

## 4. 多用戶模式

| 變數名稱 | 型別 | 預設值 | Dev | Prod | 說明 |
|----------|------|--------|-----|------|------|
| `MULTI_USER_ENABLED` | boolean | `false` | `false` | `true` | 多用戶模式總開關（唯一開關） |
| `TRADING_USER_ID` | string | `test-user` | `test-user` | _(空)_ | 當前交易用戶 ID（單用戶模式使用，多用戶模式忽略） |

### 單一開關設計

`MULTI_USER_ENABLED` 是控制多用戶行為的**唯一環境變數**，一個開關同時控制：

- 廣播跟單（Thread Pool 10 線程並行）
- Per-user 交易參數（TradeConfigResolver 查詢 UserTradeSettings）
- Per-user 查詢隔離（TradeRecordService 按 userId 過濾）

```
MULTI_USER_ENABLED=false（單用戶模式）
  ├── 交易參數：全部用全局 RiskConfig（零 DB 查詢）
  ├── 交易紀錄：全局查詢（不分用戶）
  └── 下單：直接用 TRADING_USER_ID 的 API Key

MULTI_USER_ENABLED=true（多用戶模式）
  ├── 交易參數：DB 查詢 UserTradeSettings → null 欄位 fallback RiskConfig
  ├── 交易紀錄：按 userId 隔離查詢
  └── 下單：Thread Pool 廣播給所有 autoTradeEnabled=true 的用戶
```

### Per-User 交易參數（多用戶模式啟用時）

| 參數 | 用途 | DB 欄位 | 全局 fallback |
|------|------|---------|-------------|
| 風險百分比 | 每筆用多少 % 餘額 | `risk_percent` | `binance.risk.risk-percent` |
| 最大倉位 (USDT) | 單筆名目價值上限 | `max_position_size_usdt` | `binance.risk.max-position-usdt` |
| 每日虧損上限 | 熔斷機制 | `daily_loss_limit_usdt` | `binance.risk.max-daily-loss-usdt` |
| 最大 DCA 層數 | 加倉次數上限 | `max_dca_layers` | `binance.risk.max-dca-per-symbol` |
| DCA 風險乘數 | 加倉倍率 | `dca_risk_multiplier` | `binance.risk.dca-risk-multiplier` |
| 最大槓桿 | 下單槓桿 | `max_leverage` | `binance.risk.fixed-leverage` |
| 允許交易對 | 白名單 | `allowed_symbols` (JSON) | `binance.risk.allowed-symbols` |
| 交易開關 | 啟用/停用 | `auto_trade_enabled` | 預設 true |

> **固定全局的參數**（不可自訂）：`dedup-enabled`、`default-symbol`

---

## 5. Discord Webhook

| 變數名稱 | 型別 | 預設值 | Dev | Prod | 說明 |
|----------|------|--------|-----|------|------|
| `DISCORD_WEBHOOK_URL` | string | _(空)_ | _(空)_ | webhook URL | 全局 Discord Webhook URL |
| `DISCORD_WEBHOOK_ENABLED` | boolean | `false` | `false` | `true` | 是否啟用 Discord 通知 |
| `DISCORD_WEBHOOK_PER_USER_ENABLED` | boolean | `true` | `false` | `true` | 是否使用用戶自定義 webhook |
| `DISCORD_WEBHOOK_FALLBACK` | boolean | `true` | `true` | `true` | 用戶無自定義 webhook 時是否 fallback 到全局 |

### Webhook 優先順序

```
用戶自定義 webhook (Dashboard 設定)
  │ 有 → 使用用戶 webhook
  │ 無 ↓
  └── DISCORD_WEBHOOK_FALLBACK=true ?
        │ 是 → 使用全局 DISCORD_WEBHOOK_URL
        │ 否 → 不發送通知
```

> 詳細說明請參考 `README_WEBHOOK_CONFIG.md`

---

## 6. 資料庫

| 變數名稱 | 型別 | 預設值 | Dev | Prod | 說明 |
|----------|------|--------|-----|------|------|
| `DB_URL` | string | `jdbc:postgresql://localhost:5432/trading` | localhost | Neon 雲端 URL | PostgreSQL 連線 URL |
| `DB_USERNAME` | string | `trading` | `trading` | Neon 用戶名 | 資料庫用戶名 |
| `DB_PASSWORD` | string | `trading` | `trading` | Neon 密碼 | 資料庫密碼 |

> Docker 內部使用 service name `postgres` 連線：`jdbc:postgresql://postgres:5432/trading`

---

## 7. JWT / 加密

| 變數名稱 | 型別 | 預設值 | Dev | Prod | 說明 |
|----------|------|--------|-----|------|------|
| `JWT_SECRET` | string | 開發用預設值 | 開發用預設值 | 強隨機字串 | JWT 簽名密鑰（至少 256 bits） |
| `AES_ENCRYPTION_KEY` | string | 開發用預設值 | 開發用預設值 | 強隨機字串 | AES-256 加密金鑰（用於加密用戶 API Key，必須 32 字元） |

### YAML-Only 相關設定

| YAML Key | 預設值 | 說明 |
|----------|--------|------|
| `jwt.expiration-ms` | `86400000` | JWT 過期時間（24 小時） |
| `jwt.refresh-expiration-ms` | `604800000` | Refresh Token 過期時間（7 天） |

---

## 8. AI 顧問

| 變數名稱 | 型別 | 預設值 | Dev | Prod | 說明 |
|----------|------|--------|-----|------|------|
| `ADVISOR_ENABLED` | boolean | `false` | `false` | `true` | 是否啟用 AI 交易顧問 |
| `GEMINI_API_KEY` | string | _(空)_ | _(空)_ | Gemini Key | Google Gemini API Key |

> API Key 申請：https://aistudio.google.com/apikey

### YAML-Only 相關設定

| YAML Key | 預設值 | 說明 |
|----------|--------|------|
| `advisor.gemini-model` | `gemini-2.0-flash` | 使用的 Gemini 模型 |
| `advisor.cron-expression` | `0 0 2,6,10,14,18,22 * * *` | 排程（每天 6 次） |
| `advisor.max-response-tokens` | `1024` | 回應最大 token 數 |
| `advisor.recent-trades-count` | `10` | 分析最近幾筆交易 |
| `advisor.temperature-value` | `0.7` | 模型溫度（創造性） |

---

## 9. Stripe 訂閱

| 變數名稱 | 型別 | 預設值 | Dev | Prod | 說明 |
|----------|------|--------|-----|------|------|
| `STRIPE_SECRET_KEY` | string | `sk_test_placeholder` | 測試 Key | 正式 Key | Stripe Secret Key |
| `STRIPE_WEBHOOK_SECRET` | string | `whsec_placeholder` | 測試 Secret | 正式 Secret | Stripe Webhook 驗證密鑰 |

---

## 10. Monitor 認證

| 變數名稱 | 型別 | 預設值 | Dev | Prod | 說明 |
|----------|------|--------|-----|------|------|
| `MONITOR_API_KEY` | string | _(空)_ | 開發用 Key | 強隨機字串（32+ 字元） | Python Discord Monitor 對 Spring Boot API 的認證金鑰 |

---

## 11. HikariCP 連線池

| 變數名稱 | 型別 | 預設值 | Dev | Prod | 說明 |
|----------|------|--------|-----|------|------|
| `HIKARI_MAX_POOL_SIZE` | int | `20` | `20` | `20` | HikariCP 最大連線數（配合廣播線程池） |

### YAML-Only 相關設定

| YAML Key | 預設值 | 說明 |
|----------|--------|------|
| `spring.datasource.hikari.minimum-idle` | `5` | 最小閒置連線數 |
| `spring.datasource.hikari.connection-timeout` | `10000` | 連線逾時（10 秒） |
| `spring.datasource.hikari.idle-timeout` | `300000` | 閒置回收時間（5 分鐘） |

---

## Discord Monitor 專用（非 Spring Boot）

以下變數供 Python Discord Monitor 使用，不影響 Spring Boot 應用程式：

| 變數名稱 | 說明 |
|----------|------|
| `DISCORD_CHANNEL_IDS` | 監聽的 Discord 頻道 ID（多個用逗號分隔） |
| `DISCORD_GUILD_IDS` | Discord 伺服器 ID（選填） |

---

## 環境變數 vs YAML 設定對照

所有可由環境變數覆蓋的參數，在 `application.yml` 中以 `${ENV_VAR:default}` 格式定義。

| 總計 | 數量 |
|------|------|
| 環境變數（可覆蓋） | 20 個 |
| YAML-Only（不可覆蓋） | 18 個 |
| Discord Monitor 專用 | 2 個 |

---

## 相關文檔

- [`README_WEBHOOK_CONFIG.md`](../README_WEBHOOK_CONFIG.md) — Discord Webhook 三層環境配置指南
- [`README_AUTO_TRADE_CONFIG.md`](../README_AUTO_TRADE_CONFIG.md) — 自動跟單完整文檔
- [`docs/FRONTEND_SETTINGS_IMPLEMENTATION.md`](FRONTEND_SETTINGS_IMPLEMENTATION.md) — 前端 Settings 實作指南
- [`docs/architecture-roadmap.md`](architecture-roadmap.md) — 架構路線圖
