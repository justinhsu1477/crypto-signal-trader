# Crypto Signal Trader

Discord 交易訊號自動跟單系統 — 監聽 Discord 頻道訊號，自動在 Binance Futures 下單。

## 架構

```
Discord Desktop (CDP 模式)
    │  Chrome DevTools Protocol
    ▼
Python Monitor (discord-monitor/)
    │  過濾頻道 → 識別訊號 → POST to API
    ▼
Spring Boot API (Docker, port 8080)
    │  解析訊號 → 風控檢查 → Binance 下單
    │  → Discord Webhook 通知結果
    ▼
Binance Futures API
```

## 快速開始

### 1. 設定環境變數

```bash
cp .env.example .env
# 編輯 .env 填入 Binance API Key 和 Discord Webhook URL
```

### 2. 啟動 Java API（Docker）

```bash
# 建置 + 啟動
docker-compose up --build -d

# 查看 log
docker logs -f trading-api

# 停止
docker-compose down

# 改完程式碼後重建
docker-compose up --build -d
```

### 3. 啟動 Discord 監聽

```bash
# 用 CDP 模式啟動 Discord（會先關閉現有 Discord 再重開）
cd discord-monitor
chmod +x launch_discord.sh
./launch_discord.sh

# 等 Discord 完全載入後，確認 CDP 可連
curl http://127.0.0.1:9222/json

# 安裝 Python 依賴（首次）
pip install -r requirements.txt

# 啟動監聽（dry-run 不下單，測試用）
python3 -m src.main --config config.yml --dry-run

# 正式跟單
python3 -m src.main --config config.yml
```

### 4. 測試 API

```bash
# 查詢餘額
curl http://localhost:8080/api/balance

# 測試解析訊號（不下單）
curl -X POST http://localhost:8080/api/parse-signal \
  -H "Content-Type: application/json" \
  -d '{"message": "📢 交易訊號發布: BTCUSDT\n做多 LONG 🟢 (限價單)\n入場價格 (Entry)\n95000\n止盈目標 (TP)\n98000\n止損價格 (SL)\n93000"}'
```

## 訊號格式

| Emoji / 關鍵字 | 類型 | 動作 |
|----------------|------|------|
| 📢 交易訊號發布 | ENTRY | 限價單 + TP + SL |
| ⚠️ 掛單取消 | CANCEL | 取消該幣種掛單 |
| TP-SL 修改 | MODIFY | 重新掛 TP/SL |
| 🚀 訊號成交 / 🛑 止損 / 💰 盈虧 | INFO | 僅 log |

## 設定說明

### `.env` 環境變數

```env
SPRING_PROFILES_ACTIVE=dev          # dev=Testnet假錢, prod=正式真錢
BINANCE_API_KEY=your_key
BINANCE_SECRET_KEY=your_secret
DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/你的ID/你的TOKEN
DISCORD_WEBHOOK_ENABLED=true
```

### Discord 監聽 (`discord-monitor/config.yml`)

```yaml
discord:
  channel_ids:
    - "頻道ID"        # 右鍵頻道 → 複製頻道 ID
  guild_ids:
    - "伺服器ID"      # 右鍵伺服器 → 複製伺服器 ID
  author_ids: []      # 空 = 所有人，填入 = 指定作者
```

### 風控 (`application.yml`)

```yaml
binance:
  risk:
    fixed-loss-per-trade: 500.0  # 以損定倉：單筆虧損上限 (USDT)
    max-positions: 1             # 最大同時持倉數
    fixed-leverage: 20           # 固定槓桿 (逐倉)
    allowed-symbols:             # 交易對白名單
      - BTCUSDT
```

### Webhook 通知

所有操作結果即時推送到 Discord 頻道：

| 事件 | Emoji |
|------|-------|
| ENTRY 成功 | ✅ |
| 操作失敗 | ❌ |
| CANCEL 取消 | 🚫 |
| TP/SL 修改 | 🔄 |
| CLOSE 平倉 | 💰 |
| 風控攔截 | ⚠️ |
| 重複跳過 | ⏭️ |

## REST API

| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/balance` | GET | 帳戶餘額 |
| `/api/positions` | GET | 當前持倉 |
| `/api/open-orders?symbol=BTCUSDT` | GET | 未成交訂單 |
| `/api/parse-signal` | POST | 解析訊號（不下單） |
| `/api/execute-signal` | POST | 解析 + 下單 |
| `/api/execute-trade` | POST | 結構化 JSON 下單 |
| `/api/trades` | GET | 交易紀錄（`?status=OPEN`） |
| `/api/trades/{id}` | GET | 單筆詳情 |
| `/api/trades/{id}/events` | GET | 事件日誌 |
| `/api/stats/summary` | GET | 盈虧統計 |
