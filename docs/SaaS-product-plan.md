# Crypto Signal Trader — SaaS 產品化計畫

> 前提：先用自己帳戶跑 1~3 個月，累積績效數據，確認勝率後再開始。

---

## 一、產品定位

### 目標用戶
- 已加入 Discord 付費訊號群的虛擬貨幣交易者
- 想自動跟單但不會寫程式的人
- 有幣安合約帳戶，願意用 API Key 授權的用戶

### 核心價值
- 「填 API Key → 選風控參數 → 自動跟單」三步驟完成
- 用戶不需要跑任何程式，不需懂 Docker / Python
- 訊號源由平台統一管理（你的 Discord 帳號接收），用戶只管交易

### 競品參考
| 產品 | 月費 | 模式 |
|------|------|------|
| 3Commas | $22~$100 | 用戶填 API Key，平台代下單 |
| Cornix | $19~$59 | 綁定 Telegram 訊號群，自動跟單 |
| WunderTrading | $24~$90 | TradingView Alert → 自動下單 |

---

## 二、產品架構

### 現在（單人版）
```
Discord 訊號群
    ↓
Python Monitor（本地 CDP）
    ↓
Spring Boot API（本地 Docker）
    ↓ 用一組 API Key
Binance Futures
```

### 產品化後（多租戶 SaaS）
```
Discord 訊號群
    ↓
Python Monitor（雲端 Server）
    ↓ 訊號解析一次，廣播給所有用戶
Spring Boot API（雲端 Server）
    ├─ 用戶 A 的 API Key → Binance 帳戶 A
    ├─ 用戶 B 的 API Key → Binance 帳戶 B
    └─ 用戶 C 的 API Key → Binance 帳戶 C
    ↓
各用戶的 Discord Webhook（各自收通知）
```

### 未來可擴展
```
Web Dashboard（前端）
    ↓ JWT 認證
Spring Boot API
    ↓
PostgreSQL（用戶資料、交易紀錄）
```

---

## 三、技術改造清單

### Phase 1：基礎建設（2 週）

#### 1.1 資料庫遷移：H2 → PostgreSQL
- Docker Compose 加 PostgreSQL container
- 修改 `application.yml` datasource 設定
- 用 Flyway 管理 schema migration
- 保留 H2 給 dev profile 跑單元測試

#### 1.2 User Entity + 認證
```java
@Entity
@Table(name = "users")
public class User {
    @Id
    private String userId;              // UUID
    private String email;               // 登入帳號
    private String passwordHash;        // BCrypt

    // Binance 連線
    private String binanceApiKey;       // AES 加密存儲
    private String binanceSecretKey;    // AES 加密存儲

    // 通知
    private String discordWebhookUrl;   // 用戶自己的 Webhook

    // 狀態
    private boolean active;             // 是否啟用跟單
    private LocalDateTime createdAt;
}
```

#### 1.3 UserRiskConfig Entity
```java
@Entity
@Table(name = "user_risk_configs")
public class UserRiskConfig {
    @Id
    private String userId;

    private double riskPercent;          // 單筆風險比例
    private double maxPositionUsdt;      // 最大名目價值
    private double maxDailyLossUsdt;     // 每日虧損熔斷
    private int maxPositions;            // 最大同時持倉數
    private int fixedLeverage;           // 固定槓桿
    private String allowedSymbols;       // 白名單 (JSON array)
}
```

#### 1.4 Trade / TradeEvent 加 userId
- `Trade` 表新增 `userId` 欄位
- `TradeEvent` 表新增 `userId` 欄位
- 所有 Repository query 加上 `AndUserId` 過濾

#### 1.5 Spring Security + JWT
- 登入 → 發 JWT token
- 所有 API 加 `@AuthenticationPrincipal` 取得 userId
- Python Monitor 用 service account token 呼叫 API

---

### Phase 2：核心服務多租戶改造（2 週）

#### 2.1 BinanceFuturesService 重構
```
現在: 一個 singleton，用全局 API Key
改成: 接收 userId → 從 DB 查該用戶的 API Key → 下單
```

兩種策略（擇一）：

**方案 A：方法傳參（推薦）**
- 所有方法加 `userId` 參數
- 每次下單前查 DB 取 API Key
- 用 Caffeine Cache 快取 5 分鐘，避免每次查 DB

**方案 B：TenantContext（ThreadLocal）**
- 用 Filter 設定當前 userId 到 ThreadLocal
- Service 層透明取用，不改方法簽名
- 風險：異步操作（Webhook callback）可能丟失 context

#### 2.2 RiskConfig 改為 per-user
```
現在: @ConfigurationProperties 載入 application.yml
改成: UserRiskConfigService.getConfig(userId) 查 DB
```
- 加 Caffeine Cache（TTL 5 分鐘）
- 用戶改設定時 invalidate cache

#### 2.3 DiscordWebhookService per-user
```
現在: 全局一個 Webhook URL
改成: 查 user.discordWebhookUrl 發通知
```

#### 2.4 訊號廣播機制
```
Python Monitor 收到訊號
  → POST /internal/signal（internal API，不經過用戶認證）
  → SignalBroadcastService 查出所有 active 用戶
  → 對每個用戶：風控檢查 → 下單 → 通知
  → 用 CompletableFuture 並行處理，互不影響
```

```java
@Service
public class SignalBroadcastService {

    public void broadcastSignal(TradeSignal signal) {
        List<User> activeUsers = userRepository.findByActiveTrue();

        activeUsers.parallelStream().forEach(user -> {
            try {
                executeForUser(user.getUserId(), signal);
            } catch (Exception e) {
                log.error("用戶 {} 下單失敗: {}", user.getUserId(), e.getMessage());
                // 一個用戶失敗不影響其他人
            }
        });
    }
}
```

---

### Phase 3：API 層改造（1 週）

#### 3.1 用戶管理 API
| 端點 | 方法 | 說明 |
|------|------|------|
| `/api/auth/register` | POST | 註冊 |
| `/api/auth/login` | POST | 登入，回傳 JWT |
| `/api/user/profile` | GET/PUT | 查看/修改個人資料 |
| `/api/user/binance-key` | PUT | 設定幣安 API Key |
| `/api/user/risk-config` | GET/PUT | 查看/修改風控參數 |
| `/api/user/webhook` | PUT | 設定 Discord Webhook URL |
| `/api/user/toggle` | POST | 開啟/暫停跟單 |

#### 3.2 交易查詢 API（已有，加認證）
| 端點 | 方法 | 改動 |
|------|------|------|
| `/api/trades` | GET | 加 JWT → 只回傳該用戶的交易 |
| `/api/stats/summary` | GET | 加 JWT → 只統計該用戶的數據 |
| `/api/monitor-status` | GET | 不變（全局） |

#### 3.3 Internal API（Python Monitor 用）
| 端點 | 方法 | 說明 |
|------|------|------|
| `/internal/broadcast-signal` | POST | 收到訊號，廣播給所有用戶 |
| `/internal/broadcast-trade` | POST | AI 解析後的結構化訊號廣播 |
| `/internal/heartbeat` | POST | 心跳（不變） |

Internal API 用固定 token 認證，不經過 JWT。

---

### Phase 4：安全性（1 週）

#### 4.1 API Key 加密存儲
- 使用 AES-256-GCM 加密用戶的 Binance API Key
- 加密 key 存環境變數，不入 DB
- 讀取時即時解密，記憶體中不長期保留明文

```java
@Service
public class EncryptionService {
    // AES_KEY 從環境變數載入
    public String encrypt(String plainText) { ... }
    public String decrypt(String cipherText) { ... }
}
```

#### 4.2 API Key 權限驗證
- 用戶填入 API Key 後，呼叫幣安 API 驗證：
  - ✅ 能讀取帳戶資訊
  - ✅ 能下合約單
  - ❌ 不能提幣（如果有提幣權限，警告用戶關閉）
  - ✅ 有設定 IP 白名單（建議但不強制）

#### 4.3 Rate Limiting
- 每用戶 API 呼叫限流（避免濫用）
- 幣安 API 全局限流（避免超過幣安限制）

---

### Phase 5：部署架構（1 週）

#### 5.1 Docker Compose（生產環境）
```yaml
services:
  trading-api:
    build: .
    ports:
      - "8080:8080"
    env_file: .env
    depends_on:
      - postgres

  discord-monitor:
    build: ./discord-monitor
    env_file: .env
    depends_on:
      trading-api:
        condition: service_healthy

  postgres:
    image: postgres:16
    volumes:
      - pg-data:/var/lib/postgresql/data
    environment:
      POSTGRES_DB: trading
      POSTGRES_USER: trader
      POSTGRES_PASSWORD: ${DB_PASSWORD}

volumes:
  pg-data:
```

#### 5.2 雲端部署選項
| 選項 | 成本 | 適合階段 |
|------|------|---------|
| **VPS (Contabo/Hetzner)** | $5~15/月 | MVP 初期，10 人以下 |
| **AWS EC2 t3.small** | ~$15/月 | 正式上線，50 人以下 |
| **AWS ECS + RDS** | ~$50/月 | 規模化，100+ 用戶 |

#### 5.3 Discord 監聽部署
- Discord Desktop 需要 GUI 環境
- 雲端方案：VPS + 虛擬桌面（VNC/xvfb）跑 Discord
- 或者用 Discord.js Bot（如果訊號群允許 Bot）

---

## 四、收費模式建議

| 方案 | 月費 | 目標用戶 |
|------|------|---------|
| 免費試用 | 0（7 天） | 體驗功能 |
| 基本版 | $15~20 | 1 個訊號源、1 個幣種 |
| 進階版 | $40~60 | 多訊號源、多幣種、自訂風控 |
| 專業版 | $80~100 | API 存取、優先支援 |

---

## 五、開發時程

| 階段 | 內容 | 時間 | 前置條件 |
|------|------|------|---------|
| **現在** | 自己跑 Testnet / 小資金實測 | 1~3 個月 | 無 |
| **Phase 1** | DB 遷移 + User + JWT | 2 週 | 績效數據確認 |
| **Phase 2** | 多租戶服務改造 | 2 週 | Phase 1 完成 |
| **Phase 3** | API 層 + 用戶管理 | 1 週 | Phase 2 完成 |
| **Phase 4** | 安全性（加密、限流） | 1 週 | Phase 3 完成 |
| **Phase 5** | 部署 + 測試 | 1 週 | Phase 4 完成 |
| **Phase 6** | Web Dashboard（可選） | 2~4 週 | Phase 5 完成 |

**總計：7~11 週（不含 Dashboard）**

---

## 六、風險與注意事項

### 法律風險
- 代客操作虛擬貨幣可能涉及金融監管（各國不同）
- 建議：明確免責聲明「平台只提供技術服務，不構成投資建議」
- 參考 3Commas / Cornix 的免責條款

### 技術風險
| 風險 | 影響 | 緩解方式 |
|------|------|---------|
| 幣安 API 限流 | 用戶數多時超過 rate limit | 下單排隊機制 + 限制用戶數 |
| 訊號延遲 | 用戶太多，後面的用戶下單晚 | 異步並行處理 + 監控延遲 |
| Discord 斷線 | 所有用戶都收不到訊號 | 心跳告警（已有）+ 自動重連 |
| 用戶 API Key 洩漏 | 重大信任危機 | AES 加密 + 最小權限 + 安全審計 |

### 營運風險
- 訊號源品質下降 → 用戶虧錢 → 退訂
- 緩解：透明展示歷史績效、允許用戶隨時暫停

---

## 七、MVP 檢查清單

產品化最小可行版本需要的功能：

- [ ] PostgreSQL 替換 H2
- [ ] User 註冊/登入 (JWT)
- [ ] 用戶填入 Binance API Key（加密存儲）
- [ ] 用戶設定風控參數
- [ ] 訊號廣播：一個訊號 → 所有用戶各自下單
- [ ] 每用戶獨立交易紀錄
- [ ] 每用戶獨立 Discord 通知
- [ ] 每用戶獨立每日摘要
- [ ] API Key 權限驗證（確認無提幣權限）
- [ ] 部署到雲端 VPS
- [ ] 免責聲明頁面
