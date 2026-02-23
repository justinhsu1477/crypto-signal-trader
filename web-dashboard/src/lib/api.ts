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
  UpgradePlanRequest,
  ReferralStatusResponse,
  SubmitUidRequest,
} from "@/types";

const BASE = "";  // 使用 Next.js rewrites proxy

// 防止多個並行請求同時觸發 refresh
let refreshPromise: Promise<boolean> | null = null;

function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem("token");
}

function getRefreshToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem("refreshToken");
}

function clearAuthAndRedirect(): void {
  if (typeof window !== "undefined") {
    localStorage.removeItem("token");
    localStorage.removeItem("refreshToken");
    localStorage.removeItem("userId");
    localStorage.removeItem("email");
    window.location.href = "/login";
  }
}

/**
 * 嘗試用 refreshToken 取得新的 access token
 * 使用單例模式：多個並行 401 只觸發一次 refresh
 */
async function tryRefreshToken(): Promise<boolean> {
  const rt = getRefreshToken();
  if (!rt) return false;

  try {
    const res = await fetch(`${BASE}/api/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refreshToken: rt }),
    });

    if (!res.ok) return false;

    const data = await res.json();
    localStorage.setItem("token", data.token);
    localStorage.setItem("refreshToken", data.refreshToken);
    if (data.userId) localStorage.setItem("userId", data.userId);
    if (data.email) localStorage.setItem("email", data.email);
    return true;
  } catch {
    return false;
  }
}

async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const token = getToken();
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    ...((options.headers as Record<string, string>) || {}),
  };
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }

  const res = await fetch(`${BASE}${url}`, { ...options, headers });

  if (res.status === 401) {
    // 嘗試 refresh token（單例模式，防止並行重複 refresh）
    if (!refreshPromise) {
      refreshPromise = tryRefreshToken().finally(() => {
        refreshPromise = null;
      });
    }
    const refreshed = await refreshPromise;

    if (refreshed) {
      // Refresh 成功 → 用新 token 重試原始請求
      const newToken = getToken();
      const retryHeaders: Record<string, string> = {
        "Content-Type": "application/json",
        ...((options.headers as Record<string, string>) || {}),
      };
      if (newToken) {
        retryHeaders["Authorization"] = `Bearer ${newToken}`;
      }
      const retryRes = await fetch(`${BASE}${url}`, { ...options, headers: retryHeaders });

      if (retryRes.ok) {
        return retryRes.json() as Promise<T>;
      }

      // 重試仍失敗 → 清除登入狀態
      if (retryRes.status === 401) {
        clearAuthAndRedirect();
        throw new Error("Unauthorized");
      }

      // 其他錯誤照常處理（繼續往下走 403 / !res.ok 邏輯）
      const body = await retryRes.text();
      throw new Error(body || `HTTP ${retryRes.status}`);
    }

    // Refresh 失敗 → 清除登入狀態，踢回登入頁
    clearAuthAndRedirect();
    throw new Error("Unauthorized");
  }

  // 403 推薦碼未驗證 — dispatch event 讓 ReferralBanner 顯示
  if (res.status === 403) {
    const body = await res.text();
    try {
      const parsed = JSON.parse(body);
      if (parsed.error === "REFERRAL_NOT_VERIFIED") {
        if (typeof window !== "undefined") {
          window.dispatchEvent(new CustomEvent("referral-not-verified"));
        }
        throw new Error("REFERRAL_NOT_VERIFIED");
      }
    } catch (e) {
      if (e instanceof Error && e.message === "REFERRAL_NOT_VERIFIED") throw e;
    }
    throw new Error(body || `HTTP 403`);
  }

  if (!res.ok) {
    const body = await res.text();
    throw new Error(body || `HTTP ${res.status}`);
  }

  return res.json() as Promise<T>;
}

// ==================== Auth ====================

export async function login(data: LoginRequest): Promise<LoginResponse> {
  return request<LoginResponse>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function register(data: RegisterRequest): Promise<RegisterResponse> {
  return request<RegisterResponse>("/api/auth/register", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

// refreshToken 已整合進 request() 的 401 自動重試機制
// 不再需要外部呼叫

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

export async function upgradeSubscription(
  data: UpgradePlanRequest
): Promise<{ status: string; message: string }> {
  return request<{ status: string; message: string }>("/api/subscription/upgrade", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export async function getCheckoutUrl(
  planId: string
): Promise<{ checkoutUrl: string }> {
  return request<{ checkoutUrl: string }>("/api/subscription/checkout", {
    method: "POST",
    body: JSON.stringify({ planId }),
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
