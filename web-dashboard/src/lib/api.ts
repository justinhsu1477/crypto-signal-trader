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
} from "@/types";

const BASE = "";  // 使用 Next.js rewrites proxy

function getToken(): string | null {
  if (typeof window === "undefined") return null;
  return localStorage.getItem("token");
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
    // Token expired — clear and redirect
    if (typeof window !== "undefined") {
      localStorage.removeItem("token");
      localStorage.removeItem("refreshToken");
      window.location.href = "/login";
    }
    throw new Error("Unauthorized");
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

export async function refreshToken(token: string): Promise<LoginResponse> {
  return request<LoginResponse>("/api/auth/refresh", {
    method: "POST",
    body: JSON.stringify({ refreshToken: token }),
  });
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
