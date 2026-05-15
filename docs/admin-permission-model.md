# Admin 權限模型

> 最後更新：2026-05-15
> 對應 PR：`codex/modular-signal-prompts`（引入 `custom_prompt` 高風險欄位後必須補上的治理文件）

---

## 為什麼需要這份文件

過去專案是「單一 admin / 自用」狀態，所有 admin 操作都由作者一人執行，
無權限分級的需求。引入 `signal_sources.custom_prompt` 後：

- Admin 帳號被入侵 = 攻擊者可寫入任意 prompt 影響全用戶 AI 解析
- 多 admin 環境下 = 需要分權避免內部濫用
- 法律審查需要明確的「誰可以做什麼」對應到稽核紀錄

這份文件定義角色、權限矩陣、目前實作狀態、遷移路徑。

---

## 角色定義

| 角色 | 適用情境 | 目前實作 |
|------|----------|----------|
| `ROLE_USER` | 一般訂閱用戶 | ✅ 已實作（JWT claim） |
| `ROLE_ADMIN_READ` | 客服 / 數據觀察員 — 只能看，不能改 | ❌ 未實作 |
| `ROLE_ADMIN_WRITE` | 一般 admin — 可改大多數設定 | ⚠️ 部分（目前 admin 就是 write） |
| `ROLE_ADMIN_SUPER` | 超級 admin — 可改高風險欄位（prompt、加密金鑰相關） | ❌ 未實作 |
| `ROLE_BOT` | 內部服務（Python Monitor 等） | ✅ 已實作（`MONITOR_API_KEY`） |

> 目前所有 admin 等同於 `ROLE_ADMIN_WRITE + ROLE_ADMIN_SUPER` 合併。
> 多 admin SaaS 上線前必須拆分。

---

## 權限矩陣

### 用戶資料

| 操作 | USER | READ | WRITE | SUPER |
|------|------|------|-------|-------|
| 看自己的交易紀錄 | ✅ | ✅ | ✅ | ✅ |
| 看任意用戶的交易紀錄 | ❌ | ✅ | ✅ | ✅ |
| 改自己的風控設定 | ✅ | ❌ | ✅ | ✅ |
| 強制停用某用戶跟單 | ❌ | ❌ | ✅ | ✅ |
| 刪除用戶帳號 | ❌ | ❌ | ❌ | ✅ |
| 看用戶解密後的 Binance API Key | ❌ | ❌ | ❌ | ❌ |

> 最後一條：**任何 admin 角色都不應能讀取解密後的用戶 API Key**。
> 解密只發生在記憶體中、用於對 Binance 簽名請求，read API 永遠不回傳明文。

### 訊號來源

| 操作 | USER | READ | WRITE | SUPER |
|------|------|------|-------|-------|
| 看訊號來源清單 | ✅（基本資訊） | ✅ | ✅ | ✅ |
| 新增 / 刪除訊號來源 | ❌ | ❌ | ✅ | ✅ |
| 改 trade_mode / routing_mode | ❌ | ❌ | ✅ | ✅ |
| 改 risk_multiplier | ❌ | ❌ | ✅ | ✅ |
| **改 `custom_prompt`** | ❌ | ❌ | ⚠️ 需第二位 admin 簽核 | ✅ |
| 暫停 / 啟用訊號源 | ❌ | ❌ | ✅ | ✅ |

> `custom_prompt` 是「高風險欄位」— 建議多 admin 環境下要求第二人簽核
> （類似 GitHub branch protection 的「require approvals」）。

### 訂閱 / 計費

| 操作 | USER | READ | WRITE | SUPER |
|------|------|------|-------|-------|
| 查看自己的訂閱 | ✅ | ✅ | ✅ | ✅ |
| 查看任意用戶的訂閱 | ❌ | ✅ | ✅ | ✅ |
| 手動啟用訂閱（免費贈送） | ❌ | ❌ | ✅ | ✅ |
| 手動退款 | ❌ | ❌ | ❌ | ✅ |
| 修改訂閱方案價格 | ❌ | ❌ | ❌ | ✅ |

### 系統 / 基礎設施

| 操作 | USER | READ | WRITE | SUPER |
|------|------|------|-------|-------|
| 看 Prometheus metrics | ❌ | ✅ | ✅ | ✅ |
| 看 BroadcastLog | ❌ | ✅ | ✅ | ✅ |
| 重啟服務 | ❌ | ❌ | ❌ | ✅ |
| 改全局 SYSTEM_PROMPT（透過 prompt_versions） | ❌ | ❌ | ⚠️ 簽核 | ✅ |
| 改 RiskConfig 全局上限 | ❌ | ❌ | ❌ | ✅ |
| 看 audit log | ❌ | ✅ | ✅ | ✅ |
| 刪除 audit log | ❌ | ❌ | ❌ | ❌ |

> Audit log 應 append-only — 連 SUPER 也不應該能刪。

---

## 高風險操作清單

以下操作即使是 `SUPER` 也應該額外要求 2FA / 第二人簽核：

1. 修改 `custom_prompt` for any signal source
2. 修改全局 `SYSTEM_PROMPT`（透過 `prompt_versions` 表）
3. 修改 `RiskConfig` 的 `daily-loss-percent` / `max-position-usdt`
4. 修改 `encryption.aes-key`（涉及 key rotation）
5. 任何 `signal_sources` 的 `trade_mode` 從 `SHADOW` → `AUTO`（真錢上線）

---

## 必要的稽核紀錄

每次高風險操作必須寫入 `admin_audit_log`（表結構待設計，建議放在 `shared` 模組）：

```sql
CREATE TABLE admin_audit_log (
    id BIGSERIAL PRIMARY KEY,
    admin_user_id UUID NOT NULL,
    action VARCHAR(64) NOT NULL,        -- e.g. 'UPDATE_CUSTOM_PROMPT'
    target_type VARCHAR(32) NOT NULL,   -- e.g. 'SIGNAL_SOURCE'
    target_id VARCHAR(64) NOT NULL,
    before_hash CHAR(64),               -- SHA-256 of previous value
    after_hash CHAR(64),                -- SHA-256 of new value
    ip_address VARCHAR(45),
    user_agent TEXT,
    reason TEXT,                        -- admin 提供的修改理由
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_admin_audit_log_admin ON admin_audit_log(admin_user_id, created_at);
CREATE INDEX idx_admin_audit_log_target ON admin_audit_log(target_type, target_id, created_at);
```

不存 `before_value` / `after_value` 全文，只存 SHA-256 — 因為：

- prompt 全文可能含敏感資訊
- hash 已足以證明「這個版本被使用過」對應到 BroadcastLog 的 `effective_custom_prompt_sha256`
- 若需查全文，從 `signal_source_prompt_versions` 表查（另一張，保留所有歷史版本）

---

## 實作優先級

| 階段 | 必做項目 |
|------|----------|
| **現在（單一 admin）** | 暫時可不分權，但 `admin_audit_log` 表結構 + `custom_prompt` 寫入紀錄必須先有 |
| **第一位 admin 招募前** | 角色 `READ` 拆出；高風險操作要求理由欄位 |
| **第二位 admin 招募前** | `WRITE` / `SUPER` 拆分；高風險操作要求第二人簽核 |
| **公開 SaaS 上線前** | 所有 admin 強制 2FA；audit log 對接外部 SIEM（如 Datadog） |

---

## 端點實作對照（目前狀態）

| Controller | 端點 | 目前權限 | 應有權限 |
|------------|------|---------|---------|
| `AdminSignalSourceController` | `POST/PUT /admin/signal-sources/*` | `ROLE_ADMIN` | `WRITE` for 一般欄位，`SUPER` 或簽核 for `custom_prompt` |
| `AdminUserController` | `GET /admin/users` | `ROLE_ADMIN` | `READ` 足夠 |
| `AdminUserController` | `DELETE /admin/users/{id}` | `ROLE_ADMIN` | `SUPER` only |
| `AdminSubscriptionController` | `POST /admin/subscriptions/activate` | `ROLE_ADMIN` | `WRITE` |
| `AdminSubscriptionController` | `POST /admin/subscriptions/refund` | `ROLE_ADMIN` | `SUPER` only |
| `AdminPromptController` | `POST /admin/prompts/activate` | `ROLE_ADMIN` | `SUPER` 或簽核 |
| `AdminCacheController` | `POST /admin/cache/clear` | `ROLE_ADMIN` | `WRITE`（低風險） |

---

## 為什麼不直接用既有 RBAC framework？

- Spring Security 的 `@PreAuthorize` 已可勝任
- 暫不引入 Keycloak / Casbin 等外部 RBAC 系統 — 規模還沒到
- 建議實作方式：自訂 `@RequireRole(Role.SUPER)` annotation + AOP 攔截 + 自動寫 audit log

---

## 相關文件

- [`docs/legal-risk-analysis.md`](legal-risk-analysis.md) §6.2 — 法律層面為何要做這個
- [`SIGNAL_SOURCES.md`](../SIGNAL_SOURCES.md) — `custom_prompt` 治理流程
- [`discord-monitor/docs/PROMPT_ARCHITECTURE.md`](../discord-monitor/docs/PROMPT_ARCHITECTURE.md) — `custom_prompt` 寫入端安全約束
