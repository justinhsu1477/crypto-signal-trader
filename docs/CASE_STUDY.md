# Crypto Signal Trader — Engineering Case Study

> 這份文件給「想評估這個專案的技術深度」的讀者看 — 重點不在 README 那種 quick-start，而是**設計決策、踩過的坑、學到的事**。
>
> 如果只想看怎麼 run 起來，請看 [`README.md`](../README.md)。

---

## 🎯 TL;DR

把訊號群組（Discord）的人為跟單變成 SaaS 自動執行系統。

- **21** 個活躍用戶（Binance Futures），**151** 個訊號源，月處理 **5,800+** 則 Discord 訊息
- **2,428** 個 Java 單元測試 + **329** 個 Python 測試 + **30** case AI eval harness（每週一 09:00 自動跑）
- **51** 個 Flyway migration、**596** 個 commit、單人開發 3 個月（2026-02 → 05）
- 從訊號發出到下單成交 < 3 秒（含 AI 解析 + 10 層風控 + 多用戶並行下單）

---

## 1. Problem Definition — 為什麼這個專案存在

### 痛點

Discord 訊號群組是中文圈跟單交易的主流形式。但人為跟單有 5 個痛點：

1. **手速跟不上** — 群主說「BTC 多 78000」，你看到 → 開幣安 → 算倉位 → 下單，至少 30 秒。市價已經 79000
2. **半夜漏單** — 群主美西時間發訊號，亞洲用戶睡死
3. **複合訊號處理錯誤** — 「BTC 平倉、ETH 再進」這種一句兩動作，新手只做一個
4. **多群主分散注意** — 同時跟 5 個群，互相衝突的訊號要快速判斷
5. **盈虧計算難** — 群主貼盈利圖刺激，但用戶自己跟對幾單 / 賺多少很難對

### 既有方案的缺口

- **3Commas / Cornix**：英文圈為主，**中文 LLM 解析能力差**（不認識「進多」「平掉」「TP-SL 修改」這類群內黑話）
- **Discord Bot**：要訊號源**主動架** bot 進群（群主拒絕、或攻擊面變大）
- **MetaMask snap / Telegram bot**：UI 不是中文圈習慣

→ 機會：「用 user 自己的 Discord client 監聽訊號（CDP 注入），LLM 解析後送雲端執行」這條路線**沒人做精細**。

### 商業假設

- **付費意願**：跟單群本來就是付費入會（月 50-500 USDT），我加 30 USDT/月當「執行層」用戶會買單
- **技術 moat**：訊號源治理（per-source prompt + audit chain）、AI eval harness（防 LLM 升級造成回歸）

---

## 2. System Architecture

### High-level 拓樸

```
┌──────────────────────────────────────────────────────────────┐
│                      USER 本地 Mac/Linux                     │
│                                                              │
│  Chrome (Discord Web)                                        │
│       ↓ CDP 9222 (WebSocket protocol)                        │
│  ┌──────────────────────────────────────────┐                │
│  │ Python discord-monitor                   │                │
│  │ • CDP hook 抓 MESSAGE_CREATE / UPDATE    │                │
│  │ • channel/guild/author 過濾              │                │
│  │ • Gemini AI 解析（含圖片 multimodal）    │                │
│  │ • 失敗訊號本地佇列（aiohttp 重試）        │                │
│  └─────────────────┬────────────────────────┘                │
└────────────────────┼─────────────────────────────────────────┘
                     │ HTTPS REST (X-Api-Key)
                     │ gRPC streaming (config push)
                     ↓
┌──────────────────────────────────────────────────────────────┐
│                    雲端 VM (DigitalOcean)                    │
│  ┌──────────────────────────────────────────┐                │
│  │ Caddy (自動 Let's Encrypt + reverse proxy)│                │
│  └────────────┬───────────────┬─────────────┘                │
│               ↓               ↓                              │
│   ┌────────────────┐ ┌──────────────────┐                    │
│   │ Spring Boot 3  │ │ Next.js 14       │                    │
│   │ trading-api    │ │ web-dashboard    │                    │
│   │                │ │ (admin UI)       │                    │
│   │ • 10 層風控    │ └──────────────────┘                    │
│   │ • broadcast    │                                         │
│   │ • per-user WS  │      ┌──────────┐                       │
│   │ • mirror webhk │      │ RabbitMQ │                       │
│   └────┬─────┬─────┘      │ (內部 q) │                       │
│        │     │            └──────────┘                       │
│        │     │            ┌──────────┐                       │
│        │     │            │ Redis    │                       │
│        │     │            │ (dedup)  │                       │
│        │     │            └──────────┘                       │
└────────┼─────┼──────────────────────────────────────────────┘
         │     │
         ↓     ↓
   ┌─────────────────┐       ┌──────────────────┐
   │ Neon Postgres   │       │ Binance Futures  │
   │ + pgvector      │       │ per-user REST    │
   │ (serverless)    │       │ + WebSocket      │
   └─────────────────┘       └──────────────────┘
```

### 為什麼這樣切（decision log）

| 決策 | 為什麼 | 沒選的方案 + 為什麼 |
|------|--------|---------------------|
| **CDP 注入而非 Discord Bot** | 訊號源群主**不會**讓你加 bot 進去；用 user 自己的 Discord session 監聽，**從訊號源角度完全隱形** | Bot API（要群主授權、容易被踢）/ Selenium scrape（DOM 變動易壞）|
| **Python 本地、Java 雲端** | CDP 必須 attach 到本地 Chrome（無法雲端跑）。但跟單執行屬高可用業務，必須雲端 | 全部本地（單點故障）/ 全部雲端（無法接到 Discord）|
| **Spring Boot + Postgres**（vs Node + Mongo）| 1. 強型別 entity 對齊 11 層風控的複雜邏輯 2. Hibernate `@Transactional` 處理金錢操作天然合適 3. JVM 連線池 + WebSocket 穩定 | Node 沒 type 系統會難維護 / Mongo 對 multi-row tx 不友善 |
| **Gemini 2.5 Flash**（vs GPT-4）| 1. 同等中文解析品質、**價格 1/30** 2. multimodal 對「盈利圖」邊角圖片 OCR 更穩 3. 30 RPM 免費額足夠（每月支出 < $5）| GPT-4o（成本高）/ Claude（API key 較難取得）|
| **per-user Binance WebSocket** | 隔離 — 一個 user listenKey 過期不影響其他 user；可 per-user 分析 | 單一 master stream（複雜化路由邏輯、用戶資料隔離難做）|
| **Singleton Testcontainer**（vs per-test）| Test 啟動 PostgreSQL 要 10-15 秒，2428 個 test 等死 | per-class container（仍然慢）|
| **append-only prompt 版本鏈** | LLM prompt 是 high-stakes，必須能 audit「誰、什麼時間、改成什麼、為什麼」+ 1-click rollback | 直接 update prompt 表（無歷史 = 無法 rollback）|
| **SHADOW 預設 trade_mode for 新 source** | 訊號源信任建立要時間。新源默 only-archive，可疑訊號**不會誤觸 21 個用戶下單** | AUTO 預設（高風險）|

---

## 3. Key Tradeoffs

### Tradeoff 1: Real-time vs Reliability — 訊號處理

> **選擇**：fire-and-forget archive POST + 真正下單走 RabbitMQ queue

**為什麼**：
- Archive 失敗（網路抖動）= 漏一筆 audit log，影響低
- 下單失敗 = 用戶虧錢，影響高

→ Archive 用 async aiohttp，failure 直接 swallow + 寫 local log；下單走 RabbitMQ + Spring Retry，3 次失敗才 dead-letter queue。

**代價**：Archive 漏單會讓 eval harness 偶爾少 case，可接受。

### Tradeoff 2: Centralized vs Per-user state

> **選擇**：per-user Binance API session（每用戶獨立 ApiKey + WebSocket + listenKey 維護）

**為什麼**：
- 21 個用戶 = 21 個 WebSocket。每 user 自己 listenKey、自己 reconnect
- 共用 master account 風控做不到（用戶 A 開倉影響不到用戶 B 的爆倉計算）
- Binance API 限速是 per-key，多 key = 並行能力放大

**代價**：21 個常駐 WS 連線（JVM heap ~50MB 額外開銷）+ 多 listenKey 維護複雜度（已踩 5/13 capture stall 那次坑）。

### Tradeoff 3: Strict schema validation vs migration agility

> **選擇**：Hibernate `ddl-auto=validate` on prod + 整合測試 gate

**事件背景**：V47 migration 用 `CHAR(16)` 存 SHA-256 前 16 hex，但 JPA `@Column(length=16)` 預期 `VARCHAR`。Hibernate 6 嚴格 mode 啟動就炸，prod 5xx 1.5 小時。

→ 加 `SchemaValidationIntegrationTest`：CI 用 Testcontainers + 真 Flyway migration + ddl-auto=validate，跑進 Spring context 就算 pass。**未來 entity 改動跟 migration 不同步 → CI 紅**。

**代價**：integration test 慢 1 分鐘、必須維護 V19.1 test-only seed migration（V20 FK 需要的 placeholder user）。

### Tradeoff 4: Eval cost vs regression detection

> **選擇**：每週一 09:00 UTC 跑 30-case eval（不是 PR-time gate）

**為什麼**：
- PR-time gate = 每次推 code 都跑 = $0.02 × 100 次/月 ≈ $2/月 + 拖慢 CI
- 週期 cron = $0.05/run × 4 = $0.20/月 + 異常時 Discord 警報

**已實現**：`eval-weekly.yml` workflow + `format_report.py` → emoji-tier embed POST 到 admin Discord（✅ ≥95% / ⚠️ 80-95% / 🔴 <80%）。

### Tradeoff 5: AI parsing reliability vs cost

> **選擇**：Gemini 2.5 Flash 為主 + 圖片走 flash multimodal（不升 pro）

**為什麼**：
- 30 case eval 在 flash 上 100% pass，沒理由貴一個 order of magnitude
- Pro 在「複合動作 / messy 訊號」優勢只 +3-5% 但 cost 20x

**未來**：發現某 category 掉到 80% 以下時，**fallback chain**（flash 失敗 → pro 重試）才動。

---

## 4. Real Failure Cases (這節是 cover letter)

### Case 1: V47/V51 — CHAR vs VARCHAR — prod 5xx 1.5 小時

**症狀**：deploy 完 V47 migration 後，prod startup 直接 fail，所有 user 看到 502。

**根因**：
- V47 SQL: `ALTER TABLE admin_audit_log ADD COLUMN before_hash CHAR(16)`
- Entity: `@Column(name="before_hash", length=16)` → Hibernate 預期 `VARCHAR(16)`
- prod 開 `ddl-auto=validate` → 啟動時比對失敗 → SchemaManagementException → Spring context 不起來

**為什麼測不到**：既有 integration test 用 `ddl-auto=create-drop`，**entity 自己生 schema**，永遠看不到 mismatch。

**修正**：
1. Hotfix：V51 ALTER COLUMN → VARCHAR(16)
2. 防禦：寫 `SchemaValidationIntegrationTest` — 用 Testcontainers + 真 Flyway + ddl-auto=validate，作為 CI 必過 check

**學到**：「test 跑得過 ≠ prod 跑得起來」。staging 環境用法要對齊 prod，不只 unit test 要綠。

### Case 2: gRPC Port 衝突讓 schema 測試**靜默變空殼**

**症狀**：Schema validation test 寫好 + CI overall 顯示綠。**但根本沒在驗證**。

**根因**：
- 新 profile `application-schema-validation-test.yml` 沒覆蓋 `grpc.server.port`
- Spring 啟動時 gRPC server 想 bind 9090 → 已被佔用 → throw `GrpcServerLifecycle` exception
- Spring context 起不來 → Hibernate `ddl-auto=validate` **連跑都沒跑** → test 顯示 fail
- 但 ci.yml `continue-on-error: true` 設定吞掉 → overall conclusion=success → 沒人發現

**被抓到的方式**：寫 **negative test** — 故意改 entity 加假欄位 → push 上 CI 看會不會 fail：
- 第一次 push: integration test fail（gRPC error，不是 schema mismatch）→ 發現空殼
- 加 `grpc.server.port: -1` 後 push: integration test fail（**Schema-validation: missing column** ✓）
- Revert entity + 拿掉 `continue-on-error`：CI gate 真正啟動

**學到**：「watching test fail」是 TDD 的精神 — 沒看過紅的綠不算綠。每個防護網都要主動戳破驗一次。

### Case 3: 圖片 mirror 失蹤

**症狀**：陳哥群裡有圖片的訊息 mirror 到個人 server 只剩 footer，圖片不見。

**追因順序**：
1. 先懷疑 Java buildPayload 漏 embed.image → curl Discord webhook 直接含 image URL → ✅ 出圖 → Java 端 OK
2. 改懷疑 Java mirror service 收不到 attachment_url → POST `/api/discord-messages` 加上 attachment_url → ✅ mirror 出圖 → Java 完全 OK
3. 切到 Python：deployed monitor commit 早於 09b3311（attachment_url 欄位加入 commit）→ **舊版 Python 根本沒在送這個欄位給 Java**

**根因**：deployment 沒重新 pull/restart Python monitor，code is on git but running version is stale.

**學到**：版本一致性 not free — Java 跟 Python 不同步部署時，必須有 heartbeat 帶 monitor_version 報回 server 才能 detect。

### Case 4: AI parser 誤判「事後報告」為 CLOSE 訊號

**症狀**：陳哥發「BTC 剩余半倉限价已经出发，均价78000附近」← **這是事後報告**（已經發生的成交描述）。AI 解析成 `CLOSE` action → 21 個用戶被自動平倉。

**根因**：Gemini 看到「出发、均价、已经」這些動詞 + 數字，預設當「現在要平倉」訊號。

**修正**：prompt 加 rule 49 — 「事後報告語法」識別清單（「已经」「均价」「剛剛」等過去時態詞 + 觀察數字）→ 強制歸 INFO。

**驗證**：eval harness 加 1 case「事後語法不可判 CLOSE」。

**學到**：LLM 解析中文時態極弱。anything 不是 imperative（「平掉」「進場」）都該 default INFO，提高精準度大於 recall。

### Case 5: Password reset link 指 localhost

**症狀**：用戶按「忘記密碼」收到信，link 點下去 → `http://localhost:3000/reset-password?token=xxx` → 404。

**根因**：`application.yml` `app-base-url: ${APP_BASE_URL:http://localhost:3000}`，prod env var 沒設 → 用 fallback。

**Fix**：1 行 env var 加進 `.env.prod` + force-recreate trading-api。

**學到**：env var 預設值是「dev 友善但 prod 危險」的雙面刃。給 prod 危險的預設應該直接 fail-fast（無預設、未設就拒啟動），不要 silent fallback。

---

## 5. Testing Strategy

### Pyramid

```
                   ┌──────────────────┐
                   │ AI Eval harness  │  30 case / weekly cron
                   │  (Gemini API)    │
                   └──────────────────┘
                  ╱                    ╲
                ╱ ────────────────────── ╲
              ╱ Integration Test (Tc + PG) ╲   ~30 個 IT class
            ╱   ────────────────────────────  ╲
          ╱        Unit (Java + Python)         ╲   2,428 + 329 個
        ╱      ──────────────────────────────────╲
       Linting + gitleaks + frontend build           CI Layer 0
```

### TDD 在這專案的實際做法

> "If you didn't watch the test fail, you don't know if it tests the right thing."

每個 feature 走流程：
1. **Red**：寫 failing test，run 看到「expected X, got null」之類
2. **Green**：寫最少 code 過 test
3. **Refactor**：清理重複

**踩過的反例**（Case 2 那個）：寫好 schema validation test 沒看過紅 → 空殼。事後補 negative test 才抓到。

### Singleton Container Pattern

```java
private static final PostgreSQLContainer<?> POSTGRES =
    new PostgreSQLContainer<>("pgvector/pgvector:pg16")
        .withDatabaseName("test")
        .withUsername("test")
        .withPassword("test");

static {
    POSTGRES.start();  // class-loaded 一次，整個 test JVM 共用
}
```

**為什麼**：176 個 test class 各起一個 container = 30 分鐘以上。共用一個 + 每 test 自己 cleanup data → 90 秒整套跑完。

**踩過的坑**：2026-05-13 capture stall — 第一個 IT class 跑完 close container，第二個 IT class try 重啟同 port → bind fail。修正：static 階段 start，永不 close。

### AI Eval Harness

`discord-monitor/eval/runner.py` 跑 30 個 hand-curated case：
- 10 個 entry_text（純文字進場）
- 5 個 compound（複合動作）
- 5 個 info（聊天訊息不該誤判）
- 3 個 close_only / 2 個 move_sl_only
- 3 個 eth_filter（過去常誤分）
- 2 個 messy（emoji / 多行 / 圖文混雜）

每週一 09:00 UTC 自動跑 → Discord webhook 報告：
- ✅ ≥95% / ⚠️ 80-95% / 🔴 <80%
- 失敗 case 列前 5 + by-category breakdown
- Artifact 保留 90 天，可比對歷史 baseline

**目標**：Gemini 後台默默升級 model → 我們在用戶下錯單**之前**就看到分數變動。

---

## 6. Deployment & DevOps

### CI/CD Pipeline

```
PR 開到 main
  ↓
6 個 required check 必過：
  - 🐍 Monitor 測試（Python pytest）
  - 🔐 密鑰洩漏掃描（Gitleaks）
  - ⚛️ 前端檢查、測試 & 建構（Next.js）
  - ☕ 後端建構 & 測試（Gradle）
  - 🐳 Docker 建構驗證
  - 🔬 Integration Test（Testcontainers）
  ↓
admin（justinhsu1477）approve
  ↓
Merge to main
  ↓
CI 重跑（push to main 觸發）
  ↓ all green
CD workflow_run triggered (CI conclusion=success)
  ↓
Build Docker image → push GHCR
  ↓
SSH to VM → docker compose pull + up -d
  ↓
Caddy 自動 reload
```

### Branch Protection（2026-05-23 啟用）

```json
{
  "enforce_admins": false,
  "required_approvals": 1,
  "dismiss_stale_reviews": true,
  "required_ci_checks": 6,
  "strict_ci": true,
  "force_pushes_allowed": false,
  "deletions_allowed": false
}
```

- Admin 可繞過 review（緊急 hotfix）
- 其他 collaborator 必須走 PR + 6 check + admin approve
- main 不可刪、不可 force push

### Infrastructure

| 元件 | Provider | 角色 |
|------|----------|------|
| VM | DigitalOcean droplet（1 vCPU / 2GB） | Caddy + trading-api + web-dashboard + Redis + RabbitMQ |
| DB | Neon Postgres serverless（pgvector pg16）| 主資料庫 + vector embedding |
| 前端 host | 跟 trading-api 同 VM（內部 docker network）| Next.js 14 |
| Email | Resend | password reset / 訂閱通知 |
| AI | Gemini 2.5 Flash | 訊號解析 |
| Secrets | GitHub Actions secrets + VM `.env.prod` | CI/CD + runtime |
| Discord webhook | (自家 server) | mirror 訊號 + CD 通知 |

### Watchdog / Observability

- **Capture stall**：監測「最後一筆訊息到現在幾秒」— Python heartbeat 帶上去，Java exposed in `/api/health/deep` — 超過 4 小時 → `capture: DEGRADED`
- **Monitor heartbeat**：Python 每 30s 報告 status / AI status / 累積 token usage
- **AI cost tracking**：每次解析記 token，月底彙總成本
- **eval cron 異常**：分數 < 80% 自動 Discord 警報

---

## 7. Numbers & Stats（2026-05-23 snapshot）

| 項目 | 值 |
|------|---|
| **Commits** | 596（單人 3 個月）|
| **Java LoC** | 44,296 |
| **Python LoC** | 4,434 |
| **TypeScript LoC** | 29,695 |
| **Java 測試方法** | 2,428 |
| **Python 測試方法** | 329 |
| **Flyway migration** | 51 |
| **Active users (prod)** | 21 |
| **Active signal sources** | 151 |
| **Total broadcasts** | 1,305 |
| **Raw messages archived** | 5,834 |
| **Total trades executed** | 900 |
| **Modules** | 11（auth / trading / dashboard / notification / advisor / chatbot / subscription / referral / user / papertrade / shared）|

---

## 8. What I'd Do Differently

如果重來，會改的 3 件事：

1. **更早做 schema validation gate**：V47 那次 1.5 小時 prod outage 是純粹自己挖坑。如果一開始 integration test 就用 ddl-auto=validate，根本不會發生。
2. **Audit log 寫在 service 層而非 controller 層**：現在 `admin_audit_log` 必須走 admin API 才會寫，但我自己有時繞 DB 直接改（這次 mirror webhook setup 就是）→ trail 缺失。應該寫在 `signalSourceRepository.save()` 那層，任何寫入都 audit。
3. **monitor_version 加進 heartbeat 寫進 DB**：圖片 mirror 失蹤那次（Case 3）debug 花了 1 小時才發現是 Python 舊版本。如果 heartbeat 帶 git HEAD 寫進 `monitor_heartbeats` 表，一秒 query DB 就看出來。

---

## 📚 Related Reading

- [`SECURITY.md`](../SECURITY.md) — Threat model + 漏洞揭露
- [`README.md`](../README.md) — Quick start
- [`docs/SESSION_HANDOFF.md`](SESSION_HANDOFF.md) — 過去 sessions 重點 + 已踩坑列表
- [`docs/admin-permission-model.md`](admin-permission-model.md) — Admin 高風險操作清單
- [`docs/legal-risk-analysis.md`](legal-risk-analysis.md) — 法律 / 條款風險
- [`discord-monitor/docs/PROMPT_ARCHITECTURE.md`](../discord-monitor/docs/PROMPT_ARCHITECTURE.md) — Prompt 解析架構 + audit chain
- [`discord-monitor/eval/README.md`](../discord-monitor/eval/README.md) — AI Eval harness
