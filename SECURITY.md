# Security Policy

> Crypto Signal Trader 是處理用戶交易所 API Key 與真實資金的 SaaS 系統。
> 本文件記錄 **威脅模型 / 已部署的防禦 / 已知殘餘風險 / 漏洞揭露流程**。

---

## 📡 Supported Versions

| 分支 / Tag | 狀態 | 安全更新 |
|------------|------|----------|
| `main` (HEAD) | ✅ Active | ✅ |
| Tagged releases (`v*`) | 🚧 尚未開始 release flow | — |
| Feature branches (`feature/*`, `codex/*`) | ❌ 不接受漏洞報告 | — |

**只接受 `main` 最新 commit 的漏洞報告。** 舊分支 / fork 不在範圍內。

---

## 🚨 Reporting a Vulnerability

### Do **NOT** open a public GitHub issue

漏洞報告請走以下私密管道：

- **Email**: `justin80605@gmail.com`
- **Subject prefix**: `[SECURITY] <短描述>`
- **PGP**: 暫無（如需端對端加密，先 email 約金鑰交換）

### 預期回應時程

| 階段 | SLA |
|------|-----|
| 初次回覆 | 72 小時內 |
| 嚴重程度初判 | 7 天內 |
| 修正並部署 | 嚴重 (Critical) 14 天 / 重要 (High) 30 天 / 中低 best-effort |
| Public disclosure | 修正部署後 30 天（或雙方協議）|

### 報告應包含

- 攻擊步驟（reproducible PoC，盡量小）
- 影響範圍（什麼資料 / 多少用戶 / 是否可放大）
- 你預期的修正方向（optional）
- 是否同意 credit 給你（acknowledgements）

---

## 🎯 Threat Model

下面是這個系統**主動思考過**的攻擊面、每一面已部署的防禦、以及**已知殘餘風險**。

### 1. Exchange API Key 外洩 — `CRITICAL`

**為什麼最嚴重**：直接損失資金。攻擊者拿到 Binance API Key 可下單、平倉、轉錢（如果 user 沒關提款）。

| 層 | 防禦 |
|---|---|
| Transport | HTTPS only (Caddy + Cloudflare) |
| Storage | AES-256-GCM 加密（`AesEncryptionUtil`），明碼不入庫 |
| Key 來源 | env var `AES_ENCRYPTION_KEY`，gitignored，不入 docker image |
| Access | per-user，用 JWT auth + admin role gate |
| Log | 永遠 mask（`MaskingUtil` + Logback `PatternMaskingConverter`）|

**已知殘餘風險**：
- ⚠️ AES key rotation 未實作（rotate 後需 re-encrypt 所有欄位 — 工程量大）
- ⚠️ Key 跟資料同 DB → DB 完整外洩 + env var 同時外洩 = 全部解密
- ⚠️ 建議 user 在 Binance 設定：**關提款權限 + IP 白名單**（這是 user 端硬要求，系統提示但無法強制）

### 2. Discord Webhook URL 外洩

**威脅**：拿到 webhook URL 的人能發任何訊息進對應 channel（spoofing / phishing）。

| 層 | 防禦 |
|---|---|
| Storage | AES-256-GCM 加密（`signal_sources.mirror_webhook_url`）|
| Global kill switch | `MIRROR_ENABLED=false` 一鍵關全部 outbound |
| Per-source kill switch | `mirror_enabled=false` 個別關 |
| Reset 流程 | Discord channel → Integrations → Webhooks → Regenerate URL |

**已知殘餘風險**：
- ⚠️ 24h Discord CDN URL 過期 — 圖片轉發會破圖（Phase 2 規劃改 multipart re-upload 解決）
- ⚠️ Mirror 失敗永遠 swallow → 沒主動告警，要看 log

### 3. Signal Source Poisoning

**威脅**：訊號源 Discord channel 被原作者帳號被駭 / 內部人惡意 → 假訊號 → 多用戶被誤導下單。

| 層 | 防禦 |
|---|---|
| 預設模式 | 新 source 預設 `trade_mode=SHADOW`（只記錄不下單），admin 審核後才升 `AUTO` |
| Risk multiplier | per-source 可調，不信任源設低 |
| Per-user kill switch | `auto_trade_enabled=false` 用戶可隨時關 |
| 風控層 | 10 層 pre-execution validation（symbol whitelist / size / leverage / position cap / cooldown 等）|
| Audit | `broadcast_logs` 每次廣播留紀錄 |

**已知殘餘風險**：
- ⚠️ 信任源被駭時無自動偵測（要看 prompt eval 分數突降 + 人工 review）
- ⚠️ Custom prompt 變更後第一筆訊號就生效（無 canary / staged rollout）

### 4. Database Compromise

**威脅**：Neon DB 直接被攻破，或 connection string 外洩。

| 層 | 防禦 |
|---|---|
| Sensitive columns | API key / webhook URL / cookie 都 AES 加密 |
| Password | bcrypt (cost factor 10) |
| Connection | TLS + Neon IAM |
| Network | （Neon 預設無 VPC peering）|

**已知殘餘風險**：
- ⚠️ user_id / email / trade history 是明碼（屬個資但非 secret）
- ⚠️ `password_reset_tokens.token_hash` 是 sha256（單純 hash，無 salt）— TTL 60min 內若 DB 外洩可暴力破解尚未使用 token
- ⚠️ AES key 跟 DB 在同一 cloud account → 兩個都被拿到=全失

### 5. Custom Prompt Injection

**威脅**：admin 寫 prompt 注入 instruction 讓 Gemini 改變解析結果（影響 broadcast 行為）。

| 層 | 防禦 |
|---|---|
| Append-only | `prompt_versions` 表 reflection 護欄擋 DELETE — 任何 prompt 變更都留審計鏈 |
| Audit | `admin_audit_log` 記 before/after SHA-256 fingerprint |
| Eval gate | 每週一 09:00 跑 30-case eval harness，分數低於 80% → Discord 警報 |
| Rollback | UI 一鍵回任一歷史版本（複用既有 activate API）|

**已知殘餘風險**：
- ⚠️ Eval 只 30 case，未涵蓋所有 edge case
- ⚠️ Per-source `custom_prompt` 變更後直接生效（無「dry-run」mode）

### 6. Password Reset Token Replay

**威脅**：reset email 中間人攔截 / log 外洩 → 重設別人密碼。

| 層 | 防禦 |
|---|---|
| Token format | UUID + sha256 入庫（DB 沒明碼 token）|
| TTL | 60 分鐘 |
| Used flag | 用過即廢，防 replay |
| Rate limit | per user `max-reset-per-quarter-hour: 3`（防 enumeration / brute）|

**已知殘餘風險**：
- ⚠️ Email 寄送走 Resend，Resend dashboard 可看完整 link → Resend account 被駭風險

### 7. Admin Endpoint Abuse

**威脅**：admin role 被取得 → 改 signal source / mirror webhook / custom prompt → 系統行為被惡意改變。

| 層 | 防禦 |
|---|---|
| Auth | JWT (`@PreAuthorize` + role check) |
| Audit | `admin_audit_log` 記 admin_user_id + IP + action + before/after hash + reason（required）|
| 高風險操作 | 強制填 `reason`（無 reason 拒收）|
| IP forensics | Cloudflare `CF-Connecting-IP` header 解析 |

**已知殘餘風險**：
- ⚠️ admin_audit_log **必須走 admin API 才寫入** — 如有人繞過走 DB 直 UPDATE 不會留 audit（內部威脅模型未防）

### 8. Public Endpoint Abuse / Scraping

**威脅**：未認證的 `/api/auth/forgot-password`、`/api/health` 等被 scanner 探。

| 層 | 防禦 |
|---|---|
| WAF | Cloudflare proxied，basic bot block |
| Rate limit | per IP（Bucket4j on critical endpoints）|
| Audit | failed access 寫 `audit_logs.action=API_ACCESS_FAILED` |

**已知殘餘風險**：
- ⚠️ `/api/discord-messages` 用 `X-Api-Key` (`MONITOR_API_KEY`)，若 key 外洩可大量灌假 archive

---

## 🛡️ Defense-in-Depth Summary

| 機制 | 對應威脅 |
|------|---------|
| AES-256-GCM 加密欄位 | 1, 2, 4 |
| Bcrypt 密碼 | 4 |
| JWT + role gate | 7 |
| 10 層 pre-trade 風控 | 3 |
| Append-only prompt 版本鏈 | 5 |
| Weekly eval cron + Discord 警報 | 5 |
| `admin_audit_log` (SHA-256 hash chain) | 7 |
| Branch protection (PR + 6 CI check + review) | 程式碼層 supply chain |
| Gitleaks CI scan | secret 防誤推 |
| Hibernate schema validation IT | 1, 4（防 schema mismatch 造成資料解析錯誤）|
| Schema-validation-test profile | 同上 |
| Force push 禁、main 刪除禁 | 程式碼層 |

---

## ❌ Out of Scope

以下不在這份 SECURITY.md 範圍：

- **用戶端設備被入侵**（鍵盤側錄 / 螢幕監控 / 物理存取）— 系統無法防
- **Binance / Discord / Gemini 第三方平台漏洞** — 上游責任
- **Cloudflare / Neon / Resend 基礎設施漏洞** — vendor 責任
- **Social engineering（騙 admin 寄 reset link）** — 訓練性質
- **量子計算未來破解 AES** — 跨時代假設
- **DoS / DDoS at L3/L4** — Cloudflare 處理
- **法律 / 監管風險** — 見 [`docs/legal-risk-analysis.md`](docs/legal-risk-analysis.md)

---

## 🔄 Related Documents

| 主題 | 文件 |
|------|------|
| Admin 高風險操作清單 + 權限分級 | [`docs/admin-permission-model.md`](docs/admin-permission-model.md) |
| 服務條款必備條款 / 法律風險 | [`docs/legal-risk-analysis.md`](docs/legal-risk-analysis.md) |
| 環境變數 + secret 參考 | [`docs/CONFIGURATION_REFERENCE.md`](docs/CONFIGURATION_REFERENCE.md) |
| Audit chain / prompt version invariant | [`discord-monitor/docs/PROMPT_ARCHITECTURE.md`](discord-monitor/docs/PROMPT_ARCHITECTURE.md) |

---

## 📜 Acknowledgements

感謝以下研究者提出的有效漏洞報告（依時間排序）：

_目前清單為空。第一位來報告 critical/high 漏洞且願意被 credit 的研究者會列在這裡。_

---

## 📝 Policy Version

- **Last updated**: 2026-05-19
- **Maintainer**: [@justinhsu1477](https://github.com/justinhsu1477)
- **Next review**: 2026-11-19 (半年 review 一次)
