# Bitget API 整合指南（對應目前系統）

最後更新：2026-03-08（Asia/Taipei）  
目標：把 Bitget 加進現有 `BINANCE + BYBIT` 架構，且不破壞已上線流程與去重機制。

## 1. 官方規格重點（只列整合必需）

- 私有 REST 簽名：`ACCESS-KEY`、`ACCESS-SIGN`、`ACCESS-TIMESTAMP`、`ACCESS-PASSPHRASE`。  
  `ACCESS-SIGN` 為 `Base64(HMAC_SHA256(preHash, secret))`，`preHash` 由 timestamp + method + requestPath + query/body 組成。
- 私有 WebSocket：連線後要先 `op=login`，登入參數同樣需要 `apiKey + passphrase + sign + timestamp`。
- 合約下單主路徑：`POST /api/v2/mix/order/place-order`。
- 查可用餘額：`GET /api/v2/mix/account/accounts`。
- 查持倉：`GET /api/v2/mix/position/all-position`。
- 查標記價：`GET /api/v2/mix/market/symbol-price`。
- 槓桿/保證金模式：`POST /api/v2/mix/account/set-leverage`、`POST /api/v2/mix/account/set-margin-mode`。
- 訂單查詢與取消：`GET /api/v2/mix/order/orders-pending`、`POST /api/v2/mix/order/cancel-all-orders`。
- 計畫單（SL/TP）查詢與取消：`GET /api/v2/mix/order/orders-plan-pending`、`POST /api/v2/mix/order/cancel-plan-order`。
- 合約精度資訊：`GET /api/v2/mix/market/contracts`（價格/數量小數位與最小下單單位）。

## 2. 和你現在程式架構的直接對應

### 2.1 `ExchangeAdapter` 方法對照

| 你現有介面方法 | Bitget 端點 | 實作備註 |
|---|---|---|
| `getAvailableBalance()` | `GET /api/v2/mix/account/accounts` | 依 `marginCoin=USDT` 取可用餘額欄位 |
| `getCurrentPositionAmount()` / `getAllPositionAmounts()` | `GET /api/v2/mix/position/all-position` | 依 `holdSide` 轉成 signed amount（long 正、short 負） |
| `getMarkPrice()` | `GET /api/v2/mix/market/symbol-price` | 用 `markPrice` |
| `placeLimitOrder()` / `placeMarketOrder()` | `POST /api/v2/mix/order/place-order` | `orderType=limit/market`，`side=buy/sell` |
| `setStopLoss()` / `setTakeProfit()` | `POST /api/v2/mix/order/place-tpsl-order` | 建議 `planType=pos_loss/pos_profit`（整倉保護） |
| `cancelAllOrders()` | `POST /api/v2/mix/order/cancel-all-orders` | 同 symbol 取消所有掛單 |
| `hasOpenEntryOrders()` | `GET /api/v2/mix/order/orders-pending` | 過濾 `status in live/partially_filled` |
| `cancelSLTPOrders()` / `getCurrentSLTPPrices()` | `GET orders-plan-pending` + `POST cancel-plan-order` | 只處理 `pos_loss/pos_profit` |
| `setLeverage()` | `POST /api/v2/mix/account/set-leverage` | 注意 one-way / hedge 參數差異 |
| `setMarginType()` | `POST /api/v2/mix/account/set-margin-mode` | 有持倉/掛單時可能被拒，維持你現有 try-catch 策略 |
| `formatPrice()` / `formatQuantity()` | `GET /api/v2/mix/market/contracts` | 用 `pricePlace`、`volumePlace`、`sizeMultiplier` |

### 2.2 WebSocket 對照（你目前 `MultiUserDataStreamManager` 架構）

- 新增 `BitgetStreamProvider implements ExchangeStreamProvider`。
- `connect()`：連線 Bitget private WS，`onOpen` 先送 `login` 再 `subscribe`。
- 訂閱通道：`orders`、`positions`（合約私有頻道）。
- 在 `PerUserWebSocketListener` 加 `BITGET` 分支，把事件轉給 `OrderEventHandler` 新方法（例如 `handleBitgetOrder`, `handleBitgetPosition`）。
- 延用你現有 ThreadLocal userId 流程，保持多用戶隔離。

## 3. 第一個必做：資料模型補 `passphrase`

這是目前最大阻礙。  
你現在只有 `apiKey + secretKey`，但 Bitget REST/WS 私有接口都要求 passphrase。

### 需要修改

- `user_api_keys` 新增 `encrypted_passphrase` 欄位（migration）。
- `UserApiKey` entity 加欄位。
- `SaveApiKeyRequest` / `UserService.saveApiKey(...)` / `UserApiKeyService.ExchangeKeys` / `ExchangeCredentials` 都要擴成可攜帶 passphrase。
- 驗證規則：
  - `exchange == BITGET`：passphrase 必填。
  - `exchange != BITGET`：passphrase 可為空（不影響 Binance/Bybit）。

## 4. 建議實作順序（上線系統安全版）

1. 先做資料層與 API 相容（含 migration + DTO + encryption/decryption + 單元測試）。  
2. 新增 `BitgetAdapter`，先打通只讀接口（餘額/持倉/標記價/合約資訊）。  
3. 打通下單與取消（place/cancel/cancel-all + plan order）。  
4. 新增 `BitgetStreamProvider` + 事件解析，讓成交/平倉能回寫 DB。  
5. `ExchangeAdapterFactoryImpl` 註冊 BITGET。  
6. 跑整套回歸：既有 Binance/Bybit 測試 + Bitget 新測試 + 重複訊號測試。  

## 5. 你這套系統要特別注意的風險

- `PENDING_CLOSE` 切所風險：建議改成切換交易所時同時檢查 `OPEN` 與 `PENDING_CLOSE`，避免舊交易所倉位收斂被新交易所資料污染。
- one-way / hedge 模式差異：Bitget 下單欄位 `tradeSide`、`reduceOnly`、`holdSide` 在不同模式規則不同，要在 adapter 統一轉換。
- 時間戳偏移：Bitget 私有簽名對時間敏感，建議加 server-time 校正或至少失敗重試策略。
- 速率限制：下單/查詢/計畫單的限流不同，建議比照你目前 `BybitApiRateLimiter` 再做 `BitgetApiRateLimiter`。

## 6. 本 repo 的預期改動檔案清單

### 新增

- `src/main/java/com/trader/shared/config/BitgetConfig.java`
- `src/main/java/com/trader/shared/util/BitgetSignatureUtil.java`
- `src/main/java/com/trader/shared/util/BitgetApiRateLimiter.java`
- `src/main/java/com/trader/trading/exchange/bitget/BitgetAdapter.java`
- `src/main/java/com/trader/trading/service/BitgetStreamProvider.java`
- `src/main/resources/db/migration/V32__add_bitget_passphrase.sql`（版本號可依你當前遞增）
- `src/test/java/com/trader/trading/exchange/bitget/BitgetAdapterTest.java`
- `src/test/java/com/trader/trading/service/BitgetStreamProviderTest.java`

### 修改

- `src/main/java/com/trader/user/entity/UserApiKey.java`
- `src/main/java/com/trader/user/dto/SaveApiKeyRequest.java`
- `src/main/java/com/trader/user/service/UserService.java`
- `src/main/java/com/trader/user/service/UserApiKeyService.java`
- `src/main/java/com/trader/trading/exchange/ExchangeCredentials.java`
- `src/main/java/com/trader/trading/exchange/ExchangeAdapterFactoryImpl.java`
- `src/main/java/com/trader/trading/service/MultiUserDataStreamManager.java`
- `src/main/java/com/trader/trading/service/OrderEventHandler.java`
- `src/main/resources/application.yml`（新增 `bitget.*` 設定）

## 7. 官方文件來源（本次整理使用）

- [Bitget Common - Signature](https://www.bitget.com/api-doc/common/signature)
- [Bitget Common - WebSocket Intro](https://www.bitget.com/api-doc/common/websocket-intro)
- [Bitget Contract - Place Order](https://www.bitget.com/api-doc/contract/trade/Place-Order)
- [Bitget Contract - Cancel All Orders](https://www.bitget.com/api-doc/contract/trade/Cancel-All-Orders)
- [Bitget Contract - Get Orders Pending](https://www.bitget.com/api-doc/contract/trade/Get-Orders-Pending)
- [Bitget Contract - Get Account List](https://www.bitget.com/api-doc/contract/account/Get-Account-List)
- [Bitget Contract - Get All Position](https://www.bitget.com/api-doc/contract/position/get-all-position)
- [Bitget Contract - Get Symbol Price](https://www.bitget.com/api-doc/contract/market/Get-Symbol-Price)
- [Bitget Contract - Change Leverage](https://www.bitget.com/api-doc/contract/account/Change-Leverage)
- [Bitget Contract - Change Margin Mode](https://www.bitget.com/api-doc/contract/account/Change-Margin-Mode)
- [Bitget Contract - Get Contract Config](https://www.bitget.com/api-doc/contract/market/Get-Contract-Config)
- [Bitget Contract - Place TPSL Order](https://www.bitget.com/api-doc/contract/plan/Place-Tpsl-Order)
- [Bitget Contract - Cancel Plan Order](https://www.bitget.com/api-doc/contract/plan/Cancel-Plan-Order)
- [Bitget Contract - Get Pending Trigger Order](https://www.bitget.com/api-doc/contract/plan/Get-Pending-Trigger-Order)
- [Bitget Contract WS - Order Channel](https://www.bitget.com/api-doc/contract/websocket/private/Order-Channel)
- [Bitget Contract WS - Positions Channel](https://www.bitget.com/api-doc/contract/websocket/private/Positions-Channel)
- [Bitget API Domain](https://www.bitget.com/api-doc/common/api-domain)
