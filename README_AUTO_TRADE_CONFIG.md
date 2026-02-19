# 自動跟單開關配置指南

## 快速參考

### 核心概念

- **自動跟單開關** (`autoTradeEnabled`)：用戶級別的設定，控制該用戶是否接收廣播跟單訊號
- **位置**：存儲在 `User` entity 中
- **默認值**：`true`（新用戶默認啟用）

---

## API 接口

### 查詢自動跟單狀態

```bash
GET /api/dashboard/auto-trade-status

# 回應
{
  "userId": "user123",
  "autoTradeEnabled": true
}
```

### 更新自動跟單狀態

```bash
POST /api/dashboard/auto-trade-status
Content-Type: application/json

{
  "enabled": true  # 或 false
}

# 回應
{
  "userId": "user123",
  "autoTradeEnabled": true,
  "message": "已啟用自動跟單"
}
```

---

## Dashboard 用戶界面

用戶可以在 Settings 頁面看到自動跟單開關：

```
Settings → 自動跟單設定
  ├─ 開關按鈕（啟用/關閉）
  ├─ 描述：「當啟用時，您的帳戶將自動接收廣播跟單訊號」
  ├─ 狀態提示
  │   ├─ 啟用時：✓ 已啟用自動跟單，您將接收廣播訊號
  │   └─ 關閉時：⚠️ 已關閉自動跟單，廣播訊號將不會對您執行交易
  └─ [更新按鈕]
```

---

## 廣播跟單時的檢驗邏輯

```java
// BroadcastTradeService.java
List<User> activeUsers = userRepository.findAll().stream()
    .filter(User::isAutoTradeEnabled)    // ← 檢查這個欄位
    .filter(User::isEnabled)             // ← 檢查用戶是否啟用
    .toList();
```

**邏輯：**
1. 廣播跟單訊號到達
2. 系統查詢所有用戶
3. 篩選條件：
   - `autoTradeEnabled = true`（用戶啟用自動跟單）
   - `enabled = true`（用戶帳戶啟用）
4. 只對符合條件的用戶執行廣播跟單

---

## 場景說明

### 場景 1：用戶啟用自動跟單

**配置：**
```
User1: autoTradeEnabled = true
User2: autoTradeEnabled = true
User3: autoTradeEnabled = false
```

**廣播訊號：** ENTRY BTCUSDT LONG

**結果：**
```
User1 ✅ 執行跟單
User2 ✅ 執行跟單
User3 ❌ 跳過（已關閉自動跟單）
```

---

### 場景 2：用戶暫時關閉自動跟單（例如假期）

**用戶流程：**
1. 用戶登入 Dashboard
2. 進入 Settings
3. 點擊「自動跟單」開關，關閉
4. 收到「已關閉自動跟單」的確認訊息

**效果：**
- 廣播跟單訊號來臨時被自動跳過
- 無需聯絡管理員
- 用戶可隨時重新啟用

---

## 前端實現

### React 組件結構

```tsx
// app/settings/page.tsx

// 1. 查詢狀態
const [autoTradeStatus, setAutoTradeStatus] = useState<AutoTradeStatus | null>(null);
const [autoTradeUpdating, setAutoTradeUpdating] = useState(false);

// 2. 監聽
useEffect(() => {
  const status = await getAutoTradeStatus();
  setAutoTradeStatus(status);
}, []);

// 3. 切換開關
async function handleToggleAutoTrade(enabled: boolean) {
  setAutoTradeUpdating(true);
  try {
    const result = await updateAutoTradeStatus(enabled);
    setAutoTradeStatus(result);
    // 顯示成功訊息
  } finally {
    setAutoTradeUpdating(false);
  }
}

// 4. 渲染
<Switch
  checked={autoTradeStatus.autoTradeEnabled}
  onCheckedChange={handleToggleAutoTrade}
  disabled={autoTradeUpdating}
/>
```

### TypeScript 型別

```typescript
export interface AutoTradeStatus {
  userId: string;
  autoTradeEnabled: boolean;
}

export interface AutoTradeUpdateRequest {
  enabled: boolean;
}

export interface AutoTradeUpdateResponse {
  userId: string;
  autoTradeEnabled: boolean;
  message: string;
}
```

### API 調用

```typescript
// lib/api.ts

export async function getAutoTradeStatus(): Promise<AutoTradeStatus> {
  const response = await fetch('/api/dashboard/auto-trade-status', {
    method: 'GET',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
  });
  if (!response.ok) throw new Error('Failed to fetch auto trade status');
  return response.json();
}

export async function updateAutoTradeStatus(enabled: boolean) {
  const response = await fetch('/api/dashboard/auto-trade-status', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify({ enabled }),
  });
  if (!response.ok) throw new Error('Failed to update auto trade status');
  return response.json();
}
```

---

## 數據庫

### User Entity

```java
@Entity
public class User {
    @Id
    private String userId;

    // ... 其他欄位 ...

    @Builder.Default
    private boolean autoTradeEnabled = true;  // ← 新加欄位

    // ... 其他欄位 ...
}
```

### 數據庫遷移

```sql
-- 如果使用 PostgreSQL
ALTER TABLE users ADD COLUMN auto_trade_enabled BOOLEAN DEFAULT true;

-- 如果使用 H2（Hibernate 會自動處理）
-- 無需手動遷移
```

---

## Dashboard Overview 頁面

自動跟單狀態也會顯示在 Dashboard Overview 首頁：

```json
{
  "account": {
    "availableBalance": 10000,
    "openPositionCount": 2,
    "todayPnl": 150,
    "todayTradeCount": 3
  },
  "riskBudget": {
    "dailyLossLimit": 2000,
    "todayLossUsed": 150,
    "remainingBudget": 1850,
    "circuitBreakerActive": false
  },
  "subscription": {
    "plan": "pro",
    "active": true,
    "expiresAt": "2025-12-31"
  },
  "autoTradeEnabled": true,  // ← 新加欄位
  "positions": [...]
}
```

---

## 常見問題

### Q: 用戶關閉自動跟單後，現有持倉會怎樣？

**A:** 關閉自動跟單只影響「新的廣播訊號」。
- 現有持倉繼續有效
- 止損單繼續生效
- 只是不接收新的廣播跟單訊號

---

### Q: 自動跟單默認啟用，怎麼改成默認關閉？

**A:** 修改 User entity：

```java
@Builder.Default
private boolean autoTradeEnabled = false;  // 改成 false
```

新建用戶會默認關閉，但現有用戶的設定不變。

---

### Q: 廣播跟單時，錯誤通知也會被跳過嗎？

**A:** 是的。如果用戶的 `autoTradeEnabled = false`：
- 不發送廣播訊號
- 也不發送成功/失敗通知
- 完全靜默（不打擾用戶）

如果想看到廣播執行結果，需要啟用自動跟單。

---

### Q: 廣播跟單和單用戶的 ENTRY 訊號區別？

**A:**
| | 廣播跟單 | 單用戶 ENTRY |
|---|---|---|
| 來源 | `/api/broadcast-trade` | `/api/execute-signal` 或 `/api/execute-trade` |
| 受 autoTradeEnabled 影響 | ✅ 是 | ❌ 否 |
| 發送 per-user 通知 | ✅ 是 | ❌ 發全局通知 |
| 適用場景 | 訊號源廣播給多個用戶 | 單個用戶手動操作 |

---

## 上線檢查清單

- [ ] 確認 User entity 有 `autoTradeEnabled` 欄位
- [ ] 確認 Dashboard Settings 有自動跟單開關
- [ ] 測試啟用/關閉開關，確認狀態保存
- [ ] 測試廣播跟單，確認只執行啟用的用戶
- [ ] 確認關閉自動跟單後，廣播訊號被跳過
- [ ] 確認 Overview 頁面顯示 autoTradeEnabled 狀態
- [ ] 測試數據庫遷移（如果是 PostgreSQL）

---

## 相關代碼位置

**Entity & Repository：**
- `src/main/java/com/trader/user/entity/User.java` — User entity
- `src/main/java/com/trader/user/repository/UserRepository.java` — User repository

**服務層：**
- `src/main/java/com/trader/trading/service/BroadcastTradeService.java` — 廣播跟單邏輯
- `src/main/java/com/trader/dashboard/service/DashboardService.java` — Overview 邏輯

**API & DTO：**
- `src/main/java/com/trader/dashboard/controller/DashboardController.java` — auto-trade endpoint
- `src/main/java/com/trader/dashboard/dto/DashboardOverview.java` — Overview DTO

**前端（web-dashboard）：**
- `src/app/settings/page.tsx` — Settings 頁面
- `src/components/settings/auto-trade-toggle.tsx` — 開關組件（可選，或內嵌在 settings）
- `src/lib/api.ts` — API 調用函數
- `src/types/index.ts` — TypeScript 型別

---

## 測試場景

### 場景 1：測試開關功能

```bash
# 1. 啟用自動跟單
curl -X POST http://localhost:8080/api/dashboard/auto-trade-status \
  -H "Content-Type: application/json" \
  -d '{"enabled": true}'

# 2. 查詢狀態
curl http://localhost:8080/api/dashboard/auto-trade-status

# 3. 關閉自動跟單
curl -X POST http://localhost:8080/api/dashboard/auto-trade-status \
  -H "Content-Type: application/json" \
  -d '{"enabled": false}'

# 4. 驗證狀態已改變
curl http://localhost:8080/api/dashboard/auto-trade-status
```

### 場景 2：驗證廣播跟單檢驗

```bash
# 1. 建立 2 個用戶
# User1: autoTradeEnabled = true
# User2: autoTradeEnabled = false

# 2. 發送廣播訊號
curl -X POST http://localhost:8080/api/broadcast-trade \
  -H "Content-Type: application/json" \
  -d '{
    "action": "ENTRY",
    "symbol": "BTCUSDT",
    "side": "LONG",
    "entryPrice": 95000,
    "stopLoss": 94000
  }'

# 3. 驗證結果
# User1 ✅ 執行跟單
# User2 ❌ 跳過（已關閉自動跟單）
```

### 場景 3：驗證 Overview 包含 autoTradeEnabled

```bash
curl http://localhost:8080/api/dashboard/overview

# 回應應包含
{
  "autoTradeEnabled": true,
  // ... 其他欄位 ...
}
```

---

## 故障排除

### 廣播跟單沒有執行

**檢查清單：**
1. 用戶的 `autoTradeEnabled = true`？
2. 用戶的 `enabled = true`（帳戶啟用）？
3. 廣播訊號本身是否正確？（檢查白名單、風控等）

### 開關保存不了

**檢查清單：**
1. 用戶登入狀態是否有效？
2. 後端是否有錯誤日誌？查看 `log.info("用戶 {} 自動跟單設定已更新")`
3. 數據庫是否連線正常？

### Overview 不顯示 autoTradeEnabled

**檢查清單：**
1. DashboardService 是否注入了 UserRepository？
2. getOverview() 方法中是否有設定 autoTradeEnabled？
3. 前端是否期待這個欄位？

---

## 未來改進

- [ ] 自動跟單規則（例如只在特定時間啟用）
- [ ] 按交易對篩選（例如只自動跟單 BTC）
- [ ] 風險等級限制（例如只接收低風險訊號）
- [ ] 自動跟單日誌（查看哪些訊號被執行）
- [ ] 與訂閱計劃綁定（例如 Pro 版本才能使用）
