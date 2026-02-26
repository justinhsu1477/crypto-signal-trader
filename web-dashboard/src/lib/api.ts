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
  CryptoCheckoutInfo,
  ReferralStatusResponse,
  SubmitUidRequest,
  VerifyEmailRequest,
  ResendCodeRequest,
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

  // 403 推薦碼未驗證 — 直接重導至 /referral（soft gate fallback）
  if (res.status === 403) {
    const body = await res.text();
    try {
      const parsed = JSON.parse(body);
      if (parsed.error === "REFERRAL_NOT_VERIFIED") {
        if (typeof window !== "undefined" && !window.location.pathname.startsWith("/referral")) {
          window.location.href = "/referral";
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
    throw new Error(body || `HTTP ${res.status}`);
  }

  return res.json() as Promise<T>;
}

export async function login(data: LoginRequest): Promise<LoginResponse> {
  return publicRequest<LoginResponse>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify(data),
  });
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
