import type {
  LoginRequest,
  LoginResponse,
  RegisterRequest,
  RegisterResponse,
  DashboardOverview,
  PerformanceStats,
  TradeHistoryResponse,
  TradeEvent,
  UserProfile,
  ApiKeyMetadata,
  SaveApiKeyRequest,
  MonitorStatus,
  StreamStatus,
  AutoTradeStatus,
  AutoTradeUpdateResponse,
  WebhooksResponse,
  CreateWebhookRequest,
  CreateWebhookResponse,
  UserTradeSettings,
  UpdateTradeSettingsRequest,
  TradeSettingsDefaults,
  PlanInfo,
  SubscriptionStatusDetail,
  CryptoCheckoutInfo,
  LineBindingStatus,
  LineLinkingCodeResponse,
  ReferralStatusResponse,
  SubmitUidRequest,
  VerifyEmailRequest,
  ResendCodeRequest,
  AdminSubscriptionListResponse,
  AdminPaymentHistoryResponse,
  AdminActivateRequest,
  AdminSubscriptionActionResponse,
} from "@/types";

const BASE = "";  // 使用 Next.js rewrites proxy

// 防止多個並行請求同時觸發 refresh
let refreshPromise: Promise<boolean> | null = null;

/**
 * 嘗試用 HttpOnly Cookie 中的 Refresh Token 取得新的 Access Token
 * 使用單例模式：多個並行 401 只觸發一次 refresh
 */
async function tryRefreshToken(): Promise<boolean> {
  try {
    const res = await fetch(`${BASE}/api/auth/refresh`, {
      method: "POST",
      credentials: "include",  // Cookie 自動帶上
      headers: { "Content-Type": "application/json" },
    });

    return res.ok;
  } catch {
    return false;
  }
}

function clearAuthAndRedirect(): void {
  if (typeof window !== "undefined") {
    localStorage.removeItem("userId");
    localStorage.removeItem("email");
    window.location.href = "/login";
  }
}

async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...((options.headers as Record<string, string>) || {}),
  };

  const res = await fetch(`${BASE}${url}`, {
    ...options,
    headers,
    credentials: "include",  // HttpOnly Cookie 自動帶上
  });

  if (res.status === 401) {
    // 嘗試 refresh token（單例模式，防止並行重複 refresh）
    if (!refreshPromise) {
      refreshPromise = tryRefreshToken().finally(() => {
        refreshPromise = null;
      });
    }
    const refreshed = await refreshPromise;

    if (refreshed) {
      // Refresh 成功 → 重試原始請求（Cookie 已自動更新）
      const retryRes = await fetch(`${BASE}${url}`, {
        ...options,
        headers,
        credentials: "include",
      });

      if (retryRes.ok) {
        return retryRes.json() as Promise<T>;
      }

      // 重試仍失敗 → 清除登入狀態
      if (retryRes.status === 401) {
        clearAuthAndRedirect();
        throw new Error("Unauthorized");
      }

      const body = await retryRes.text();
      throw new Error(body || `HTTP ${retryRes.status}`);
    }

    // Refresh 失敗 → 清除登入狀態，踢回登入頁
    clearAuthAndRedirect();
    throw new Error("Unauthorized");
  }

  if (res.status === 403) {
    const body = await res.text();
    let message = "Access denied";
    if (body) {
      try {
        const parsed = JSON.parse(body);
        message = parsed.error || parsed.message || parsed.detail || body;
        // REFERRAL_NOT_VERIFIED → redirect 到推薦碼頁面
        if (parsed.error === "REFERRAL_NOT_VERIFIED") {
          if (typeof window !== "undefined" && window.location.pathname !== "/referral") {
            window.location.href = "/referral";
          }
        }
      } catch {
        message = body;
      }
    }
    throw new Error(message);
  }

  if (!res.ok) {
    const body = await res.text();
    let message = `HTTP ${res.status}`;
    if (body) {
      try {
        const parsed = JSON.parse(body);
        message = parsed.error || parsed.message || parsed.detail || body;
      } catch {
        message = body;
      }
    }
    throw new Error(message);
  }

  return res.json() as Promise<T>;
}

// ==================== Auth ====================

/**
 * 公開 API 請求（不含 401 token refresh 邏輯）
 * 用於 login / register 等不需要 JWT 的端點。
 * 仍帶 credentials: "include" 以接收 Set-Cookie。
 */
async function publicRequest<T>(url: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...((options.headers as Record<string, string>) || {}),
  };

  const res = await fetch(`${BASE}${url}`, {
    ...options,
    headers,
    credentials: "include",  // 接收 Set-Cookie
  });

  if (!res.ok) {
    const body = await res.text();
    let message = `HTTP ${res.status}`;
    if (body) {
      try {
        const parsed = JSON.parse(body);
        message = parsed.error || parsed.message || parsed.detail || body;
      } catch {
        message = body;
      }
    }
    throw new Error(message);
  }

  return res.json() as Promise<T>;
}

export async function login(data: LoginRequest): Promise<LoginResponse> {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    credentials: "include",
    body: JSON.stringify(data),
  });

  if (!res.ok) {
    const body = await res.text();
    let parsed: Record<string, unknown> | null = null;

    if (body) {
      try {
        parsed = JSON.parse(body) as Record<string, unknown>;
      } catch {
        parsed = null;
      }
    }

    // 未驗證 Email：保留後端完整 JSON（包含 email）給 login page 做導向。
    if (res.status === 403 && parsed?.error === "EMAIL_NOT_VERIFIED") {
      throw new Error(body);
    }

    const rawMessage =
      parsed?.error ?? parsed?.message ?? parsed?.detail ?? body ?? `HTTP ${res.status}`;
    const message = typeof rawMessage === "string" ? rawMessage : `HTTP ${res.status}`;
    throw new Error(message);
  }

  return res.json() as Promise<LoginResponse>;
}

export async function register(data: RegisterRequest): Promise<RegisterResponse> {
  return publicRequest<RegisterResponse>("/api/auth/register", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function verifyEmail(data: VerifyEmailRequest): Promise<{ message: string }> {
  return publicRequest<{ message: string }>("/api/auth/verify-email", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function resendCode(data: ResendCodeRequest): Promise<{ message: string }> {
  return publicRequest<{ message: string }>("/api/auth/resend-code", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

/**
 * 取得當前登入用戶資訊（從 HttpOnly Cookie 認證）
 * 前端在頁面載入時呼叫，確認登入狀態。
 * 用 publicRequest 而非 request：未登入時不觸發 401 redirect，避免公開頁面 reload loop。
 */
export async function fetchCurrentUser(): Promise<{ userId: string; email: string; role: string }> {
  return publicRequest<{ userId: string; email: string; role: string }>("/api/auth/me");
}

// ==================== OAuth ====================

export interface OAuthCompleteResponse {
  userId: string;
  email: string;
  role: string;
  expiresIn: number;
}

export async function completeOAuthLogin(ticket: string): Promise<OAuthCompleteResponse> {
  return publicRequest<OAuthCompleteResponse>("/api/auth/oauth/complete", {
    method: "POST",
    body: JSON.stringify({ ticket }),
  });
}

// ==================== Password Management ====================

export async function changePassword(data: {
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}): Promise<{ message: string }> {
  return request<{ message: string }>("/api/auth/change-password", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function forgotPassword(data: {
  email: string;
}): Promise<{ message: string }> {
  return publicRequest<{ message: string }>("/api/auth/forgot-password", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function resetPassword(data: {
  token: string;
  newPassword: string;
  confirmPassword: string;
}): Promise<{ message: string }> {
  return publicRequest<{ message: string }>("/api/auth/reset-password", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

/**
 * 登出（清除 HttpOnly Cookie）
 */
export async function apiLogout(): Promise<void> {
  try {
    await fetch(`${BASE}/api/auth/logout`, {
      method: "POST",
      credentials: "include",
    });
  } catch {
    // 即使 API 失敗也要清除本地狀態
  }
}

// ==================== User ====================

export async function getUserProfile(): Promise<UserProfile> {
  return request<UserProfile>("/api/user/me");
}

export async function getApiKeys(): Promise<ApiKeyMetadata[]> {
  return request<ApiKeyMetadata[]>("/api/user/api-keys");
}

export async function saveApiKey(data: SaveApiKeyRequest): Promise<{ message: string }> {
  return request<{ message: string }>("/api/user/api-keys", {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

// ==================== Account Deletion ====================

export async function deleteAccount(): Promise<{ message: string }> {
  return request<{ message: string }>("/api/user/account", {
    method: "DELETE",
  });
}

// ==================== Dashboard ====================

export async function getDashboardOverview(): Promise<DashboardOverview> {
  return request<DashboardOverview>("/api/dashboard/overview");
}

export async function getPerformanceStats(days: number): Promise<PerformanceStats> {
  return request<PerformanceStats>(`/api/dashboard/performance?days=${days}`);
}

export async function getTradeHistory(page: number, size: number): Promise<TradeHistoryResponse> {
  return request<TradeHistoryResponse>(`/api/dashboard/trades?page=${page}&size=${size}`);
}

// ==================== Position Management ====================

export async function closePosition(symbol: string, side: string): Promise<{ status: string }> {
  return request<{ status: string }>("/api/execute-trade", {
    method: "POST",
    body: JSON.stringify({ action: "CLOSE", symbol, side }),
  });
}

export async function cancelOrders(symbol: string): Promise<{ status: string }> {
  return request<{ status: string }>("/api/execute-trade", {
    method: "POST",
    body: JSON.stringify({ action: "CANCEL", symbol }),
  });
}

// ==================== Trade Export ====================

export async function exportTrades(days: number = 30): Promise<void> {
  const res = await fetch(`${BASE}/api/dashboard/trades/export?days=${days}`, {
    credentials: "include",
  });

  if (res.status === 401) {
    if (!refreshPromise) {
      refreshPromise = tryRefreshToken().finally(() => {
        refreshPromise = null;
      });
    }
    const refreshed = await refreshPromise;
    if (refreshed) {
      const retryRes = await fetch(`${BASE}/api/dashboard/trades/export?days=${days}`, {
        credentials: "include",
      });
      if (retryRes.ok) {
        const blob = await retryRes.blob();
        downloadBlob(blob, retryRes);
        return;
      }
    }
    clearAuthAndRedirect();
    throw new Error("Unauthorized");
  }

  if (!res.ok) {
    throw new Error(`Export failed: HTTP ${res.status}`);
  }

  const blob = await res.blob();
  downloadBlob(blob, res);
}

function downloadBlob(blob: Blob, res: Response): void {
  const disposition = res.headers.get("Content-Disposition") || "";
  const match = disposition.match(/filename="?([^"]+)"?/);
  const filename = match?.[1] || `trades_${new Date().toISOString().slice(0, 10)}.csv`;

  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

// ==================== Trade Detail ====================

export async function getTradeEvents(tradeId: string): Promise<TradeEvent[]> {
  return request<TradeEvent[]>(`/api/trades/${tradeId}/events`);
}

// ==================== System Status ====================

export async function getMonitorStatus(): Promise<MonitorStatus> {
  return request<MonitorStatus>("/api/monitor-status");
}

export async function getStreamStatus(): Promise<StreamStatus> {
  return request<StreamStatus>("/api/stream-status");
}

// ==================== Public Status ====================

import type { PublicStatusResponse } from "@/types";

export async function getPublicSystemStatus(): Promise<PublicStatusResponse> {
  return publicRequest<PublicStatusResponse>("/api/status");
}

// ==================== Auto Trade ====================

export async function getAutoTradeStatus(): Promise<AutoTradeStatus> {
  return request<AutoTradeStatus>("/api/dashboard/auto-trade-status");
}

export async function updateAutoTradeStatus(
  enabled: boolean
): Promise<AutoTradeUpdateResponse> {
  return request<AutoTradeUpdateResponse>("/api/dashboard/auto-trade-status", {
    method: "POST",
    body: JSON.stringify({ enabled }),
  });
}

// ==================== Trade Settings ====================

export async function getTradeSettings(): Promise<UserTradeSettings> {
  return request<UserTradeSettings>("/api/dashboard/trade-settings");
}

export async function updateTradeSettings(
  data: UpdateTradeSettingsRequest
): Promise<UserTradeSettings> {
  return request<UserTradeSettings>("/api/dashboard/trade-settings", {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export async function getTradeSettingsDefaults(): Promise<TradeSettingsDefaults> {
  return request<TradeSettingsDefaults>("/api/dashboard/trade-settings/defaults");
}

// ==================== Discord Webhooks ====================

export async function getDiscordWebhooks(): Promise<WebhooksResponse> {
  return request<WebhooksResponse>("/api/dashboard/discord-webhooks");
}

export async function createDiscordWebhook(
  data: CreateWebhookRequest
): Promise<CreateWebhookResponse> {
  return request<CreateWebhookResponse>("/api/dashboard/discord-webhooks", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function disableDiscordWebhook(
  webhookId: string
): Promise<{ message: string }> {
  return request<{ message: string }>(
    `/api/dashboard/discord-webhooks/${webhookId}/disable`,
    { method: "POST" }
  );
}

export async function deleteDiscordWebhook(
  webhookId: string
): Promise<{ message: string }> {
  return request<{ message: string }>(
    `/api/dashboard/discord-webhooks/${webhookId}`,
    { method: "DELETE" }
  );
}

// ==================== LINE Binding ====================

export async function getLineBinding(): Promise<LineBindingStatus> {
  return request<LineBindingStatus>("/api/dashboard/line-binding");
}

export async function generateLineCode(): Promise<LineLinkingCodeResponse> {
  return request<LineLinkingCodeResponse>("/api/dashboard/line-binding/generate-code", {
    method: "POST",
  });
}

export async function unbindLine(): Promise<{ message: string }> {
  return request<{ message: string }>("/api/dashboard/line-binding", {
    method: "DELETE",
  });
}

export async function updateLineNotificationStatus(
  enabled: boolean
): Promise<{ lineNotificationEnabled: boolean; message: string }> {
  return request<{ lineNotificationEnabled: boolean; message: string }>(
    "/api/dashboard/line-notification-status",
    {
      method: "POST",
      body: JSON.stringify({ enabled }),
    }
  );
}

// ==================== Subscription ====================

export async function getSubscriptionPlans(): Promise<PlanInfo[]> {
  return request<PlanInfo[]>("/api/subscription/plans");
}

export async function getSubscriptionStatus(): Promise<SubscriptionStatusDetail> {
  return request<SubscriptionStatusDetail>("/api/subscription/status");
}

export async function cancelSubscription(): Promise<{ status: string; message: string }> {
  return request<{ status: string; message: string }>("/api/subscription/cancel", {
    method: "POST",
  });
}

export async function getCheckoutInfo(
  planId: string
): Promise<CryptoCheckoutInfo> {
  return request<CryptoCheckoutInfo>("/api/subscription/checkout", {
    method: "POST",
    body: JSON.stringify({ planId }),
  });
}

export async function submitPayment(
  data: { planId: string; txHash: string }
): Promise<{ status: string; message: string }> {
  return request<{ status: string; message: string }>("/api/subscription/submit-payment", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

// ==================== Referral ====================

export async function getReferralStatus(): Promise<ReferralStatusResponse> {
  return request<ReferralStatusResponse>("/api/referral/status");
}

export async function submitReferralUid(
  data: SubmitUidRequest
): Promise<ReferralStatusResponse> {
  return request<ReferralStatusResponse>("/api/referral/submit-uid", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function getReferralProgram(): Promise<ReferralStatusResponse> {
  return request<ReferralStatusResponse>("/api/referral/program");
}

// ==================== Admin ====================

import type {
  AdminSystemOverview,
  AdminUserListResponse,
  AdminUserSummary,
  AdminUpdateUserRequest,
  AdminPendingReferral,
  AdminVerifyRequest,
  SystemHealthResponse,
  StreamStatusResponse,
  DatabaseStatsResponse,
} from "@/types";

export async function getAdminSystemOverview(): Promise<AdminSystemOverview> {
  return request<AdminSystemOverview>("/api/admin/dashboard/system-overview");
}

export async function getAdminUsers(): Promise<AdminUserListResponse> {
  return request<AdminUserListResponse>("/api/admin/users");
}

import type { AdminUserDetailResponse } from "@/types";

export async function getAdminUserDetail(userId: string): Promise<AdminUserDetailResponse> {
  return request<AdminUserDetailResponse>(`/api/admin/users/${userId}`);
}

export async function updateAdminUser(
  userId: string,
  data: AdminUpdateUserRequest
): Promise<{ message: string; user: AdminUserSummary }> {
  return request<{ message: string; user: AdminUserSummary }>(
    `/api/admin/users/${userId}`,
    { method: "PUT", body: JSON.stringify(data) }
  );
}

export async function getAdminPendingReferrals(): Promise<AdminPendingReferral[]> {
  return request<AdminPendingReferral[]>("/api/admin/referral/pending");
}

export async function adminVerifyReferral(
  data: AdminVerifyRequest
): Promise<{ message: string; userId: string }> {
  return request<{ message: string; userId: string }>(
    "/api/admin/referral/verify",
    { method: "POST", body: JSON.stringify(data) }
  );
}

// ─── Admin Subscriptions ───

export async function getAdminSubscriptions(): Promise<AdminSubscriptionListResponse> {
  return request<AdminSubscriptionListResponse>("/api/admin/subscriptions");
}

export async function getAdminUserPayments(userId: string): Promise<AdminPaymentHistoryResponse> {
  return request<AdminPaymentHistoryResponse>(`/api/admin/subscriptions/${userId}/payments`);
}

export async function adminActivateSubscription(
  userId: string,
  data: AdminActivateRequest
): Promise<AdminSubscriptionActionResponse> {
  return request<AdminSubscriptionActionResponse>(
    `/api/admin/subscriptions/${userId}/activate`,
    { method: "POST", body: JSON.stringify(data) }
  );
}

export async function adminCancelSubscription(
  userId: string
): Promise<AdminSubscriptionActionResponse> {
  return request<AdminSubscriptionActionResponse>(
    `/api/admin/subscriptions/${userId}/cancel`,
    { method: "PUT" }
  );
}

export async function adminSetLifetime(
  userId: string
): Promise<AdminSubscriptionActionResponse> {
  return request<AdminSubscriptionActionResponse>(
    `/api/admin/subscriptions/${userId}/lifetime`,
    { method: "PUT" }
  );
}

// ─── Admin Health Check ───

export async function getSystemHealth(): Promise<SystemHealthResponse> {
  return request<SystemHealthResponse>("/api/health/deep");
}

export async function getAdminStreamStatus(): Promise<StreamStatusResponse> {
  return request<StreamStatusResponse>("/api/stream-status");
}

// ─── Admin Database Stats ───

export async function getAdminDatabaseStats(): Promise<DatabaseStatsResponse> {
  return request<DatabaseStatsResponse>("/api/admin/dashboard/database-stats");
}

// ─── Admin Metrics ───

import type { AdminMetricsResponse } from "@/types";

export async function getAdminMetrics(): Promise<AdminMetricsResponse> {
  return request<AdminMetricsResponse>("/api/admin/dashboard/metrics");
}

// ─── Admin Announcements ───

import type {
  AnnouncementResponse,
  CreateAnnouncementRequest,
  AnnouncementListResponse,
} from "@/types";

export async function getAdminAnnouncements(): Promise<AnnouncementResponse[]> {
  return request<AnnouncementResponse[]>("/api/admin/announcements");
}

export async function createAnnouncement(
  data: CreateAnnouncementRequest
): Promise<AnnouncementResponse> {
  return request<AnnouncementResponse>("/api/admin/announcements", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function updateAnnouncement(
  id: number,
  data: CreateAnnouncementRequest
): Promise<AnnouncementResponse> {
  return request<AnnouncementResponse>(`/api/admin/announcements/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export async function publishAnnouncement(
  id: number
): Promise<{ message: string; announcement: AnnouncementResponse }> {
  return request<{ message: string; announcement: AnnouncementResponse }>(
    `/api/admin/announcements/${id}/publish`,
    { method: "POST" }
  );
}

export async function archiveAnnouncement(
  id: number
): Promise<{ message: string }> {
  return request<{ message: string }>(
    `/api/admin/announcements/${id}/archive`,
    { method: "PUT" }
  );
}

export async function deleteAnnouncement(
  id: number
): Promise<{ message: string }> {
  return request<{ message: string }>(`/api/admin/announcements/${id}`, {
    method: "DELETE",
  });
}

// ─── User Announcements ───

export async function getAnnouncements(
  page: number = 0,
  size: number = 20
): Promise<AnnouncementListResponse> {
  return request<AnnouncementListResponse>(
    `/api/announcements?page=${page}&size=${size}`
  );
}

export async function markAnnouncementRead(id: number): Promise<void> {
  await request<{ message: string }>(`/api/announcements/${id}/read`, {
    method: "POST",
  });
}

export async function getUnreadAnnouncementCount(): Promise<{ count: number }> {
  return request<{ count: number }>("/api/announcements/unread-count");
}

// ─── Admin Monitor Settings ───

export interface MonitorChannelsResponse {
  channelIds: string[];
  guildIds: string[];
  authorIds: string[];
  ignoreKeywords: string[];
  configVersion: number;
  connectedMonitors: number;
  monitorOnline: boolean;
  lastHeartbeat: string | null;
}

export interface UpdateChannelsRequest {
  channelIds: string[];
  guildIds?: string[];
  authorIds?: string[];
  ignoreKeywords?: string[];
}

export async function getMonitorChannels(): Promise<MonitorChannelsResponse> {
  return request<MonitorChannelsResponse>("/api/admin/monitor/channels");
}

export async function updateMonitorChannels(
  data: UpdateChannelsRequest
): Promise<{ message: string; channelIds: string[]; configVersion: number; connectedMonitors: number }> {
  return request<{ message: string; channelIds: string[]; configVersion: number; connectedMonitors: number }>(
    "/api/admin/monitor/channels",
    {
      method: "PUT",
      body: JSON.stringify(data),
    }
  );
}

// ─── Admin Broadcast Trade (Emergency Signal) ───

export interface BroadcastTradeRequest {
  action: string;           // ENTRY | CLOSE | MOVE_SL | CANCEL
  symbol: string;           // BTCUSDT
  side?: string;            // LONG | SHORT (ENTRY 用)
  entry_price?: number;
  stop_loss?: number;
  take_profit?: number;
  close_ratio?: number;     // CLOSE 用 (0.5=50%, 1.0=全平, null=全平)
  new_stop_loss?: number;   // MOVE_SL / CLOSE 部分平倉後新 SL
  new_take_profit?: number;
  is_dca?: boolean;
  source?: { platform: string; author_name: string };
  target_user_ids?: string[];  // 可選，空 = 全部用戶
}

export interface BroadcastTradeResponse {
  status: string;
  totalUsers?: number;
  successCount?: number;
  failCount?: number;
  skippedNoSubscription?: number;
  skippedNoApiKey?: number;
  skippedNotTargeted?: number;
  message?: string;
  error?: string;
  reason?: string;
}

export async function adminBroadcastTrade(
  data: BroadcastTradeRequest
): Promise<BroadcastTradeResponse> {
  return request<BroadcastTradeResponse>("/api/broadcast-trade", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

// ─── Admin Broadcast Logs ───

import type { BroadcastLogListResponse, BroadcastLogDetail } from "@/types";

export async function getAdminBroadcastLogs(
  page = 0, size = 20, sourceAuthor?: string, startDate?: string, endDate?: string
): Promise<BroadcastLogListResponse> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (sourceAuthor) params.set("sourceAuthor", sourceAuthor);
  if (startDate) params.set("startDate", startDate);
  if (endDate) params.set("endDate", endDate);
  return request<BroadcastLogListResponse>(`/api/admin/dashboard/broadcast-logs?${params}`);
}

export async function getAdminBroadcastLogSources(): Promise<string[]> {
  return request<string[]>("/api/admin/dashboard/broadcast-logs/sources");
}

export async function getAdminBroadcastLogDetail(id: number): Promise<BroadcastLogDetail> {
  return request<BroadcastLogDetail>(`/api/admin/dashboard/broadcast-logs/${id}`);
}

// ─── Admin User Performance (Analytics) ───

export async function getAdminUserPerformance(userId: string, days: number): Promise<PerformanceStats> {
  return request<PerformanceStats>(`/api/admin/dashboard/users/${userId}/performance?days=${days}`);
}

// ─── Admin Funnel Stats (Insights) ───

import type { FunnelStatsResponse } from "@/types";

export async function getAdminFunnelStats(): Promise<FunnelStatsResponse> {
  return request<FunnelStatsResponse>("/api/admin/dashboard/funnel");
}

// ─── Admin User Balances ───

export async function getAdminUserBalances(): Promise<Record<string, number | null>> {
  return request<Record<string, number | null>>("/api/admin/dashboard/user-balances");
}

// ─── Admin Send Notification ───

import type { AdminSendNotificationRequest, AdminSendNotificationResponse } from "@/types";

export async function adminSendNotification(data: AdminSendNotificationRequest): Promise<AdminSendNotificationResponse> {
  return request<AdminSendNotificationResponse>("/api/admin/notifications/send", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data),
  });
}

// ─── Admin User API Keys ───

export async function adminSetApiKey(
  userId: string,
  data: { exchange: string; apiKey: string; secretKey: string }
): Promise<{ message: string; exchange: string; updatedAt: string }> {
  return request<{ message: string; exchange: string; updatedAt: string }>(
    `/api/admin/users/${userId}/api-keys`,
    { method: "PUT", body: JSON.stringify(data) }
  );
}

// ─── Admin User Trade Settings ───

export async function adminUpdateTradeSettings(
  userId: string,
  data: UpdateTradeSettingsRequest
): Promise<UserTradeSettings> {
  return request<UserTradeSettings>(
    `/api/admin/users/${userId}/trade-settings`,
    { method: "PUT", body: JSON.stringify(data) }
  );
}

// ─── Admin Signal Source Management ───

import type {
  SignalSourceResponse,
  CreateSignalSourceRequest,
  UpdateSignalSourceRequest,
  UserAssignmentResponse,
  SignalSourcePerformanceDto,
  SignalSourceUserResponse,
  MonitorStatusResponse,
} from "@/types";

export async function getAdminSignalSources(): Promise<SignalSourceResponse[]> {
  return request<SignalSourceResponse[]>("/api/admin/signal-sources");
}

export async function createAdminSignalSource(data: CreateSignalSourceRequest): Promise<SignalSourceResponse> {
  return request<SignalSourceResponse>("/api/admin/signal-sources", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function getAdminSignalSource(id: number): Promise<SignalSourceResponse> {
  return request<SignalSourceResponse>(`/api/admin/signal-sources/${id}`);
}

export async function updateAdminSignalSource(id: number, data: UpdateSignalSourceRequest): Promise<SignalSourceResponse> {
  return request<SignalSourceResponse>(`/api/admin/signal-sources/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export async function deleteAdminSignalSource(id: number): Promise<{ message: string }> {
  return request<{ message: string }>(`/api/admin/signal-sources/${id}`, {
    method: "DELETE",
  });
}

export async function getAdminSignalSourceUsers(id: number): Promise<UserAssignmentResponse[]> {
  return request<UserAssignmentResponse[]>(`/api/admin/signal-sources/${id}/users`);
}

export async function assignAdminSignalSourceUsers(id: number, userIds: string[]): Promise<UserAssignmentResponse[]> {
  return request<UserAssignmentResponse[]>(`/api/admin/signal-sources/${id}/users`, {
    method: "POST",
    body: JSON.stringify({ userIds }),
  });
}

export async function unassignAdminSignalSourceUser(sourceId: number, userId: string): Promise<{ message: string }> {
  return request<{ message: string }>(`/api/admin/signal-sources/${sourceId}/users/${userId}`, {
    method: "DELETE",
  });
}

export async function toggleAdminSignalSourceUser(sourceId: number, userId: string, enabled: boolean): Promise<{ message: string }> {
  return request<{ message: string }>(`/api/admin/signal-sources/${sourceId}/users/${userId}`, {
    method: "PUT",
    body: JSON.stringify({ enabled }),
  });
}

export async function getAdminSignalSourcePerformances(period = "all"): Promise<SignalSourcePerformanceDto[]> {
  return request<SignalSourcePerformanceDto[]>(`/api/admin/signal-sources/performance?period=${period}`);
}

export async function getAdminSignalSourcePerformance(id: number, period = "all"): Promise<SignalSourcePerformanceDto> {
  return request<SignalSourcePerformanceDto>(`/api/admin/signal-sources/${id}/performance?period=${period}`);
}

// ─── Shadow Graduation ───

import type { ShadowGraduationResult } from "@/types";

export async function getAdminShadowGraduation(): Promise<ShadowGraduationResult[]> {
  return request<ShadowGraduationResult[]>("/api/admin/shadow-graduation");
}

// ─── Prompt Version Management ───

export interface PromptVersion {
  id: number;
  version: number;
  content: string;
  description: string | null;
  active: boolean;
  tokenCount: number | null;
  createdAt: string;
  updatedAt: string;
}

export async function getAdminPromptVersions(): Promise<PromptVersion[]> {
  return request<PromptVersion[]>("/api/admin/prompts");
}

export async function createAdminPromptVersion(content: string, description: string): Promise<PromptVersion> {
  return request<PromptVersion>("/api/admin/prompts", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ content, description }),
  });
}

export async function activateAdminPromptVersion(id: number): Promise<PromptVersion> {
  return request<PromptVersion>(`/api/admin/prompts/${id}/activate`, { method: "POST" });
}

export async function getAdminActivePrompt(): Promise<PromptVersion | null> {
  try {
    return await request<PromptVersion>("/api/admin/prompts/active");
  } catch {
    return null;
  }
}

// ─── Payment History (User) ───

import type {
  UserPaymentHistoryResponse,
  ApiKeyHealthResponse,
  ChangelogEntry,
  TradeNoteResponse,
  TradeNoteRequest,
  BalanceSnapshot,
} from "@/types";

export async function getPaymentHistory(): Promise<UserPaymentHistoryResponse> {
  return request<UserPaymentHistoryResponse>("/api/dashboard/payment-history");
}

// ─── API Key Health Check ───

export async function testApiKeyHealth(): Promise<ApiKeyHealthResponse> {
  return request<ApiKeyHealthResponse>("/api/user/api-keys/test", {
    method: "POST",
  });
}

// ─── Changelog ───

export async function getChangelogs(): Promise<ChangelogEntry[]> {
  return request<ChangelogEntry[]>("/api/changelog");
}

export async function getAdminChangelogs(): Promise<ChangelogEntry[]> {
  return request<ChangelogEntry[]>("/api/admin/changelog");
}

export async function createAdminChangelog(data: Partial<ChangelogEntry>): Promise<ChangelogEntry> {
  return request<ChangelogEntry>("/api/admin/changelog", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function publishAdminChangelog(id: number): Promise<ChangelogEntry> {
  return request<ChangelogEntry>(`/api/admin/changelog/${id}/publish`, {
    method: "POST",
  });
}

export async function deleteAdminChangelog(id: number): Promise<{ message: string }> {
  return request<{ message: string }>(`/api/admin/changelog/${id}`, {
    method: "DELETE",
  });
}

// ─── Trade Notes ───

export async function getTradeNote(tradeId: string): Promise<TradeNoteResponse> {
  return request<TradeNoteResponse>(`/api/dashboard/trades/${tradeId}/note`);
}

export async function saveTradeNote(tradeId: string, data: TradeNoteRequest): Promise<TradeNoteResponse> {
  return request<TradeNoteResponse>(`/api/dashboard/trades/${tradeId}/note`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

// ─── Equity Curve ───

export async function getEquityCurve(days: number = 90): Promise<BalanceSnapshot[]> {
  return request<BalanceSnapshot[]>(`/api/dashboard/equity-curve?days=${days}`);
}

// ─── User Signal Sources ───

export async function getUserSignalSources(): Promise<SignalSourceUserResponse[]> {
  return request<SignalSourceUserResponse[]>("/api/dashboard/signal-sources");
}

// ─── Signal Source Monitor Status ───

export async function getSignalSourceMonitorStatus(): Promise<MonitorStatusResponse> {
  return request<MonitorStatusResponse>("/api/admin/signal-sources/monitor-status");
}

// ─── Admin Daily Signal Report ───

import type {
  DailySignalReportListResponse,
  DailySignalReportDetail,
} from "@/types";

export async function getAdminDailyReports(page = 0, size = 20): Promise<DailySignalReportListResponse> {
  return request<DailySignalReportListResponse>(`/api/admin/dashboard/daily-reports?page=${page}&size=${size}`);
}

export async function getAdminDailyReportDetail(id: number): Promise<DailySignalReportDetail> {
  return request<DailySignalReportDetail>(`/api/admin/dashboard/daily-reports/${id}`);
}

export async function generateAdminDailyReport(date: string): Promise<DailySignalReportDetail> {
  return request<DailySignalReportDetail>(`/api/admin/dashboard/daily-reports/generate?date=${date}`, {
    method: "POST",
  });
}

export async function updateGlobalMonitorSettings(
  data: { authorIds?: string[]; ignoreKeywords?: string[] }
): Promise<{ message: string; configVersion: number; connectedMonitors: number }> {
  return request<{ message: string; configVersion: number; connectedMonitors: number }>(
    "/api/admin/signal-sources/monitor-settings",
    {
      method: "PUT",
      body: JSON.stringify(data),
    }
  );
}
