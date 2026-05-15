# 訊號來源設定總覽

> 最後更新：2026-05-15（加入 `Custom Prompt` 欄位與治理章節）

## 來源清單

| ID | 名稱 | Channel ID | Trade Mode | Routing Mode | Enabled | Paper Trading | Custom Prompt |
|----|------|------------|------------|--------------|---------|---------------|---------------|
| 2 | fengge（風哥） | 1004720271409807400 | SHADOW | ASSIGNED | Yes | Yes | — |
| 3 | zhige（志哥） | 1004720695160348683 | SHADOW | ASSIGNED | Yes | No | — |
| 4 | shuqin（淑琴） | 1104301561892585543 | SHADOW | ASSIGNED | Yes | Yes | — |
| 5 | jiami-dapiaoliang（加密大漂亮） | 1229962956884807740 | SHADOW | ASSIGNED | Yes | Yes | — |
| 6 | sanmage（三馬哥） | 1237749772148801567 | SHADOW | ASSIGNED | Yes | Yes | — |
| 7 | ouyang（歐陽） | 1331646699843883069 | SHADOW | ASSIGNED | Yes | No | — |
| 8 | feiyang（飛揚） | 1356581750914027590 | SHADOW | ASSIGNED | Yes | No | — |
| 9 | dabiaoke（大鏢客） | 1384381529064476815 | SHADOW | ASSIGNED | Yes | No | — |
| 10 | chenge（陳哥） | 1271151178905817129 | SHADOW | GLOBAL | Yes | No | — |

> Guild ID 全部為 `1004707886657699901`
> `Custom Prompt` 欄位：`—` = 未設定，`vN` = 已設定版本號（見下方治理章節）

---

## Trade Mode 說明

| Mode | 行為 |
|------|------|
| **AUTO** | 真實交易：接收訊號 → 對所有訂閱用戶執行 Binance 下單 → 通知 Admin |
| **SHADOW** | 影子模式：記錄 BroadcastLog + AI 評分 + 可選模擬交易（Paper Trading）→ 不執行真實交易、不即時通知 Admin |
| **MANUAL** | 僅記錄：只寫 BroadcastLog，不交易、不評分（目前無來源使用） |

## Routing Mode 說明

| Mode | 行為 |
|------|------|
| **GLOBAL** | 全員廣播：訊號發送給所有啟用的用戶 |
| **ASSIGNED** | 指定用戶：只發送給綁定此來源的用戶 |

## Paper Trading（模擬交易）

- 開啟後，SHADOW 訊號會建立 `simulated=true` 的 Trade 紀錄
- 固定倉位 1000 USDT、10x 槓桿
- 定時監控（每 90 秒）檢查 TP/SL 自動模擬平倉
- 績效比較表會自動包含模擬交易的 PnL 數據

## Enabled 開關

- `enabled=false` 時，該來源的所有訊號會被跳過（不進入 AUTO/SHADOW/MANUAL 流程）
- 僅記錄一筆 `SOURCE_DISABLED` 的 BroadcastLog

---

---

## Custom Prompt 治理

`signal_sources.custom_prompt` 是該訊號源的 AI 解析方言補充，會在 Gemini system prompt
的「通用規則」與「範例」之間插入。**這個欄位是高風險欄位**（影響該源所有訂閱用戶的 AI 解析）。

### 何時應該設定 `custom_prompt`？

✅ 適合的使用場景：
- 該老師有跨來源衝突的口語（例：「保護」對 A 老師是移動 SL 到 breakeven，對 B 老師是
  移動 SL 到 entry price，無法靠全局規則統一）
- 該老師的圖訊號有特殊版型（藍框=entry，紅框=SL）
- 該老師有獨特的縮寫（例：用 `BP` 代表 BTCUSDT 永續）

❌ 不適合的使用場景：
- 通用規則（應該改 `ai_parser.py` 的 SYSTEM_PROMPT，所有源都受惠）
- 數字門檻（風險倍率改 `signal_sources.risk_multiplier`，倉位設定改 `UserTradeSettings`）
- 「暫時關掉這個源」（改 `enabled=false` 或 trade_mode）

### 寫入流程（必須遵守）

1. **撰寫**：在 Admin Dashboard 的 SignalSource 頁面填寫；長度 ≤ 1500 字元
2. **Eval**：在 `discord-monitor/eval/cases.jsonl` 加至少 1 個對應 case，跑 `python -m eval.runner`
3. **送審**（多 admin 環境）：由另一位 admin 確認 prompt 內容無 prompt injection
4. **生效**：寫入後 gRPC stream 自動推送到 Python monitor，version 號 +1
5. **觀察**：上線後 24 小時內每天看一次 BroadcastLog，確認該源解析結果符合預期

### 變更會留下的稽核紀錄

每次 `custom_prompt` 變動會自動寫入：
- `signal_source_audit_log`：誰改、改前 SHA-256、改後 SHA-256、時間戳
- 後續 30 天內由該源產生的每筆 `BroadcastLog` / `Trade` 會帶 `custom_prompt_version`
  + `effective_custom_prompt_sha256`，可向用戶/律師證明該筆交易的解析依據

### 安全約束

詳細約束見 [`discord-monitor/docs/PROMPT_ARCHITECTURE.md`](discord-monitor/docs/PROMPT_ARCHITECTURE.md#safety-constraints-on-custom_prompt)。
摘要：

- 禁止含 `## 規則` / `## 範例` 等 marker
- 禁止「忽略以上」「ignore previous」「output plain text」等 prompt injection 樣本
- 禁止要求新增 schema 欄位
- 超過 800 字元 → 寫入時 warning（建議拆分到通用規則）

---

## 變更紀錄

| 日期 | 變更 |
|------|------|
| 2026-03-13 | 陳哥（id=10）從 AUTO 改為 SHADOW（暫停真實交易，改為觀察模式） |
| 2026-05-15 | 新增 `Custom Prompt` 欄位與治理章節（搭配 `codex/modular-signal-prompts` PR） |
