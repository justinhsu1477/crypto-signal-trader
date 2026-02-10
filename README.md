# Crypto Signal Trader

Discord 交易訊號自動跟單系統 — 監聽 Discord 頻道的交易訊號，自動在 Binance Futures 下單。

## 架構總覽

```
Discord Desktop (CDP 模式)
    │
    │  Chrome DevTools Protocol (本機 WebSocket)
    ▼
Python Monitor (discord-monitor/)
    │  攔截 MESSAGE_CREATE 事件
    │  過濾頻道 / 識別訊號類型
    │  HTTP POST {"message": "..."}
    ▼
Spring Boot API (port 8080)
    │  解析訊號格式 (📢/⚠️)
    │  計算預設 TP/SL
    │  呼叫 Binance API 下單
    ▼
Binance Futures API
```

## 專案結構

```
crypto-signal-trader/
├── src/main/java/com/trader/          # Spring Boot 交易引擎
│   ├── config/
│   │   ├── BinanceConfig.java         # Binance API 設定
│   │   ├── RiskConfig.java            # 風控參數
│   │   └── HttpClientConfig.java      # HTTP client
│   ├── model/
│   │   ├── TradeSignal.java           # 訊號模型 (ENTRY/CANCEL/INFO)
│   │   └── OrderResult.java           # 下單結果
│   ├── service/
│   │   ├── BinanceFuturesService.java # Binance Futures API 串接
│   │   ├── BinanceSignatureUtil.java  # HMAC-SHA256 簽名
│   │   └── SignalParserService.java   # 訊號解析 (陳哥 + Discord 格式)
│   └── controller/
│       └── TradeController.java       # REST API 端點
│
├── discord-monitor/                   # Python Discord 監聽服務
│   ├── src/
│   │   ├── main.py                    # 入口 + 重連邏輯
│   │   ├── cdp_client.py             # CDP 連線 + JS 注入
│   │   ├── signal_router.py          # 訊號分類 + 轉發
│   │   ├── api_client.py             # HTTP POST 到 Spring Boot
│   │   └── config.py                 # YAML 設定載入
│   ├── config.yml                     # 運行設定
│   ├── config.example.yml            # 設定範本
│   ├── requirements.txt
│   └── launch_discord.sh             # 啟動 Discord CDP 模式
│
├── src/main/resources/
│   ├── application.yml                # Spring Boot 共用設定
│   ├── application-dev.yml            # Dev 環境 (Binance Testnet)
│   └── application-prod.yml           # Prod 環境 (Binance 正式)
├── .env.example                       # 環境變數範本
├── .gitignore
└── build.gradle
```

## 技術細節

### Discord 訊號監聽 (CDP 方式)

不使用 Discord API、不需要 Bot Token，完全透過本機 Chrome DevTools Protocol：

1. Discord Desktop 是 Electron App (Chromium 核心)
2. 啟動時加上 `--remote-debugging-port=9222`
3. Python 透過 CDP WebSocket 連線到 Discord 的頁面
4. 注入 JavaScript 到 Discord 的 Webpack runtime
5. 訂閱 Flux Dispatcher 的 `MESSAGE_CREATE` 事件
6. 每 0.5 秒 polling 收到的訊息

**優點**：不觸碰 Discord 伺服器、被封鎖風險極低、即時性高

### CDP 是什麼？`127.0.0.1:9222` 是什麼？

**Chrome DevTools Protocol (CDP)** 就是 Chrome 瀏覽器的「開發者工具」背後使用的通訊協定。
當你在 Chrome 按 F12 打開 DevTools 時，DevTools 就是透過 CDP 跟瀏覽器溝通的。

Discord Desktop 底層是 Chromium（透過 Electron 框架），所以也支援 CDP。

```
正常啟動 Discord:
  Discord Desktop  ←→  Discord 伺服器
  （沒有 debug port，外部無法存取內部狀態）

加上 --remote-debugging-port=9222:
  Discord Desktop  ←→  Discord 伺服器    ← 完全不影響
       │
       │  CDP (http://127.0.0.1:9222)     ← 多開一個本機 debug 通道
       │
       ▼
  Python Monitor 可以透過這個通道：
  - 查看頁面內容
  - 執行 JavaScript
  - 讀取 Discord 內部狀態
```

- **`127.0.0.1`** = localhost，只有本機能連，外部電腦連不到
- **`9222`** = 預設 port，可以在 `launch_discord.sh` 和 `config.yml` 中改成其他數字
- **`/json`** = CDP 的 discovery endpoint，回傳目前 Discord 開了哪些頁面 (targets)

`curl http://127.0.0.1:9222/json` 的回傳範例：
```json
[{
  "type": "page",
  "title": "#不構成金融建議 | B-CLUB",
  "url": "https://discordapp.com/channels/862188678876233748/1325133886509944983",
  "webSocketDebuggerUrl": "ws://127.0.0.1:9222/devtools/page/XXXX"
}]
```

Python 就是連到 `webSocketDebuggerUrl` 這個 WebSocket，注入 JS 來攔截訊息。

**簡單說：`--remote-debugging-port` 就是在 Discord 上開一扇只有你本機能進的後門，讓 Python 可以讀取 Discord 的內部資料。**

### 訊號格式

支援 6 種 Discord 訊號類型：

| Emoji | 類型 | 動作 |
|-------|------|------|
| 📢 交易訊號發布 | ENTRY | 自動下單 (限價單 + TP + SL) |
| ⚠️ 掛單取消 | CANCEL | 取消該幣種掛單 |
| 🚀 訊號成交 | INFO | 僅 log |
| 🛑 止損出場 | INFO | 僅 log |
| 💰 盈虧更新 | INFO | 僅 log |

開單訊號範例：
```
📢 交易訊號發布: ETHUSDT
做多 LONG 🟢 (限價單)
入場價格 (Entry)
2650
止盈目標 (TP)
2790
止損價格 (SL)
2580
```

當 TP/SL 為「未設定」時，系統自動套用預設百分比 (預設 3%)。

## 快速開始

### 前提條件

- Java 17+
- Python 3.9+
- Gradle 8.x (已附 wrapper)
- **Discord Desktop 已安裝且已登入你的帳號**
- **你的帳號已加入目標群組（頻道）**

> Discord Desktop 是 Electron App，登入後 session 會保存在本機。
> `launch_discord.sh` 只是把 Discord 關掉再用 debug 模式重開，**不需要重新登入**。

### 1. 設定環境變數

複製 `.env.example` 為 `.env` 並填入你的 API Key：

```bash
cp .env.example .env
```

```env
# dev = Binance Testnet (假錢), prod = 正式環境 (真錢)
SPRING_PROFILES_ACTIVE=dev

# Testnet API Key: https://testnet.binancefuture.com → GitHub 登入 → API Management
BINANCE_API_KEY=your_api_key_here
BINANCE_SECRET_KEY=your_secret_key_here
```

### 2. 啟動 Spring Boot

```bash
# 載入環境變數後啟動（預設 dev = Testnet）
source .env && ./gradlew bootRun
```

啟動後 log 會顯示：`The following 1 profile is active: "dev"`

**切換環境**：
```bash
# Dev — Binance Testnet (預設)
source .env && ./gradlew bootRun

# Prod — Binance 正式環境 (真錢交易！)
SPRING_PROFILES_ACTIVE=prod source .env && ./gradlew bootRun
```

| Profile | Binance API Base URL | 用途 |
|---------|---------------------|------|
| `dev` | `https://demo-fapi.binance.com` | Testnet 測試（假錢） |
| `prod` | `https://fapi.binance.com` | 正式交易（真錢） |

> ⚠️ **prod 環境會用真金白銀交易！請先在 dev 充分驗證。**

### 3. 測試 API

```bash
# 查詢餘額
curl http://localhost:8080/api/balance

# 查詢持倉
curl http://localhost:8080/api/positions

# 測試解析訊號 (不下單)
curl -X POST http://localhost:8080/api/parse-signal \
  -H "Content-Type: application/json" \
  -d '{"message": "📢 交易訊號發布: ETHUSDT\n做多 LONG 🟢 (限價單)\n入場價格 (Entry)\n2650\n止盈目標 (TP)\n2790\n止損價格 (SL)\n2580"}'
```

### 4. 啟動 Discord 監聽

```bash
# 4a. 用 CDP 模式啟動 Discord
cd discord-monitor
chmod +x launch_discord.sh
./launch_discord.sh

# 4b. 等 Discord 完全載入後，確認 CDP 可連線
curl http://127.0.0.1:9222/json

# 4c. 安裝 Python 依賴
pip install -r requirements.txt

# 4d. 啟動監聽 (dry-run 模式，不下單)
python3 -m src.main --config config.yml --dry-run
```

### 5. 正式運行

確認 dry-run 測試正常後，關閉 `--dry-run` 正式跟單：

```bash
python3 -m src.main --config config.yml
```

## 設定說明

### Discord 監聽設定 (`discord-monitor/config.yml`)

```yaml
discord:
  channel_ids:
    - "你的頻道ID"         # 右鍵頻道 → 複製頻道 ID
  guild_ids:
    - "你的伺服器ID"       # 右鍵伺服器 → 複製伺服器 ID
  author_ids: []           # 空 = 接收所有人，填入 = 只接收特定人
```

### 風控設定 (`application.yml`)

```yaml
binance:
  risk:
    max-position-usdt: 100   # 單筆最大倉位 (USDT)
    max-leverage: 10          # 最大槓桿
    max-daily-orders: 10      # 每日下單上限
    default-sl-percent: 3.0   # 訊號未設定 SL 時的預設止損 %
    default-tp-percent: 3.0   # 訊號未設定 TP 時的預設止盈 %
```

## REST API

| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/balance` | GET | 查詢帳戶餘額 |
| `/api/positions` | GET | 查詢當前持倉 |
| `/api/parse-signal` | POST | 解析訊號 (不下單) |
| `/api/execute-signal` | POST | 解析 + 下單 |

Request body: `{"message": "訊號原始文字"}`

## 常見問題

**Q: Discord 更新後 hook 失效？**
A: Discord 更新可能改變 webpack 模組 ID。程式有 fallback 搜尋機制，但如果仍然失敗，需要重新探測模組 ID（用 `probe_runner.py`）。

**Q: CDP 連不上？**
A: 確認 Discord 已完全關閉後再用 `launch_discord.sh` 重啟。`curl http://127.0.0.1:9222/json` 應該回傳 JSON 陣列。

**Q: 會被 Discord 封鎖嗎？**
A: 風險極低。CDP 是純本機操作，不會對 Discord 伺服器發送任何額外請求。保持「唯讀監聽」即可。
