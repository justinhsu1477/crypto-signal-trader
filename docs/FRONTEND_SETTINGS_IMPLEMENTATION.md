# 前端設定頁面實現指南

本文檔指導前端團隊如何在 Dashboard Settings 頁面中實現「自動跟單開關」和「Discord Webhook 管理」功能。

## 📋 目錄

1. [自動跟單開關](#自動跟單開關)
2. [Discord Webhook 管理](#discord-webhook-管理)
3. [API 接口總結](#api-接口總結)
4. [實現範例](#實現範例)

---

## 自動跟單開關

### 功能說明
- 用戶可在 Settings 頁面啟用/關閉「自動跟單」
- 開關狀態實時保存到 Server
- Overview 頁面同時顯示當前開關狀態

### UI/UX 設計

**位置：** Settings 頁面 → 「跟單設定」區段（在 API Key Management 後面）

**組件結構：**
```
┌─────────────────────────────────────────┐
│ 🤖 自動跟單設定                          │
├─────────────────────────────────────────┤
│                                         │
│ 自動跟單                  [開關按鈕]    │
│ 當啟用時，您的帳戶將自動接收            │
│ 廣播跟單訊號                             │
│                                         │
│ ℹ️ 提示：關閉此開關後，廣播訊號        │
│    將不會對您的帳戶執行交易              │
│                                         │
└─────────────────────────────────────────┘
```

### 實現步驟

#### 1. 在 `types/index.ts` 加上型別定義

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

#### 2. 在 `lib/api.ts` 加上 API 函數

```typescript
// 查詢自動跟單狀態
export async function getAutoTradeStatus(): Promise<AutoTradeStatus> {
  const response = await fetch('/api/dashboard/auto-trade-status', {
    method: 'GET',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
  });
  if (!response.ok) throw new Error('Failed to fetch auto trade status');
  return response.json();
}

// 更新自動跟單狀態
export async function updateAutoTradeStatus(
  enabled: boolean
): Promise<AutoTradeUpdateResponse> {
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

#### 3. 在 `app/settings/page.tsx` 加上開關邏輯

在現有的 `SettingsPage` 組件中加上：

```typescript
import type { AutoTradeStatus } from "@/types";
import { getAutoTradeStatus, updateAutoTradeStatus } from "@/lib/api";
import { Switch } from "@/components/ui/switch"; // 假設有 Switch 組件

export default function SettingsPage() {
  const { t } = useT();

  // ... 現有 state ...

  // Auto Trade state
  const [autoTradeStatus, setAutoTradeStatus] = useState<AutoTradeStatus | null>(null);
  const [autoTradeLoading, setAutoTradeLoading] = useState(true);
  const [autoTradeError, setAutoTradeError] = useState<string | null>(null);
  const [autoTradeUpdating, setAutoTradeUpdating] = useState(false);

  // Fetch auto trade status
  useEffect(() => {
    let cancelled = false;

    async function fetchAutoTradeStatus() {
      setAutoTradeLoading(true);
      setAutoTradeError(null);
      try {
        const data = await getAutoTradeStatus();
        if (!cancelled) setAutoTradeStatus(data);
      } catch (err) {
        if (!cancelled) {
          setAutoTradeError(err instanceof Error ? err.message : t("common.loadFailed"));
        }
      } finally {
        if (!cancelled) setAutoTradeLoading(false);
      }
    }

    fetchAutoTradeStatus();
    return () => { cancelled = true; };
  }, []);

  // Handle toggle auto trade
  async function handleToggleAutoTrade(enabled: boolean) {
    setAutoTradeUpdating(true);
    try {
      const result = await updateAutoTradeStatus(enabled);
      setAutoTradeStatus(result);
      setSaveMessage({
        type: "success",
        text: result.message,
      });
    } catch (err) {
      setSaveMessage({
        type: "error",
        text: err instanceof Error ? err.message : t("common.saveFailed"),
      });
      // Revert the toggle
      setAutoTradeStatus(prev => prev ? { ...prev, autoTradeEnabled: !enabled } : null);
    } finally {
      setAutoTradeUpdating(false);
    }
  }

  return (
    <div className="space-y-6">
      {/* ... 現有內容 ... */}

      {/* Section 3: Auto Trade Settings */}
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            🤖 {t("settings.autoTrade")}
          </CardTitle>
        </CardHeader>
        <CardContent className="space-y-6">
          {autoTradeLoading && (
            <div className="flex items-center justify-center py-8">
              <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
            </div>
          )}

          {autoTradeError && (
            <div className="text-center py-6 text-red-500">{autoTradeError}</div>
          )}

          {!autoTradeLoading && !autoTradeError && autoTradeStatus && (
            <div className="space-y-4">
              <div className="flex items-center justify-between p-4 border rounded-lg bg-muted/30">
                <div className="space-y-1">
                  <Label className="text-base font-medium">
                    {t("settings.autoTradeLabel")}
                  </Label>
                  <p className="text-sm text-muted-foreground">
                    {t("settings.autoTradeDescription")}
                  </p>
                </div>
                <Switch
                  checked={autoTradeStatus.autoTradeEnabled}
                  onCheckedChange={handleToggleAutoTrade}
                  disabled={autoTradeUpdating}
                />
              </div>

              {autoTradeStatus.autoTradeEnabled ? (
                <div className="p-3 bg-emerald-50 border border-emerald-200 rounded-lg text-sm text-emerald-700">
                  ✓ {t("settings.autoTradeEnabled")}
                </div>
              ) : (
                <div className="p-3 bg-yellow-50 border border-yellow-200 rounded-lg text-sm text-yellow-700">
                  ⚠️ {t("settings.autoTradeDisabled")}
                </div>
              )}
            </div>
          )}
        </CardContent>
      </Card>

      {/* ... 更多區段 ... */}
    </div>
  );
}
```

---

## Discord Webhook 管理

### 功能說明
- 用戶可添加自定義的 Discord Webhook URL
- 同時支持多個 webhook（自動停用舊的）
- 可啟用/停用/刪除 webhook
- 交易通知優先發送到用戶自定義 webhook

### UI/UX 設計

**位置：** Settings 頁面 → 「通知設定」區段（在自動跟單設定後面）

**組件結構：**
```
┌──────────────────────────────────────────┐
│ 🔔 Discord 通知設定                      │
├──────────────────────────────────────────┤
│                                          │
│ 現有 Webhook：                            │
│ ┌──────────────────────────────────────┐ │
│ │ 📌 我的交易通知                        │ │
│ │ https://discord.com/api/webhooks/... │ │
│ │ 啟用中  [停用]  [刪除]                │ │
│ └──────────────────────────────────────┘ │
│                                          │
│ ─────────────────────────────────────── │
│                                          │
│ 新增 Webhook：                            │
│                                          │
│ Webhook 名稱：[________]                 │
│ Webhook URL： [_________________]       │
│              [新增]                      │
│                                          │
│ ℹ️ 提示：                                │
│ • Discord Webhook URL 取得方式：        │
│   1. 在 Discord 伺服器建立文字頻道      │
│   2. 編輯頻道 → 整合 → Webhook          │
│   3. 新建 Webhook，複製 URL              │
│                                          │
└──────────────────────────────────────────┘
```

### 實現步驟

#### 1. 在 `types/index.ts` 加上型別定義

```typescript
export interface UserDiscordWebhook {
  webhookId: string;
  userId: string;
  webhookUrl: string;
  name: string;
  enabled: boolean;
  createdAt?: string;
  updatedAt?: string;
}

export interface WebhooksResponse {
  userId: string;
  webhooks: UserDiscordWebhook[];
  primaryWebhookId: string | null;
}

export interface CreateWebhookRequest {
  webhookUrl: string;
  name?: string;
}

export interface CreateWebhookResponse {
  webhookId: string;
  userId: string;
  name: string;
  enabled: boolean;
  message: string;
}
```

#### 2. 在 `lib/api.ts` 加上 API 函數

```typescript
// 取得用戶所有 webhook
export async function getDiscordWebhooks(): Promise<WebhooksResponse> {
  const response = await fetch('/api/dashboard/discord-webhooks', {
    method: 'GET',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
  });
  if (!response.ok) throw new Error('Failed to fetch webhooks');
  return response.json();
}

// 建立新 webhook
export async function createDiscordWebhook(
  request: CreateWebhookRequest
): Promise<CreateWebhookResponse> {
  const response = await fetch('/api/dashboard/discord-webhooks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
    body: JSON.stringify(request),
  });
  if (!response.ok) {
    const error = await response.json();
    throw new Error(error.error || 'Failed to create webhook');
  }
  return response.json();
}

// 停用 webhook
export async function disableDiscordWebhook(webhookId: string) {
  const response = await fetch(`/api/dashboard/discord-webhooks/${webhookId}/disable`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
  });
  if (!response.ok) throw new Error('Failed to disable webhook');
  return response.json();
}

// 刪除 webhook
export async function deleteDiscordWebhook(webhookId: string) {
  const response = await fetch(`/api/dashboard/discord-webhooks/${webhookId}`, {
    method: 'DELETE',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
  });
  if (!response.ok) throw new Error('Failed to delete webhook');
  return response.json();
}
```

#### 3. 建立 Discord Webhook 管理組件

建立新檔案：`src/components/settings/discord-webhook-manager.tsx`

```typescript
'use client';

import { useEffect, useState } from 'react';
import type { UserDiscordWebhook, WebhooksResponse } from '@/types';
import {
  getDiscordWebhooks,
  createDiscordWebhook,
  disableDiscordWebhook,
  deleteDiscordWebhook,
} from '@/lib/api';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { useT } from '@/lib/i18n/i18n-context';

export function DiscordWebhookManager() {
  const { t } = useT();

  // State
  const [webhooks, setWebhooks] = useState<UserDiscordWebhook[]>([]);
  const [primaryId, setPrimaryId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Form state
  const [webhookName, setWebhookName] = useState('');
  const [webhookUrl, setWebhookUrl] = useState('');
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  // Fetch webhooks
  useEffect(() => {
    let cancelled = false;

    async function fetch() {
      setLoading(true);
      setError(null);
      try {
        const data = await getDiscordWebhooks();
        if (!cancelled) {
          setWebhooks(data.webhooks);
          setPrimaryId(data.primaryWebhookId);
        }
      } catch (err) {
        if (!cancelled) setError(err instanceof Error ? err.message : t('common.loadFailed'));
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    fetch();
    return () => { cancelled = true; };
  }, []);

  // Handle create webhook
  async function handleCreateWebhook() {
    if (!webhookUrl.trim()) {
      setMessage({ type: 'error', text: t('settings.webhookUrlRequired') });
      return;
    }

    if (!webhookUrl.startsWith('https://discord.com/api/webhooks/')) {
      setMessage({ type: 'error', text: t('settings.invalidWebhookUrl') });
      return;
    }

    setSaving(true);
    setMessage(null);
    try {
      const result = await createDiscordWebhook({
        webhookUrl: webhookUrl.trim(),
        name: webhookName.trim() || 'Discord Webhook',
      });

      setMessage({ type: 'success', text: result.message });
      setWebhookName('');
      setWebhookUrl('');

      // Refresh webhooks
      const data = await getDiscordWebhooks();
      setWebhooks(data.webhooks);
      setPrimaryId(data.primaryWebhookId);
    } catch (err) {
      setMessage({
        type: 'error',
        text: err instanceof Error ? err.message : t('common.saveFailed'),
      });
    } finally {
      setSaving(false);
    }
  }

  // Handle disable
  async function handleDisable(webhookId: string) {
    try {
      await disableDiscordWebhook(webhookId);
      const data = await getDiscordWebhooks();
      setWebhooks(data.webhooks);
      setPrimaryId(data.primaryWebhookId);
    } catch (err) {
      setMessage({
        type: 'error',
        text: err instanceof Error ? err.message : t('common.error'),
      });
    }
  }

  // Handle delete
  async function handleDelete(webhookId: string) {
    if (window.confirm(t('settings.confirmDelete'))) {
      try {
        await deleteDiscordWebhook(webhookId);
        const data = await getDiscordWebhooks();
        setWebhooks(data.webhooks);
        setPrimaryId(data.primaryWebhookId);
      } catch (err) {
        setMessage({
          type: 'error',
          text: err instanceof Error ? err.message : t('common.error'),
        });
      }
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          🔔 {t('settings.discordNotification')}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-6">
        {/* Current webhooks */}
        {loading && (
          <div className="flex items-center justify-center py-8">
            <div className="animate-spin rounded-full h-8 w-8 border-b-2 border-primary" />
          </div>
        )}

        {error && <div className="text-center py-6 text-red-500">{error}</div>}

        {!loading && !error && (
          <>
            {webhooks.length > 0 && (
              <div className="space-y-3">
                <Label className="text-sm font-medium">{t('settings.currentWebhooks')}</Label>
                <div className="space-y-2">
                  {webhooks.map((webhook) => (
                    <div
                      key={webhook.webhookId}
                      className="flex items-center justify-between p-3 border rounded-lg"
                    >
                      <div className="flex-1 space-y-1">
                        <div className="flex items-center gap-2">
                          <span className="font-medium text-sm">📌 {webhook.name}</span>
                          {webhook.enabled && (
                            <Badge className="bg-emerald-500/15 text-emerald-500">
                              {t('settings.enabled')}
                            </Badge>
                          )}
                          {primaryId === webhook.webhookId && (
                            <Badge variant="outline">{t('settings.primary')}</Badge>
                          )}
                        </div>
                        <p className="text-xs font-mono text-muted-foreground break-all">
                          {webhook.webhookUrl.substring(0, 60)}...
                        </p>
                      </div>
                      <div className="flex gap-2">
                        {webhook.enabled && (
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => handleDisable(webhook.webhookId)}
                          >
                            {t('settings.disable')}
                          </Button>
                        )}
                        <Button
                          size="sm"
                          variant="destructive"
                          onClick={() => handleDelete(webhook.webhookId)}
                        >
                          {t('settings.delete')}
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            )}

            <div className="border-t pt-6 space-y-4">
              <Label className="text-sm font-medium">{t('settings.addNewWebhook')}</Label>

              <div className="space-y-3">
                <div className="space-y-2">
                  <Label htmlFor="webhookName" className="text-xs text-muted-foreground">
                    {t('settings.webhookName')}
                  </Label>
                  <Input
                    id="webhookName"
                    value={webhookName}
                    onChange={(e) => setWebhookName(e.target.value)}
                    placeholder={t('settings.webhookNamePlaceholder')}
                  />
                </div>

                <div className="space-y-2">
                  <Label htmlFor="webhookUrl" className="text-xs text-muted-foreground">
                    Webhook URL
                  </Label>
                  <Input
                    id="webhookUrl"
                    value={webhookUrl}
                    onChange={(e) => setWebhookUrl(e.target.value)}
                    placeholder="https://discord.com/api/webhooks/..."
                    type="url"
                  />
                </div>
              </div>

              {message && (
                <p
                  className={`text-sm ${
                    message.type === 'success' ? 'text-emerald-500' : 'text-red-500'
                  }`}
                >
                  {message.text}
                </p>
              )}

              <Button onClick={handleCreateWebhook} disabled={saving}>
                {saving ? t('common.saving') : t('settings.addWebhook')}
              </Button>

              {/* Help text */}
              <div className="p-3 bg-blue-50 border border-blue-200 rounded-lg text-sm text-blue-700 space-y-2">
                <p className="font-medium">ℹ️ {t('settings.howToGetWebhook')}</p>
                <ol className="list-decimal list-inside space-y-1">
                  <li>{t('settings.step1CreateChannel')}</li>
                  <li>{t('settings.step2EditChannel')}</li>
                  <li>{t('settings.step3CreateWebhook')}</li>
                  <li>{t('settings.step4CopyUrl')}</li>
                </ol>
              </div>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  );
}
```

#### 4. 在 `app/settings/page.tsx` 導入並使用組件

```typescript
import { DiscordWebhookManager } from '@/components/settings/discord-webhook-manager';

export default function SettingsPage() {
  // ... 現有代碼 ...

  return (
    <div className="space-y-6">
      {/* ... 現有區段 ... */}

      {/* Discord Webhook 管理 */}
      <DiscordWebhookManager />
    </div>
  );
}
```

---

## API 接口總結

### 自動跟單 API

| 方法 | 端點 | 說明 |
|------|------|------|
| GET | `/api/dashboard/auto-trade-status` | 查詢自動跟單狀態 |
| POST | `/api/dashboard/auto-trade-status` | 更新自動跟單狀態 |

**GET 回應：**
```json
{
  "userId": "user123",
  "autoTradeEnabled": true
}
```

**POST 請求：**
```json
{
  "enabled": true
}
```

**POST 回應：**
```json
{
  "userId": "user123",
  "autoTradeEnabled": true,
  "message": "已啟用自動跟單"
}
```

### Webhook 管理 API

| 方法 | 端點 | 說明 |
|------|------|------|
| GET | `/api/dashboard/discord-webhooks` | 查詢所有 webhook |
| POST | `/api/dashboard/discord-webhooks` | 建立新 webhook |
| POST | `/api/dashboard/discord-webhooks/{id}/disable` | 停用 webhook |
| DELETE | `/api/dashboard/discord-webhooks/{id}` | 刪除 webhook |

**GET 回應：**
```json
{
  "userId": "user123",
  "webhooks": [
    {
      "webhookId": "webhook1",
      "userId": "user123",
      "webhookUrl": "https://discord.com/api/webhooks/...",
      "name": "我的交易通知",
      "enabled": true,
      "createdAt": "2024-01-01T12:00:00Z",
      "updatedAt": "2024-01-01T12:00:00Z"
    }
  ],
  "primaryWebhookId": "webhook1"
}
```

**POST 請求：**
```json
{
  "webhookUrl": "https://discord.com/api/webhooks/...",
  "name": "我的交易通知"
}
```

**POST 回應：**
```json
{
  "webhookId": "webhook1",
  "userId": "user123",
  "name": "我的交易通知",
  "enabled": true,
  "message": "Webhook 已設定成功"
}
```

---

## 實現範例

### 完整的 Settings 頁面（簡化版）

```typescript
"use client";

import { useEffect, useState } from "react";
import type { UserProfile, AutoTradeStatus } from "@/types";
import { getUserProfile, getAutoTradeStatus, updateAutoTradeStatus } from "@/lib/api";
import { DiscordWebhookManager } from "@/components/settings/discord-webhook-manager";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Switch } from "@/components/ui/switch";
import { useT } from "@/lib/i18n/i18n-context";

export default function SettingsPage() {
  const { t } = useT();

  // Profile
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [profileLoading, setProfileLoading] = useState(true);

  // Auto Trade
  const [autoTradeStatus, setAutoTradeStatus] = useState<AutoTradeStatus | null>(null);
  const [autoTradeLoading, setAutoTradeLoading] = useState(true);
  const [autoTradeUpdating, setAutoTradeUpdating] = useState(false);

  // Fetch data
  useEffect(() => {
    const fetchProfile = async () => {
      try {
        const data = await getUserProfile();
        setProfile(data);
      } finally {
        setProfileLoading(false);
      }
    };
    fetchProfile();
  }, []);

  useEffect(() => {
    const fetchAutoTrade = async () => {
      try {
        const data = await getAutoTradeStatus();
        setAutoTradeStatus(data);
      } finally {
        setAutoTradeLoading(false);
      }
    };
    fetchAutoTrade();
  }, []);

  // Handle toggle
  const handleToggleAutoTrade = async (enabled: boolean) => {
    setAutoTradeUpdating(true);
    try {
      const result = await updateAutoTradeStatus(enabled);
      setAutoTradeStatus(result);
    } finally {
      setAutoTradeUpdating(false);
    }
  };

  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t("settings.title")}</h1>

      {/* Profile Card */}
      {!profileLoading && profile && (
        <Card>
          <CardHeader>
            <CardTitle>{t("settings.profile")}</CardTitle>
          </CardHeader>
          <CardContent>
            <p>Email: {profile.email}</p>
          </CardContent>
        </Card>
      )}

      {/* Auto Trade Card */}
      {!autoTradeLoading && autoTradeStatus && (
        <Card>
          <CardHeader>
            <CardTitle>🤖 {t("settings.autoTrade")}</CardTitle>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="flex items-center justify-between p-4 border rounded-lg">
              <div>
                <p className="font-medium">{t("settings.autoTradeLabel")}</p>
                <p className="text-sm text-muted-foreground">
                  {t("settings.autoTradeDescription")}
                </p>
              </div>
              <Switch
                checked={autoTradeStatus.autoTradeEnabled}
                onCheckedChange={handleToggleAutoTrade}
                disabled={autoTradeUpdating}
              />
            </div>
          </CardContent>
        </Card>
      )}

      {/* Webhook Manager */}
      <DiscordWebhookManager />
    </div>
  );
}
```

---

## 国际化（i18n）文本

在 `i18n/locales/zh-TW.json` 或 `en-US.json` 中加上：

```json
{
  "settings": {
    "title": "設定",
    "profile": "用戶資料",
    "autoTrade": "自動跟單",
    "autoTradeLabel": "自動跟單",
    "autoTradeDescription": "當啟用時，您的帳戶將自動接收廣播跟單訊號",
    "autoTradeEnabled": "✓ 已啟用自動跟單，您將接收廣播訊號",
    "autoTradeDisabled": "⚠️ 已關閉自動跟單，廣播訊號將不會對您執行交易",
    "discordNotification": "Discord 通知",
    "currentWebhooks": "現有 Webhook",
    "addNewWebhook": "新增 Webhook",
    "webhookName": "Webhook 名稱",
    "webhookNamePlaceholder": "例：我的交易通知",
    "webhookUrl": "Webhook URL",
    "webhookUrlRequired": "Webhook URL 不可為空",
    "invalidWebhookUrl": "無效的 Discord Webhook URL",
    "addWebhook": "新增",
    "disable": "停用",
    "delete": "刪除",
    "enabled": "啟用中",
    "primary": "主要",
    "confirmDelete": "確認刪除此 Webhook？",
    "howToGetWebhook": "如何取得 Webhook URL？",
    "step1CreateChannel": "在 Discord 伺服器建立文字頻道",
    "step2EditChannel": "編輯頻道 → 整合 → Webhook",
    "step3CreateWebhook": "新建 Webhook 並給予名稱",
    "step4CopyUrl": "複製 Webhook URL 並貼到上方"
  }
}
```

---

## 注意事項

1. **安全性**：
   - Webhook URL 在資料庫中以純文本存儲（應考慮加密）
   - 前端應在輸入時驗證 URL 格式
   - 只允許正式的 Discord Webhook URL

2. **UX 體驗**：
   - 新增 webhook 時自動停用舊的（避免混亂）
   - 提供明確的「主要 webhook」指示
   - 在表單中提供複製按鈕方便用戶操作

3. **錯誤處理**：
   - 網路錯誤時保持現有狀態
   - 提供清晰的錯誤提示信息
   - 支持重試機制

4. **性能優化**：
   - 避免重複請求（使用 React Query/SWR）
   - 在 Overview 頁面緩存狀態
   - Webhook 列表可分頁（未來考慮）

---

## 下一步

- [ ] 前端開發人員實現上述 UI/UX
- [ ] 整合到現有 Settings 頁面
- [ ] 測試所有端點
- [ ] 考慮添加 Webhook 測試功能（發送測試訊息）
- [ ] 考慮添加 Webhook 連線狀態指示
