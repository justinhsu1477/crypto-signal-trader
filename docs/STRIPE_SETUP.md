# Stripe 訂閱設定指南

本文件說明如何在 Stripe Dashboard 設定訂閱計費，並與 HookFi 後端整合。

---

## 1. 建立 Stripe 帳號

1. 前往 [Stripe Dashboard](https://dashboard.stripe.com/) 註冊或登入
2. 完成帳戶驗證（測試模式可跳過）
3. **記下 API Keys**（在 Developers → API keys）：
   - `Secret Key`（以 `sk_test_` 或 `sk_live_` 開頭）

---

## 2. 建立 Products 和 Prices

在 Stripe Dashboard → **Products** → **Add Product**：

### Basic 方案

| 欄位 | 值 |
|------|-----|
| Product name | HookFi Basic |
| Pricing model | Recurring |
| Price | $9.99 / month |
| Currency | USD |

建立後記下 **Price ID**（格式 `price_xxx`）。

### Pro 方案

| 欄位 | 值 |
|------|-----|
| Product name | HookFi Pro |
| Pricing model | Recurring |
| Price | $29.99 / month |
| Currency | USD |

建立後記下 **Price ID**。

---

## 3. 建立 Payment Links

在每個 Product 頁面：

1. 點擊 **Create payment link**
2. 設定選項：
   - **After payment** → Don't show confirmation page（或自訂成功頁 URL）
   - **Allow promotion codes** → 視需求開啟
3. 點擊 **Create link**
4. 記下 Payment Link URL（格式 `https://buy.stripe.com/xxx`）

> 每個方案各需一個 Payment Link。

---

## 4. 設定 Webhook

在 Stripe Dashboard → **Developers** → **Webhooks** → **Add endpoint**：

| 欄位 | 值 |
|------|-----|
| Endpoint URL | `https://your-domain.com/api/subscription/webhook` |
| Listen to | Events on your account |

### 選擇監聽的事件（5 個）

- `checkout.session.completed`
- `invoice.payment_succeeded`
- `invoice.payment_failed`
- `customer.subscription.deleted`
- `customer.subscription.updated`

建立後記下 **Webhook Signing Secret**（格式 `whsec_xxx`）。

---

## 5. 設定環境變數

在 `.env.prod`（或 `.env.dev`）加入：

```env
# Stripe
STRIPE_SECRET_KEY=sk_test_xxx          # 或 sk_live_xxx
STRIPE_WEBHOOK_SECRET=whsec_xxx
```

對應 `application.yml`：
```yaml
stripe:
  secret-key: ${STRIPE_SECRET_KEY:sk_test_placeholder}
  webhook-secret: ${STRIPE_WEBHOOK_SECRET:whsec_placeholder}
```

---

## 6. 更新資料庫 Plans 表

啟動後端後（V6 migration 會自動新增欄位），手動更新 Stripe 相關欄位：

```sql
-- Basic 方案
UPDATE plans
SET stripe_price_id = 'price_xxxxx',
    stripe_payment_link_url = 'https://buy.stripe.com/xxxxx'
WHERE plan_id = 'basic';

-- Pro 方案
UPDATE plans
SET stripe_price_id = 'price_yyyyy',
    stripe_payment_link_url = 'https://buy.stripe.com/yyyyy'
WHERE plan_id = 'pro';
```

> Free 方案不需要設定 Stripe 欄位。

---

## 7. 本地開發測試

### 使用 Stripe CLI 轉發 Webhook

```bash
# 安裝 Stripe CLI
brew install stripe/stripe-cli/stripe

# 登入
stripe login

# 轉發 webhook 到本地
stripe listen --forward-to localhost:8080/api/subscription/webhook
```

Stripe CLI 會顯示一個臨時的 `whsec_xxx`，將此值設為 `STRIPE_WEBHOOK_SECRET`。

### 觸發測試事件

```bash
stripe trigger checkout.session.completed
stripe trigger invoice.payment_succeeded
stripe trigger customer.subscription.deleted
```

---

## 8. 付款流程說明

```
用戶點擊「訂閱」按鈕
    ↓
開新分頁到 Stripe Payment Link
（URL 包含 ?client_reference_id=userId）
    ↓
用戶在 Stripe 頁面完成付款
    ↓
Stripe 發送 checkout.session.completed Webhook
    ↓
後端收到 Webhook → 驗證簽名 → 建立 Subscription 紀錄
    ↓
用戶返回 Dashboard → 看到訂閱生效
```

---

## 9. API 端點一覽

| Method | Path | Auth | 說明 |
|--------|------|------|------|
| GET | `/api/subscription/plans` | JWT | 查詢可用方案 |
| POST | `/api/subscription/checkout` | JWT | 取得 Payment Link URL |
| GET | `/api/subscription/status` | JWT | 查詢訂閱狀態 |
| POST | `/api/subscription/cancel` | JWT | 立即取消訂閱 |
| POST | `/api/subscription/upgrade` | JWT | 升級/降級方案 |
| POST | `/api/subscription/webhook` | Public | Stripe Webhook 回調 |

---

## Checklist

- [ ] Stripe 帳號已建立
- [ ] Basic / Pro Products + Prices 已建立
- [ ] Payment Links 已建立（每個方案一個）
- [ ] Webhook endpoint 已設定（5 個事件）
- [ ] `.env` 已設定 `STRIPE_SECRET_KEY` 和 `STRIPE_WEBHOOK_SECRET`
- [ ] Plans 表已 UPDATE stripe_price_id 和 stripe_payment_link_url
- [ ] 本地 Stripe CLI 測試通過
