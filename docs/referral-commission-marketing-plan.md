# 反佣導向 Marketing / Product 設計計劃（Binance 為主）

> 適用於本專案目前定位：`單一 Discord 訊號源 -> 統一解析 -> 廣播跟單 SaaS`
>
> 目標：讓使用者使用平台時，優先透過平台的交易所推薦關係（Referral / Affiliate）完成註冊或綁定，形成平台的第二收入來源，同時維持對用戶有吸引力的定價與體驗。

---

## 1. 先定義名詞（避免對內對外講錯）

你的商業設計會同時涉及 3 種「佣金/回饋」，要明確分開：

1. `交易所返佣（Platform Referral Commission）`
- 交易所依推薦計畫給平台的佣金收入（你的收入）。

2. `用戶手續費折扣（Exchange Fee Discount）`
- 交易所直接給用戶的手續費優惠（不是你平台付的）。

3. `平台回饋（Platform Rebate / Credit）`
- 你把部分收入回饋給用戶，形式可能是月費折扣、點數、現金券、功能升級。

建議對外說法：
- 不要主打「你要給我反佣」
- 要主打「透過平台指定註冊/綁定流程，可享平台方案優惠與持續回饋（依交易所規則）」

---

## 2. 核心商業理念（你這個產品版本）

### 2.1 收入結構理念：雙引擎，而不是只靠反佣

1. `主收入`：SaaS 訂閱（可預測、可控）
2. `次收入`：交易所返佣（高毛利，但受平台規則與用戶行為影響）
3. `促銷工具`：以部分返佣補貼用戶方案價格（提高轉化率）

結論：
- 不要把商業模型設計成「只有反佣才能成立」
- 正確做法是「有綁定更便宜、沒綁定也能用」

### 2.2 產品理念：對齊你的北極星

你的核心賣點不是交易所返佣，而是：

`單一高品質 Discord 訊號源 + 廣播跟單執行`

所以反佣設計應該是：
- `降低獲客成本`
- `增加留存`
- `補貼 SaaS 價格`

而不是破壞產品可信度（例如過度推銷、模糊資訊、強迫用戶重註冊）。

### 2.3 用戶分流理念（非常重要）

不可能讓所有用戶都給你反佣，因此要接受並制度化 3 類用戶：

1. `新用戶（最佳）`
- 可走你的推薦連結註冊，最容易形成穩定返佣。

2. `既有用戶（可爭取）`
- 視交易所規則與資格，可能可用 recall / affiliate 機制綁定。

3. `既有用戶（不可綁定）`
- 無法形成推薦關係，但仍可付費使用你的 SaaS。

產品設計的重點不是「強迫 100% 綁定」，而是：
- `最大化可綁定比例`
- `讓不可綁定用戶仍有可接受方案`

---

## 3. 目標與成功指標（KPI）

### 3.1 商業 KPI（核心）

1. `Referral-Eligible Signup Rate`
- 新註冊用戶中，進入推薦註冊流程的比例

2. `Referral Verified Rate`
- 提交 UID/API Key 的用戶中，被驗證為有效推薦關係的比例

3. `Referral-linked Paid Conversion Rate`
- 已驗證推薦關係用戶的付費轉化率

4. `ARPU (含返佣)`
- 訂閱收入 + 返佣收入後的每用戶平均收入

5. `CAC Payback Period`
- 把返佣納入後，回本期是否縮短

### 3.2 產品/營運 KPI

1. `Onboarding Completion Rate`
2. `UID Submission Rate`
3. `API Key Bind Rate`
4. `Verification SLA`（驗證完成時間）
5. `Support Ticket Rate`（因推薦/返佣規則產生的客服比例）

---

## 4. 定價與方案設計（建議版本）

## 4.1 建議採用雙軌方案（不要單軌）

1. `Referral Linked Plan（推薦綁定價）`
- 條件：使用平台指定推薦流程註冊/完成驗證
- 價格：較低
- 可搭配：回饋點數、功能升級、延長試用

2. `Standard Plan（一般價）`
- 條件：無推薦綁定或不符合綁定資格
- 價格：標準價
- 功能可相同，或略少（視你的策略）

### 4.2 價格策略原則（避免踩雷）

1. 不要承諾固定收益（只能承諾方案優惠/規則內回饋）
2. 不要把回饋寫成投資報酬保證
3. 不要讓費率過度複雜（用戶看不懂就不轉化）

### 4.3 建議最小可行方案（MVP）

1. 先不做動態回饋金額
2. 先做「是否完成推薦綁定」的方案折扣分流
3. 後續再做按月交易量分級回饋

---

## 5. Onboarding / Marketing Funnel 設計（產品流程）

## 5.1 首次導流頁（Landing / Signup 前）

第一步就分流，不要等到 API Key 綁定才講推薦關係。

建議選項：

1. `我是新 Binance 用戶`
- 走平台指定推薦註冊流程（你的主路徑）

2. `我已有 Binance 帳戶`
- 進入資格檢查 / 既有用戶流程（可能 recall 或一般價）

3. `先試用再決定`
- 可進 demo / paper trading / dashboard preview，但付費前仍需分流

## 5.2 註冊後 Onboarding（SaaS 內）

建議順序：

1. 建立 SaaS 帳號
2. 選擇交易所（目前 Binance）
3. 顯示推薦綁定狀態頁（Pending / Verified / Ineligible）
4. 綁 API Key（非託管）
5. 提交 Binance UID（供驗證/對帳）
6. 啟用跟單
7. 顯示最終方案價格（依驗證結果）

## 5.3 狀態模型（前後端共用）

建議 `referral_status`：

1. `NOT_STARTED`
2. `PENDING_USER_ACTION`（尚未用指定流程註冊/尚未提交 UID）
3. `PENDING_VERIFICATION`
4. `VERIFIED`
5. `INELIGIBLE`
6. `EXPIRED`（若特定優惠需定期驗證）
7. `SUSPENDED`（異常/風控）

---

## 6. 資料模型設計（Table / 欄位規劃）

原則：

1. 優先沿用你現有的 `users / user_api_keys / subscriptions / plans`
2. 用少量新表補足「驗證、對帳、稽核」能力
3. 讓營運可追溯，不靠人工聊天紀錄

## 6.1 既有表建議新增欄位（優先）

### A. `user_api_keys`（已有）

用途：你已用這張表綁交易所 API，最適合補上交易所帳號識別與推薦驗證狀態。

建議新增欄位：

| 欄位 | 型別 | 用途 |
|---|---|---|
| `exchange_uid` | `VARCHAR(64)` | 使用者在交易所的 UID（用於驗證與對帳） |
| `referral_status` | `VARCHAR(32)` | `NOT_STARTED/PENDING_VERIFICATION/VERIFIED/...` |
| `referral_program_id` | `VARCHAR(64)` | 對應平台使用的推薦計畫版本/ID |
| `referral_source_type` | `VARCHAR(32)` | `NEW_SIGNUP / RECALL / NONE / UNKNOWN` |
| `referral_verified_at` | `TIMESTAMP` | 驗證成功時間 |
| `referral_ineligible_reason` | `VARCHAR(255)` | 不符合資格原因（例如既有帳戶不可綁） |
| `referral_last_checked_at` | `TIMESTAMP` | 最後一次驗證/同步時間 |

建議索引：
- `idx_uak_exchange_uid (exchange_uid)`
- `idx_uak_referral_status (referral_status)`
- `idx_uak_user_exchange (userId, exchange)`

### B. `subscriptions`（已有）

用途：把定價結果與推薦綁定關係串起來，避免只在前端顯示折扣。

建議新增欄位：

| 欄位 | 型別 | 用途 |
|---|---|---|
| `pricing_mode` | `VARCHAR(32)` | `STANDARD / REFERRAL_LINKED / PROMO` |
| `discount_source` | `VARCHAR(64)` | `BINANCE_REFERRAL`, `MANUAL_OVERRIDE`, `PROMO_CAMPAIGN` |
| `discount_percent` | `DECIMAL(5,2)` | 當期折扣百分比（若有） |
| `referral_link_required` | `BOOLEAN` | 此訂閱價格是否依賴推薦狀態 |
| `pricing_locked_until` | `TIMESTAMP` | 價格鎖定到期（避免頻繁變動引發客訴） |

### C. `plans`（已有）

用途：把標準價與推薦綁定價制度化，不要硬編碼在後端。

建議新增欄位（擇一方案）：

方案 1（簡單）：

| 欄位 | 型別 | 用途 |
|---|---|---|
| `price_monthly_referral` | `DECIMAL(10,2)` | 推薦綁定月費 |
| `price_yearly_referral` | `DECIMAL(10,2)` | 推薦綁定年費 |

方案 2（較彈性，推薦）：
- 不改 `plans` 結構，改新增 `plan_price_rules` 表（見下方）

## 6.2 建議新增 Table（MVP + 可擴充）

### 1. `exchange_referral_programs`

用途：
- 管理你平台目前生效的推薦計畫設定（不同交易所/不同時期規則）
- 避免把規則散落在程式碼與 Notion

建議欄位：

| 欄位 | 型別 | 用途 |
|---|---|---|
| `program_id` | `VARCHAR(64)` PK | 推薦計畫 ID（例如 `BINANCE_REFERRAL_2026Q1`） |
| `exchange` | `VARCHAR(32)` | `BINANCE` |
| `program_type` | `VARCHAR(32)` | `REFERRAL_PRO / AFFILIATE / RECALL` |
| `status` | `VARCHAR(16)` | `ACTIVE / INACTIVE` |
| `referral_link` | `TEXT` | 對外引導連結 |
| `referral_code` | `VARCHAR(64)` | 邀請碼（若有） |
| `notes` | `TEXT` | 規則備註、限制說明 |
| `effective_from` | `TIMESTAMP` | 生效時間 |
| `effective_to` | `TIMESTAMP` | 失效時間 |
| `created_at` | `TIMESTAMP` | 建立時間 |
| `updated_at` | `TIMESTAMP` | 更新時間 |

### 2. `user_exchange_referral_links`

用途（核心表）：
- 記錄「某用戶與某交易所帳號」的推薦綁定狀態與驗證結果

建議欄位：

| 欄位 | 型別 | 用途 |
|---|---|---|
| `id` | `BIGSERIAL` PK | 主鍵 |
| `user_id` | `VARCHAR(64)` | 對應 `users.userId` |
| `exchange` | `VARCHAR(32)` | `BINANCE` |
| `exchange_uid` | `VARCHAR(64)` | 交易所 UID |
| `program_id` | `VARCHAR(64)` | 對應 `exchange_referral_programs.program_id` |
| `link_type` | `VARCHAR(32)` | `NEW_SIGNUP / RECALL / NONE` |
| `status` | `VARCHAR(32)` | 推薦狀態（同 `referral_status`） |
| `verified_by` | `VARCHAR(32)` | `MANUAL / IMPORT / API / SUPPORT` |
| `verified_at` | `TIMESTAMP` | 驗證時間 |
| `ineligible_reason_code` | `VARCHAR(64)` | 不符合原因代碼 |
| `ineligible_reason_detail` | `TEXT` | 說明 |
| `first_seen_at` | `TIMESTAMP` | 首次建立紀錄 |
| `last_checked_at` | `TIMESTAMP` | 最後檢查時間 |
| `created_at` | `TIMESTAMP` | 建立時間 |
| `updated_at` | `TIMESTAMP` | 更新時間 |

約束建議：
- `UNIQUE (exchange, exchange_uid)`：同一交易所 UID 只能綁一個 SaaS 帳號（避免套利）
- `UNIQUE (user_id, exchange)`：同一用戶同交易所一筆主綁定關係

### 3. `referral_verification_events`

用途：
- 稽核每次驗證行為（客服/營運/匯入腳本）
- 之後出現客訴時可追溯

建議欄位：

| 欄位 | 型別 | 用途 |
|---|---|---|
| `id` | `BIGSERIAL` PK | 主鍵 |
| `user_id` | `VARCHAR(64)` | SaaS 用戶 ID |
| `exchange` | `VARCHAR(32)` | `BINANCE` |
| `exchange_uid` | `VARCHAR(64)` | UID |
| `event_type` | `VARCHAR(32)` | `SUBMITTED / VERIFIED / REJECTED / RECHECKED / OVERRIDE` |
| `status_before` | `VARCHAR(32)` | 前狀態 |
| `status_after` | `VARCHAR(32)` | 後狀態 |
| `source` | `VARCHAR(32)` | `USER_UI / ADMIN_UI / IMPORT_JOB / API` |
| `operator_id` | `VARCHAR(64)` | 操作者（系統或管理員） |
| `evidence_ref` | `TEXT` | 證據參考（匯入檔 ID、後台截圖路徑、報表批次） |
| `notes` | `TEXT` | 備註 |
| `created_at` | `TIMESTAMP` | 建立時間 |

### 4. `referral_commission_batches`

用途：
- 紀錄從交易所/合作後台匯入的佣金資料批次（每日/每週）

建議欄位：

| 欄位 | 型別 | 用途 |
|---|---|---|
| `batch_id` | `VARCHAR(64)` PK | 批次 ID（例如日期+來源） |
| `exchange` | `VARCHAR(32)` | `BINANCE` |
| `period_start` | `TIMESTAMP` | 批次期間起 |
| `period_end` | `TIMESTAMP` | 批次期間迄 |
| `source_type` | `VARCHAR(32)` | `CSV_IMPORT / API_SYNC` |
| `file_name` | `VARCHAR(255)` | 原始檔名（若有） |
| `row_count` | `INT` | 匯入筆數 |
| `status` | `VARCHAR(16)` | `PENDING / APPLIED / FAILED` |
| `created_at` | `TIMESTAMP` | 建立時間 |
| `applied_at` | `TIMESTAMP` | 套用時間 |
| `notes` | `TEXT` | 備註 |

### 5. `referral_commission_entries`

用途：
- 每筆可對帳的佣金紀錄（用於計算用戶回饋、方案調整、營運報表）

建議欄位：

| 欄位 | 型別 | 用途 |
|---|---|---|
| `id` | `BIGSERIAL` PK | 主鍵 |
| `batch_id` | `VARCHAR(64)` | 對應 `referral_commission_batches` |
| `exchange` | `VARCHAR(32)` | `BINANCE` |
| `exchange_uid` | `VARCHAR(64)` | 交易所 UID |
| `user_id` | `VARCHAR(64)` nullable | 若對應成功則寫 SaaS userId |
| `asset` | `VARCHAR(16)` | 佣金資產（例如 `USDC` / `USDT`） |
| `commission_amount` | `DECIMAL(20,8)` | 佣金金額 |
| `trade_volume` | `DECIMAL(20,8)` nullable | 對應交易量（若資料有） |
| `occurred_at` | `TIMESTAMP` | 發生時間 |
| `matched` | `BOOLEAN` | 是否成功對應到用戶 |
| `match_confidence` | `VARCHAR(16)` | `HIGH / MEDIUM / LOW` |
| `created_at` | `TIMESTAMP` | 建立時間 |

索引建議：
- `idx_rce_uid (exchange, exchange_uid)`
- `idx_rce_user_time (user_id, occurred_at)`
- `idx_rce_batch (batch_id)`

### 6. `user_pricing_entitlements`

用途：
- 將「營運規則」與「實際收費權利」分離，避免每次計費都重新推導
- 適合你未來做 `referral-linked price`、活動價、客服補償

建議欄位：

| 欄位 | 型別 | 用途 |
|---|---|---|
| `id` | `BIGSERIAL` PK | 主鍵 |
| `user_id` | `VARCHAR(64)` | 用戶 ID |
| `entitlement_type` | `VARCHAR(32)` | `REFERRAL_LINKED_PRICE / PROMO_CREDIT / MANUAL_OVERRIDE` |
| `status` | `VARCHAR(16)` | `ACTIVE / EXPIRED / REVOKED` |
| `plan_id` | `VARCHAR(64)` nullable | 作用的方案 |
| `discount_percent` | `DECIMAL(5,2)` nullable | 折扣 |
| `benefit_value` | `DECIMAL(10,2)` nullable | 其他回饋值 |
| `currency` | `VARCHAR(16)` nullable | 幣別 |
| `source_ref` | `VARCHAR(128)` | 來源（program/batch/ticket） |
| `starts_at` | `TIMESTAMP` | 生效 |
| `ends_at` | `TIMESTAMP` nullable | 到期 |
| `created_at` | `TIMESTAMP` | 建立時間 |
| `updated_at` | `TIMESTAMP` | 更新時間 |

### 7. `marketing_attribution_events`（可後做）

用途：
- 追蹤用戶是從哪個導流頁/廣告/推薦連結進來
- 用來優化 marketing，不是核心交易邏輯

建議欄位（簡版）：
- `id`, `user_id`, `session_id`, `utm_source`, `utm_medium`, `utm_campaign`, `landing_path`, `event_type`, `created_at`

---

## 7. 推薦：MVP 版資料庫改動（最小集）

如果你要先快速上線驗證商業模型，不要一次做太多。MVP 建議先做：

1. `user_api_keys` 加欄位：
- `exchange_uid`
- `referral_status`
- `referral_verified_at`
- `referral_ineligible_reason`
- `referral_last_checked_at`

2. 新增 `user_exchange_referral_links`
- 作為主狀態表（核心）

3. 新增 `referral_verification_events`
- 作為稽核紀錄表（客服/營運必備）

4. `subscriptions` 加欄位：
- `pricing_mode`
- `discount_source`
- `discount_percent`

先不要做的（可以 V2）：
- `referral_commission_entries` 自動對帳
- `user_pricing_entitlements`
- `marketing_attribution_events`

---

## 8. 營運流程設計（不是只有資料表）

## 8.1 日常流程（建議）

1. 用戶完成註冊/綁 API Key/提交 UID
2. 系統狀態設為 `PENDING_VERIFICATION`
3. 每日營運對帳（手動匯入或 API）
4. 驗證成功 -> `VERIFIED`
5. 系統套用 `Referral Linked Plan` 或折扣權益
6. 驗證失敗 -> `INELIGIBLE`，回到 `Standard Plan`

## 8.2 客服流程（建議）

客服需要有標準話術與處理路徑：

1. 用戶說「我有用你的連結註冊但沒優惠」
- 查 `user_exchange_referral_links`
- 查 `referral_verification_events`
- 若需人工核對，建立 `OVERRIDE` 事件（要留證據）

2. 用戶是既有帳戶，要求補綁
- 不要承諾一定可行
- 引導至「既有用戶資格檢查」流程

3. 用戶拒絕提供 UID
- 可以使用產品，但走 `Standard Plan`

---

## 9. UI / 文案設計重點（Marketing 與轉化）

## 9.1 不建議話術（容易反感或有風險）

1. 「你一定要給我反佣才可以用」
2. 「保證你靠返佣省回月費」
3. 「用這個一定賺」

## 9.2 建議話術（雙贏、透明）

1. 「透過平台指定註冊流程，可解鎖推薦綁定方案價」
2. 「平台以交易所合作收入補貼產品費用，讓你用更低成本跟單」
3. 「是否符合優惠資格依交易所規則與帳戶狀態為準」

## 9.3 Onboarding 畫面一定要出現的資訊

1. 這不是資金託管，資金留在用戶交易所
2. 推薦綁定與優惠資格需驗證
3. 既有帳戶不一定可補綁
4. 平台方案費與交易所手續費是兩件事

---

## 10. 風險與注意事項（重要）

## 10.1 合作平台規則會變（必須制度化）

1. 推薦比例
2. 可用地區
3. 既有用戶是否可補綁
4. 資料匯出格式 / 對帳方式
5. 禁止的推廣方式

做法：
- 不要把規則寫死在前端文案
- 用 `exchange_referral_programs` 管版本與生效期間

## 10.2 法務/合規注意（至少要有內控）

1. 不保證投資報酬
2. 不以返佣包裝成收益保證
3. 清楚揭露平台與交易所合作關係（若適用）
4. 遵守當地對推薦行銷/投資宣傳規範
5. 注意個資與交易資料使用範圍（UID、交易量、佣金對帳資料）

> 建議：在正式大規模推廣前，至少做一版對外揭露文案與服務條款補充頁。

## 10.3 反作弊 / 濫用風險

你會遇到的常見濫用：

1. 同一人用多帳號套利推薦方案價
2. 借他人 UID 嘗試冒領優惠
3. 綁定後立即取消/切換帳號仍要求保留優惠

建議控制：

1. `UNIQUE (exchange, exchange_uid)`
2. 方案價格鎖定期間（例如 30 天）
3. 保留人工審核與撤銷權限（要有事件紀錄）
4. 異常變更進 `SUSPENDED` 狀態

## 10.4 客訴風險（最常見）

1. 用戶以為「交易所手續費折扣」=「平台月費折扣」
2. 用戶以為提交 UID 就一定過
3. 用戶不知道既有帳戶可能不可補綁

解法：
- Onboarding 文案和 FAQ 先講清楚
- 狀態頁顯示「待驗證 / 已驗證 / 不符合」與原因

---

## 11. 系統整合建議（對你現有專案）

## 11.1 後端模組分工（建議新增）

建議新建 `referral` 模組（或先掛在 `subscription` 模組下）：

1. `ReferralProgramService`
- 管理當前生效推薦計畫與文案顯示

2. `ReferralVerificationService`
- 驗證 UID / 更新狀態 / 寫事件紀錄

3. `ReferralPricingService`
- 根據推薦狀態決定 `pricing_mode` / 折扣

4. `ReferralReconciliationService`（V2）
- 匯入佣金報表與對帳

## 11.2 API（MVP 建議）

1. `GET /api/referral/status`
- 取得目前用戶推薦綁定狀態

2. `POST /api/referral/submit-uid`
- 用戶提交 `exchange_uid`

3. `POST /api/referral/recheck`
- 觸發重新檢查（可加頻率限制）

4. `GET /api/referral/program`
- 回傳目前生效計畫資訊（顯示前端文案）

5. `POST /api/admin/referral/verify`（admin-only）
- 人工覆核/覆寫狀態（必寫 event）

---

## 12. 分階段落地計劃（建議）

## Phase 1（2~5 天，商業驗證 MVP）

1. 完成資料表最小改動（`user_api_keys` + `user_exchange_referral_links` + `referral_verification_events` + `subscriptions`）
2. 做 `referral_status` API
3. 做 UID 提交頁與狀態頁
4. 後端能依 `VERIFIED` 套用 `Referral Linked Plan`
5. 客服/營運先手動驗證（不用自動對帳）

目標：
- 先驗證「用戶是否願意配合推薦綁定換較低月費」

## Phase 2（1~2 週，營運效率）

1. 加入匯入批次（`referral_commission_batches`）
2. 加入佣金明細對帳（`referral_commission_entries`）
3. 做營運後台清單：待驗證 / 已驗證 / 不符合
4. 補客服操作審計與 override 流程

目標：
- 降低人工核對成本，縮短驗證 SLA

## Phase 3（進階成長）

1. `user_pricing_entitlements`
2. 分級回饋（依交易量或活躍度）
3. 行銷 attribution 與 funnel 優化
4. A/B test 文案與方案價格

---

## 13. 需要你先做的商業決策（我建議先拍板）

這 8 個問題要先定，程式才能穩定落地：

1. `Referral Linked Plan` 比 `Standard Plan` 便宜多少？
2. 未驗證狀態可否先給優惠（例如 7 天暫時優惠）？
3. 不符合資格後，何時切回標準價（立即 / 下個 billing cycle）？
4. 是否允許客服人工 override？誰有權限？
5. 推薦綁定失效時是否保留價格鎖定期？
6. 是否要求提交 UID 才能啟用自動跟單？
7. 是否先只支援 Binance，其他交易所後補？
8. 對外文案是否揭露「平台透過合作返佣補貼價格」？

---

## 14. 最終原則（給你做產品決策時用）

1. `先把推薦綁定做成清楚的定價制度，不要做成私下談條件`
2. `先做狀態與稽核，再做自動對帳`
3. `先提高轉化與可驗證性，再追求複雜回饋算法`
4. `反佣設計要服務你的核心賣點（單一訊號源廣播 SaaS），不是取代它`

