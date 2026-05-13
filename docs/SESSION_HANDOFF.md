# Session Handoff — 給下一個 Claude Session 的接力文件

> 這份文件記錄 **2026-04 ~ 2026-05** 兩個多月的密集 iteration 成果。新 session 接手時看這份就能掌握「**最近做了什麼、目前狀態、踩過哪些坑**」。CLAUDE.md 是規則，這份是上下文。

---

## 1. 一句話現況

Discord 訊號自動跟單 SaaS，Python 監聽 + Java 後端 + Next.js dashboard，已 push 50+ commits 在過去 2 週，**2700+ tests 通過**，覆蓋圖訊號、複合動作、Admin chatbot、Eval harness、Capture 自我修復等。

---

## 2. 部署拓樸（必看）

```
本地（你的 Mac/Win 機器）              雲端 (DigitalOcean Singapore)
─────────────────────────             ─────────────────────────────
Discord 桌面 (debug port 9222)        Caddy :443 / Cloudflare CDN
        ↓ CDP                                ↓ /api/*
Python discord-monitor                trading-api (Spring Boot, Java 17)
  └─ src.main 進程                          ├── BroadcastTradeService (10-thread pool)
  └─ AI parser → /api/broadcast-trade       ├── BinanceFuturesService (per-user ThreadLocal)
  └─ 心跳每 30 秒                            ├── RabbitMQ (notification.exchange + DLQ)
                                            └── Redis cache
                                                       ↓
                                            Neon Postgres (Singapore region)
```

**關鍵**：
- **Java 後端**：GitHub Actions auto-deploy on push to main（5-10 分鐘）
- **Python**：跑在本地，**必須手動 git pull + 重啟**（不自動部署）
- **Discord**：必須帶 `--remote-debugging-port=9222` 啟動

---

## 3. 最近 2 週新功能（49 commits 概覽）

### 🖼️ Image Signal Parsing
- 從 Discord 圖片附件（紫色 banner）抽取交易訊號
- 用 Gemini 2.5 Flash multimodal
- 主要檔案：`discord-monitor/src/image_utils.py`、`ai_parser.parse_with_image()`
- Feature flag：`config.yml > image_signal.enabled`
- 上線狀態：**default false**（需手動開）

### 🔀 Compound Action (CLOSE + MOVE_SL)
- 識別「止盈X%做成本保护」這類混合指令
- 觸發兩個獨立 trade：CLOSE + MOVE_SL（breakeven）
- 主要檔案：`ai_parser.py` SYSTEM_PROMPT 「複合動作識別」、`signal_router._forward_compound()`
- 自動 dedup 用 `__close` / `__move_sl` suffix 繞過 Java L1
- Java 端 `executeMoveSLInternal` 對 `newStopLoss=null` 自動 breakeven + 0.1% 手續費補償

### 🤖 Admin Chatbot Tools
- DM bot 直接問「所有用戶餘額」「本週用戶獲利」「今天訊號狀況」
- 工具註冊在 `ChatbotActionExecutor.buildAllDeclarations()`
- 真實 Binance API 查詢（不是 DB cache）

### 📋 `discord_raw_messages` Audit Table（V45 migration）
- 每則 Discord 訊息獨立 row（不只訊號）
- 反向連結 `signal_id` 給已解析的訊號
- 解鎖「漏單偵測」自動 SQL audit
- 主要檔案：`DiscordRawMessageService`、`DiscordRawMessageController`
- Python: `_archive_message_async()` fire-and-forget
- 配套：180 天自動清理 + Prometheus counter + `@Version` 樂觀鎖

### 🎯 Eval Harness
- 30 個 hand-curated cases against real Gemini
- 主要檔案：`discord-monitor/eval/{runner,scorer,cases.jsonl}`
- 用法：`EVAL_GEMINI_MODEL=gemini-2.5-flash python3 -m eval.runner --delay 7 --json out.json`
- 真實準確率 **100%** (29/29 PASS, 1 個 503 噪音)

### 🛡️ CDP Capture Resilience（最近 3 個 feature）
1. **F1 Watchdog**: Python `secondsSinceAnyMessage` → Java `/api/health/deep` 4 小時無訊息 → DEGRADED
2. **F2 MESSAGE_UPDATE**: 訂閱編輯事件 + `__edit-{N}` suffix 繞過 dedup
3. **F3 Hook health check**: 60 秒主動驗 `window.__signalMonitorActive` + 自動 re-inject

### 📊 觀測層
- Prometheus metrics: `signal_image_total`, `signal_compound_total`, `discord_archive_total`, `chatbot_llm_calls_total`
- Rolling performance chatbot tool: 7d/30d/90d rolling 績效 + 衰退警示

---

## 4. 已知踩過的坑（不要重複犯）

### 🚨 5/13 Silent Capture Failure (production 2 天漏單)
**症狀**：Python 心跳正常但 `channelLastSeen={}` 兩天沒任何 channel 訊息
**根因**：Discord 桌面更新 / renderer reload → JS hook 失效，Python 不知道
**解法**：F3 hook health check + F1 watchdog（**已實作**）

### 🚨 Gemini 15 RPM Rate Limit
**症狀**：Eval 跑連續 30 cases → 半數 429 RESOURCE_EXHAUSTED
**解法**：`runner.py --delay 7`（free tier 15 RPM = 4s/call + buffer）
**Production note**：用 `gemini-2.5-flash`（同 prod）跑 eval

### 🚨 Timezone Bug（CI 抓到）
**症狀**：本地 Mac (Asia/Taipei) 過、CI (UTC) 炸
**根因**：測試用 `LocalDateTime.now()` 跟 service 的 `LocalDateTime.now(AppConstants.ZONE_ID)` 差 8 小時
**Pattern**：**所有 `LocalDateTime.now()` 都該帶 ZoneId**（生產 + 測試）

### 🚨 Hibernate 6 Nested Object[][]
**症狀**：Native query single-row 結果有時被 wrap 成 `Object[]{Object[]{...}}`
**解法**：永遠走 `MarketDataService.extractAggregateRow(stats)` helper

### 🚨 Java L1 Dedup 跟 Edit 衝突
**症狀**：陳哥編輯訊息，第二次送 broadcast-trade 被擋
**解法**：MESSAGE_UPDATE 訊息 message_id 加 `__edit-{N}` suffix

### 🚨 Mockito @MockBean State Pollution
**症狀**：同 test class 跨 nested groups 累積 invocations → `verify(never())` 失敗
**解法**：`@BeforeEach Mockito.reset(service)` 重置

### 🚨 `LocalDateTime.now()` Without Tz Across Services
找 service 程式碼，看是否有混用 `now()` vs `now(ZoneId)`。**統一用 `AppConstants.ZONE_ID`**。

---

## 5. Debug Cheat Sheet

### SSH 雲端 VM
```bash
ssh root@159.223.85.29
cd /root/crypto-signal-trader/
docker ps                                       # 看容器狀態
docker logs trading-api 2>&1 | tail -50         # 最近 log
docker logs trading-api --since "2026-05-14T12:00:00" 2>&1 | grep "broadcast"
```

### DB 查詢（Neon）
```python
mcp__Neon__run_sql:
  projectId: "delicate-darkness-24503688"
  databaseName: "trading"
```

### Health check
```bash
curl https://hook-fi.com/api/health           # 輕量
curl https://hook-fi.com/api/health/deep      # DB + Binance + capture
```

### 常用 SQL
```sql
-- 漏單偵測（V45 後可用）
SELECT message_timestamp, content
FROM discord_raw_messages
WHERE source_author_name = '陈哥合约频道'
  AND signal_id IS NULL AND parser_action IS NULL
  AND content ~ '止损|做多|做空|挂单'
  AND message_timestamp >= NOW() - INTERVAL '7 days';

-- 該頻道最近 ENTRY
SELECT created_at, action, side, entry_price_low, stop_loss
FROM signals
WHERE source_author_name = '陈哥合约频道' AND action='ENTRY'
ORDER BY created_at DESC LIMIT 10;

-- 廣播統計
SELECT signal_action, success_count, fail_count, skipped_no_key
FROM broadcast_logs WHERE created_at >= NOW() - INTERVAL '1 day';
```

---

## 6. 「要做 X 該看哪個檔」對照表

| 想改什麼 | 主要檔案 |
|---|---|
| Discord 訊號接收邏輯 | `discord-monitor/src/signal_router.py` |
| CDP JS hook（webpack/Dispatcher）| `discord-monitor/src/cdp_client.py` INJECT_JS (line 22-185) |
| AI parser prompt（few-shots）| `discord-monitor/src/ai_parser.py` SYSTEM_PROMPT（16k 字符）|
| 圖訊號處理 | `discord-monitor/src/image_utils.py` + `signal_router._handle_image_signal()` |
| 廣播下單 | `BroadcastTradeService.broadcastTrade()` |
| 風控 10 層 | `BinanceFuturesService.executeSignalInternal()` |
| 訊號去重 | `SignalDeduplicationService` (L1/L2/L3) |
| Chatbot 工具註冊 | `ChatbotActionExecutor.buildAllDeclarations()` + `executeFunction()` switch |
| Admin Discord bot listener | `DiscordBotListener.onMessageReceived()` |
| 健康檢查 | `HealthController.deepHealth()` |
| 心跳處理 | `MonitorHeartbeatService.receiveHeartbeat()` |
| Audit raw messages | `DiscordRawMessageService.recordMessage()` |
| Eval | `discord-monitor/eval/{runner.py, scorer.py, cases.jsonl}` |

---

## 7. 測試金字塔

| 層 | 工具 | 範例檔 |
|---|---|---|
| **Unit (主力，~85%)** | Mockito + JUnit / pytest | `test_signal_router_*.py`, `*ServiceTest.java` |
| **Contract** | JSON fixtures 雙邊載入 | `tests/fixtures/payloads/` + `PythonPayloadContractTest`, `test_payload_contract.py` |
| **Slice (@WebMvcTest)** | Spring 真 HTTP/Jackson/Security | `TradeControllerSliceTest`, `DiscordRawMessageControllerSliceTest` |
| **Integration (@SpringBootTest)** | Testcontainers PostgreSQL | `*IntegrationTest.java`（需 Docker daemon）|
| **Eval (AI quality)** | Real Gemini calls + scoring | `discord-monitor/eval/runner.py` |

---

## 8. Eval Workflow（改 prompt 必跑）

```bash
cd /Users/justinhsu/Desktop/sideproject/crypto-signal-trader/discord-monitor
export $(grep "^GEMINI_API_KEY=" ../.env | head -1)

# 1. 改 prompt（ai_parser.py SYSTEM_PROMPT）
# 2. 跑 eval（30 cases × 7s ≈ 3.5 分鐘）
EVAL_GEMINI_MODEL=gemini-2.5-flash python3 -m eval.runner --delay 7 --json eval_iterN.json

# 3. 看 by-category 哪邊掉分
# 4. 看失敗 case 的 input + expected + actual
# 5. 修 prompt 或 fix test case → re-run

# Exit code 0 = ≥80% overall PASS (CI 可用)
```

**真實 baseline**（2026-05-13）：
- v1 baseline (含 rate limit 噪音): 40% overall, 75% real
- v2 after prompt fix: 76.7% overall, 88.5% real
- v3 after 短/中長線雙段 + test fix: **96.7% overall, 100% real (29/29)**

---

## 9. Production Rollout Checklist

當有 push 改動，**Python 端必須手動同步**：

```bash
# 在你跑 Python 那台機器（不是雲端 VM）
cd <crypto-signal-trader 目錄>
git pull --ff-only origin main

# 殺舊 Python（看你怎麼啟動的）
pkill -f "python.*src.main"       # Mac/Linux
# 或 Windows: taskkill /F /IM python.exe

# （如果 CDP JS hook 有改）重啟 Discord
./launch_discord.sh               # Mac
# launch_discord.bat 9222         # Windows

# 重啟 Python
cd discord-monitor
python3 -m src.main --config config.yml
```

**驗證啟動**：log 應該看到
```
JS hook injection result: ok
Connected! Listening for trading signals...
```

---

## 10. 過時 / 不準的文件

| 檔案 | 問題 | 該怎麼讀 |
|---|---|---|
| `docs/architecture-roadmap.md` | Phase 2 RabbitMQ 描述「未來」但已做完 | 看成歷史 |
| `ROADMAP.md` | 只到 Phase 2，沒記 Phase 3+ | 看成歷史 |
| `INTERVIEW_PREP.md` | 個人面試準備 | 跳過 |
| `discord-monitor/discord_gateway.py` | **Dead code**（120 行）| **可刪** |

---

## 11. 還沒做但被討論過的事項

| 項目 | 為什麼沒做 | 何時做 |
|---|---|---|
| Per-source 連虧 5 筆 auto-SHADOW | 5/13 翻車後分析發現 | 待 user 決定 |
| R-multiple 計算 + 寫 trades 表 | 解鎖跨幣比較 | 待 user 決定 |
| Daily drawdown limit 收緊 | 5/1-5/6 連虧 $4737 沒觸發 | 待 user 決定 |
| Dashboard 加 rolling widget | 後端有了，前端 Next.js work | 待 user 決定 |
| Auto-restart Discord | 跨平台 + 用戶體驗風險 | 已 skip（用 F3 + watchdog 取代）|
| Per-channel P95 baseline 計算 | 排程任務 + signal_source_config 加欄位 | F1 Layer 2 進化版 |

---

## 12. 商業相關（產品角度）

| 維度 | 狀態 |
|---|---|
| 訂閱 | USDT TRC20 鏈上驗證 ✅ |
| 多用戶 | 廣播跟單 + per-user 風控（global config）✅ |
| Admin chatbot | DM 互動式 ✅ |
| Weekly report | 每週一 09:00 自動推 ✅ |
| Daily signal report | 每天 23:59 ✅ |
| Onboarding 流程 | ❌ 缺（新用戶引導加 API Key）|
| Plan tiers | ❌ 只有單一 plan |
| Referral leaderboard | ❌ 有 referral 但沒 leaderboard |

---

## 13. 給下一個 Session 的開場提示

如果你是接手的 Claude session：

1. **先讀 CLAUDE.md**（auto-load）
2. **再讀這份 SESSION_HANDOFF.md**（你現在看的）
3. 如果要動程式碼 → 對照「§6 要做 X 該看哪個檔」找入口
4. 改 AI prompt → 跑 eval（§8）
5. 部署改動 → Java auto-deploy；Python 提醒用戶手動 pull
6. 任何「我以為這沒做」→ 先用 `git log --oneline -50` 跟 grep 確認

**最常用 sanity check**：
```bash
git log --oneline -20         # 最近改動
docker ps                     # （SSH 雲端後）容器狀態
curl https://hook-fi.com/api/health/deep
```

---

> 文件最後更新：2026-05-14
> 過去 2 週主要 contributor: Claude session（user: justin80605@gmail.com）
