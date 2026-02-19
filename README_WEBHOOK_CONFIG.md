# Discord Webhook 配置指南

## 快速參考

### 三個核心環境變數

```bash
# 啟用 per-user webhook（DB 中的用戶自定義 webhook）
DISCORD_WEBHOOK_PER_USER_ENABLED=true/false

# 當用戶無自定義 webhook 時，是否 fallback 到全局 webhook
DISCORD_WEBHOOK_FALLBACK=true/false

# 全局 webhook URL（測試和 fallback 使用）
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/xxx/yyy
DISCORD_WEBHOOK_ENABLED=true/false
```

---

## 三種環境配置

### 📱 開發環境（`.env.local`）

**目標：** 所有通知統一到一個 Admin 頻道，方便測試

```bash
DISCORD_WEBHOOK_PER_USER_ENABLED=false
DISCORD_WEBHOOK_ENABLED=true
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/YOUR_DEV_ADMIN_CHANNEL
```

**行為：**
- 所有用戶的跟單訊息都發到 Admin 頻道
- 容易看到整個系統的動作
- 測試多用戶場景不會亂

---

### 🧪 測試環境（`.env.staging`）

**目標：** 混合模式，優先 per-user，但沒有的也能看到

```bash
DISCORD_WEBHOOK_PER_USER_ENABLED=true
DISCORD_WEBHOOK_FALLBACK=true
DISCORD_WEBHOOK_ENABLED=true
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/YOUR_STAGING_ADMIN_CHANNEL
```

**行為：**
```
User1（有自定義）→ User1 的私人頻道
User2（有自定義）→ User2 的私人頻道
User3（無自定義）→ Admin 頻道（fallback）
```

---

### 🚀 正式環境（`.env.prod`）

**目標：** 優先 per-user，沒有的才 fallback，最嚴格配置

```bash
DISCORD_WEBHOOK_PER_USER_ENABLED=true
DISCORD_WEBHOOK_FALLBACK=true
DISCORD_WEBHOOK_ENABLED=true
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/YOUR_PROD_ADMIN_CHANNEL
```

**行為：** 同 staging，但通常用戶都已設定 webhook

---

## 概念解釋

### 全局 Webhook（Global Webhook）

- **存儲位置：** `application.yml` / 環境變數
- **配置方式：** 系統管理員設定
- **用途：** 整個系統的共享通知渠道
- **使用者：** Admin 或開發團隊共用的 Discord 頻道

### Per-User Webhook

- **存儲位置：** 資料庫 `UserDiscordWebhook` table
- **配置方式：** 用戶在 Dashboard Settings 自己設定
- **用途：** 用戶私人的通知渠道
- **使用者：** 每個用戶自己的 Discord 頻道

---

## 廣播跟單時的優先順序

```
廣播訊號到達
  ↓
DISCORD_WEBHOOK_PER_USER_ENABLED = true?
  ├─ YES
  │   ├─ 查詢 UserDiscordWebhook 表
  │   ├─ 找到用戶自定義 webhook?
  │   │   ├─ YES → 使用 per-user webhook ✅
  │   │   └─ NO → DISCORD_WEBHOOK_FALLBACK = true?
  │   │       ├─ YES → 使用全局 webhook ✅
  │   │       └─ NO → 靜默跳過 ❌
  │
  └─ NO → 直接使用全局 webhook ✅
```

---

## 實際例子

### 例子 1：測試 3 個用戶的廣播

**配置：**
```bash
DISCORD_WEBHOOK_PER_USER_ENABLED=false
DISCORD_WEBHOOK_ENABLED=true
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/admin-channel
```

**結果：**
```
Admin 頻道
├─ ✅ ENTRY BTCUSDT... (User1)
├─ ✅ ENTRY BTCUSDT... (User2)
└─ ✅ ENTRY BTCUSDT... (User3)
```

---

### 例子 2：正式環境，用戶部分設定

**配置：**
```bash
DISCORD_WEBHOOK_PER_USER_ENABLED=true
DISCORD_WEBHOOK_FALLBACK=true
DISCORD_WEBHOOK_ENABLED=true
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/admin-channel
```

**用戶設定：**
- User1：有自定義 webhook
- User2：有自定義 webhook
- User3：無自定義 webhook

**結果：**
```
User1 私人頻道
└─ ✅ ENTRY BTCUSDT...

User2 私人頻道
└─ ✅ ENTRY BTCUSDT...

Admin 頻道
└─ ✅ ENTRY BTCUSDT... (User3 - fallback)
```

---

## 常見問題

### Q: 上線時想從全局改成 per-user，需要改代碼嗎？

**A:** 不需要！只需改環境變數：
```bash
# 舊配置
DISCORD_WEBHOOK_PER_USER_ENABLED=false

# 改為
DISCORD_WEBHOOK_PER_USER_ENABLED=true
```

重啟應用即可。

---

### Q: 用戶設定 webhook 後，訊息不會發到全局頻道嗎？

**A:** 對，如果啟用了 per-user，用戶自定義的 webhook 優先級最高，訊息只會發到用戶自己的頻道。

如果想同時發到兩個地方，需要：
- 在用戶自定義 webhook 中添加多個 URL（未來功能）
- 或者用戶自己在 Discord 配置 webhook 時轉發訊息

---

### Q: fallback-to-global 關閉後，沒設 webhook 的用戶會收不到訊息？

**A:** 是的。訊息會靜默跳過。這是 SaaS 模式推薦的做法：
- 強制用戶必須設定 webhook
- 確保每個用戶都看得到自己的訊息

---

### Q: 全局 webhook 同時被用戶 A 和用戶 B 使用會不會有問題？

**A:** 不會。全局 webhook 是共享的，多個用戶的訊息都會發到同一個 Discord 頻道。

但會比較亂，因為無法區分是誰的訊息。這也是為什麼推薦正式環境使用 per-user 配置。

---

### Q: 我想在不同的業務邏輯中使用不同的 webhook，怎麼辦？

**A:** 系統目前支援：
1. **廣播跟單**：優先 per-user，fallback 到全局
2. **其他交易操作**（ENTRY/CLOSE/TP/SL）：用全局 webhook

如果需要區分，可以：
- 在 `TradeController` 或其他交易 endpoint 中手動指定 webhook
- 或者在 Discord 配置 webhook 時用不同的 URL

---

## Dashboard 用戶自定義 Webhook

用戶可以在 Dashboard Settings 頁面自己設定 webhook：

```
Settings → Discord 通知設定
  ├─ 查詢現有 Webhook
  │   └─ 顯示主要 webhook（最新啟用的）
  │
  └─ 新增 Webhook
      ├─ Webhook 名稱：「我的交易通知」
      ├─ Webhook URL：「https://discord.com/api/webhooks/...」
      └─ [新增按鈕]
```

**API 端點：**
```
GET    /api/dashboard/discord-webhooks          # 查詢所有 webhook
POST   /api/dashboard/discord-webhooks          # 新增 webhook
POST   /api/dashboard/discord-webhooks/{id}/disable  # 停用
DELETE /api/dashboard/discord-webhooks/{id}     # 刪除
```

---

## 🔧 上線檢查清單

- [ ] 確認 `.env` 中有設定 `DISCORD_WEBHOOK_PER_USER_ENABLED` 和 `DISCORD_WEBHOOK_FALLBACK`
- [ ] 確認 `DISCORD_WEBHOOK_URL` 指向正確的 Discord 頻道
- [ ] 測試廣播跟單是否發送訊息到正確的 webhook
- [ ] 測試用戶自定義 webhook 是否優先於全局 webhook
- [ ] 確認 Dashboard Settings 的 webhook 管理功能可用
- [ ] 確認自動跟單開關可以正常啟用/關閉

---

## 相關代碼位置

**配置：**
- `src/main/resources/application.yml` — webhook 配置定義
- `src/main/java/com/trader/shared/config/WebhookConfig.java` — 配置 class

**服務：**
- `src/main/java/com/trader/notification/service/DiscordWebhookService.java` — webhook 邏輯
- `src/main/java/com/trader/trading/service/BroadcastTradeService.java` — 廣播跟單

**數據庫：**
- `src/main/java/com/trader/user/entity/UserDiscordWebhook.java` — webhook entity
- `src/main/java/com/trader/user/repository/UserDiscordWebhookRepository.java` — webhook repository

**API：**
- `src/main/java/com/trader/dashboard/controller/DashboardController.java` — webhook 管理 endpoint

---

## 快速命令

### 本地開發

```bash
# 使用全局 webhook（推薦開發時使用）
export DISCORD_WEBHOOK_PER_USER_ENABLED=false
export DISCORD_WEBHOOK_ENABLED=true
export DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/YOUR_ADMIN_CHANNEL"

./gradlew bootRun
```

### Docker Compose

```yaml
# docker-compose.yml
environment:
  DISCORD_WEBHOOK_PER_USER_ENABLED: "false"
  DISCORD_WEBHOOK_ENABLED: "true"
  DISCORD_WEBHOOK_URL: "https://discord.com/api/webhooks/YOUR_CHANNEL"
```

### Kubernetes / K8s

```yaml
# deployment.yaml
env:
  - name: DISCORD_WEBHOOK_PER_USER_ENABLED
    value: "true"
  - name: DISCORD_WEBHOOK_FALLBACK
    value: "true"
  - name: DISCORD_WEBHOOK_ENABLED
    value: "true"
  - name: DISCORD_WEBHOOK_URL
    valueFrom:
      secretKeyRef:
        name: webhook-secret
        key: url
```

---

## 測試場景

### 場景 1：驗證廣播跟單能發送 per-user 通知

```bash
curl -X POST http://localhost:8080/api/broadcast-trade \
  -H "Content-Type: application/json" \
  -d '{
    "action": "ENTRY",
    "symbol": "BTCUSDT",
    "side": "LONG",
    "entryPrice": 95000,
    "stopLoss": 94000
  }'
```

查看每個用戶的 Discord 頻道是否收到訊息。

### 場景 2：驗證 fallback 機制

1. 設定 `DISCORD_WEBHOOK_FALLBACK=true`
2. 建立 User1 有自定義 webhook，User2 無自定義 webhook
3. 發送廣播訊號
4. 驗證 User1 的頻道收到訊息，User2 的訊息發到 fallback 頻道

### 場景 3：驗證優先級

1. 同時啟用全局和 per-user webhook
2. 確認訊息優先發送到 per-user webhook
3. 禁用 per-user webhook 後，訊息發送到全局 webhook

---

## 故障排除

### 訊息沒有被發送

**檢查清單：**
1. `DISCORD_WEBHOOK_ENABLED=true`？
2. `DISCORD_WEBHOOK_URL` 正確？
3. Discord webhook 還有效嗎？（有時候需要重新生成）
4. 日誌中有錯誤訊息嗎？查看 `log.warn("Discord Webhook 發送失敗")`

### 訊息發到了全局，但應該發到 per-user

**檢查清單：**
1. `DISCORD_WEBHOOK_PER_USER_ENABLED=true`？
2. 用戶在 Dashboard 設定了 webhook 嗎？
3. 用戶的 webhook 被停用了嗎？

### 用戶設定的 webhook 不工作

**檢查清單：**
1. Webhook URL 格式正確嗎？必須以 `https://discord.com/api/webhooks/` 開頭
2. Webhook 是否被 Discord 刪除了？
3. 在 Dashboard 嘗試重新設定 webhook

---

## 未來改進

- [ ] 支援多個 per-user webhook（目前只有一個主要 webhook）
- [ ] Webhook 測試功能（發送測試訊息）
- [ ] Webhook 連線狀態指示（綠色/紅色）
- [ ] Webhook 訊息日誌（查看發送記錄）
- [ ] 按訊息類型篩選 webhook（例如只接收失敗通知）
