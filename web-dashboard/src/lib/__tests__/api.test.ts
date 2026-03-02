/**
 * api.ts 測試
 *
 * 測試重點：
 * 1. HttpOnly Cookie 認證：credentials: "include" 帶上
 * 2. Refresh Token 自動重試機制（401 → refresh → retry）
 * 3. Refresh 失敗 → 清除登入狀態 + redirect
 * 4. 403 REFERRAL_NOT_VERIFIED → redirect /referral
 * 5. 並行 401 的單例 refresh（防止重複呼叫）
 */
import { describe, it, expect, vi, beforeEach } from "vitest";

// 需要動態 import 因為 api.ts 有 module-level state（refreshPromise）
// 每個 test 前 re-import 確保乾淨狀態
async function loadApi() {
  // 清除 module cache 讓每次 import 拿到新的 module state
  vi.resetModules();
  return await import("@/lib/api");
}

// ─── Mock fetch ───
const mockFetch = vi.fn();
global.fetch = mockFetch;

// ─── Mock window.location ───
const originalLocation = window.location;
beforeEach(() => {
  // @ts-expect-error -- mock location.href
  delete window.location;
  window.location = { ...originalLocation, href: "" } as Location;
  localStorage.clear();
});

function jsonResponse(data: unknown, status = 200) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(data),
    text: () => Promise.resolve(JSON.stringify(data)),
  });
}

function textResponse(body: string, status: number) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    json: () => Promise.resolve(JSON.parse(body)),
    text: () => Promise.resolve(body),
  });
}

// ==================== Normal Request ====================

describe("正常請求", () => {
  it("帶 credentials: include（HttpOnly Cookie 自動帶上）", async () => {
    mockFetch.mockReturnValueOnce(
      jsonResponse({ id: "u1", email: "test@test.com" })
    );

    const api = await loadApi();
    const result = await api.getUserProfile();

    expect(result).toEqual({ id: "u1", email: "test@test.com" });
    expect(mockFetch).toHaveBeenCalledWith(
      "/api/user/me",
      expect.objectContaining({
        credentials: "include",
      })
    );
  });

  it("不帶 Authorization header（改用 Cookie）", async () => {
    mockFetch.mockReturnValueOnce(jsonResponse({ id: "u1" }));

    const api = await loadApi();
    await api.getUserProfile();

    const headers = mockFetch.mock.calls[0][1].headers;
    expect(headers.Authorization).toBeUndefined();
  });

  it("非 ok 回應 → 拋出 Error（含 body）", async () => {
    mockFetch.mockReturnValueOnce(
      textResponse("Server Error", 500)
    );

    const api = await loadApi();
    await expect(api.getUserProfile()).rejects.toThrow("Server Error");
  });
});

// ==================== Refresh Token ====================

describe("401 Refresh Token 自動重試", () => {
  it("refresh 成功 → 重試原始請求", async () => {
    // 第 1 次呼叫 getUserProfile → 401
    mockFetch.mockReturnValueOnce(
      textResponse("", 401)
    );

    // 第 2 次呼叫 /api/auth/refresh → 成功（Cookie 由 Set-Cookie 自動更新）
    mockFetch.mockReturnValueOnce(
      jsonResponse({ userId: "u1", email: "test@test.com", role: "USER" })
    );

    // 第 3 次：重試 getUserProfile → 成功
    mockFetch.mockReturnValueOnce(
      jsonResponse({ id: "u1", email: "test@test.com" })
    );

    const api = await loadApi();
    const result = await api.getUserProfile();

    expect(result).toEqual({ id: "u1", email: "test@test.com" });

    // refresh 呼叫也帶 credentials: include
    const refreshCall = mockFetch.mock.calls[1];
    expect(refreshCall[0]).toBe("/api/auth/refresh");
    expect(refreshCall[1]).toMatchObject({
      method: "POST",
      credentials: "include",
    });

    // 重試也帶 credentials: include
    expect(mockFetch.mock.calls[2][1].credentials).toBe("include");

    // 不應 redirect
    expect(window.location.href).toBe("");
  });

  it("refresh 失敗 → 清除登入 + redirect /login", async () => {
    localStorage.setItem("userId", "u1");
    localStorage.setItem("email", "test@test.com");

    // 第 1 次呼叫 → 401
    mockFetch.mockReturnValueOnce(textResponse("", 401));

    // 第 2 次 refresh → 401（refresh token 也過期）
    mockFetch.mockReturnValueOnce(textResponse("", 401));

    const api = await loadApi();
    await expect(api.getUserProfile()).rejects.toThrow("Unauthorized");

    // localStorage userId/email 已清除
    expect(localStorage.getItem("userId")).toBeNull();
    expect(localStorage.getItem("email")).toBeNull();

    // redirect
    expect(window.location.href).toBe("/login");
  });

  it("refresh 成功但重試仍 401 → 清除登入 + redirect", async () => {
    // 第 1 次 → 401
    mockFetch.mockReturnValueOnce(textResponse("", 401));
    // refresh 成功
    mockFetch.mockReturnValueOnce(
      jsonResponse({ userId: "u1", email: "test@test.com", role: "USER" })
    );
    // 重試仍 401
    mockFetch.mockReturnValueOnce(textResponse("", 401));

    const api = await loadApi();
    await expect(api.getUserProfile()).rejects.toThrow("Unauthorized");
    expect(window.location.href).toBe("/login");
  });

  it("並行 401 只觸發一次 refresh", async () => {
    // 兩個並行請求都返回 401
    mockFetch.mockReturnValueOnce(textResponse("", 401)); // getUserProfile
    mockFetch.mockReturnValueOnce(textResponse("", 401)); // getApiKeys

    // 只觸發一次 refresh
    mockFetch.mockReturnValueOnce(
      jsonResponse({ userId: "u1", email: "test@test.com", role: "USER" })
    );

    // 兩個重試都成功
    mockFetch.mockReturnValueOnce(
      jsonResponse({ id: "u1", email: "test@test.com" })
    );
    mockFetch.mockReturnValueOnce(jsonResponse([]));

    const api = await loadApi();
    const [profile, keys] = await Promise.all([
      api.getUserProfile(),
      api.getApiKeys(),
    ]);

    expect(profile).toEqual({ id: "u1", email: "test@test.com" });
    expect(keys).toEqual([]);

    // 呼叫次數：2 (原始) + 1 (refresh) + 2 (重試) = 5
    expect(mockFetch).toHaveBeenCalledTimes(5);

    // 只有一次打到 /api/auth/refresh
    const refreshCalls = mockFetch.mock.calls.filter(
      ([url]: [string]) => url === "/api/auth/refresh"
    );
    expect(refreshCalls).toHaveLength(1);
  });
});

// ==================== 403 REFERRAL_NOT_VERIFIED ====================

describe("403 REFERRAL_NOT_VERIFIED", () => {
  it("redirect /referral + 拋出 Error", async () => {
    // 模擬在 dashboard 頁
    Object.defineProperty(window.location, "pathname", {
      value: "/",
      writable: true,
      configurable: true,
    });

    mockFetch.mockReturnValueOnce(
      textResponse(
        JSON.stringify({ error: "REFERRAL_NOT_VERIFIED", message: "請先完成推薦碼驗證" }),
        403
      )
    );

    const api = await loadApi();
    await expect(api.getDashboardOverview()).rejects.toThrow(
      "REFERRAL_NOT_VERIFIED"
    );

    expect(window.location.href).toBe("/referral");
  });

  it("已在 /referral → 不重導，仍拋出 Error", async () => {
    Object.defineProperty(window.location, "pathname", {
      value: "/referral",
      writable: true,
      configurable: true,
    });

    mockFetch.mockReturnValueOnce(
      textResponse(
        JSON.stringify({ error: "REFERRAL_NOT_VERIFIED", message: "請先完成推薦碼驗證" }),
        403
      )
    );

    const api = await loadApi();
    await expect(api.getDashboardOverview()).rejects.toThrow(
      "REFERRAL_NOT_VERIFIED"
    );

    // 不應 redirect（已在 /referral）
    expect(window.location.href).toBe("");
  });

  it("非 REFERRAL_NOT_VERIFIED 的 403 → 拋出一般錯誤", async () => {
    mockFetch.mockReturnValueOnce(
      textResponse("Forbidden", 403)
    );

    const api = await loadApi();
    await expect(api.getDashboardOverview()).rejects.toThrow("Forbidden");

    // 不 redirect
    expect(window.location.href).toBe("");
  });
});

// ==================== Referral API Functions ====================

describe("Referral API 函式", () => {
  it("getReferralStatus → GET /api/referral/status", async () => {
    const mockData = {
      status: "NOT_STARTED",
      exchangeUid: null,
      verifiedAt: null,
      referralLink: "https://www.binance.com/referral/123",
      referralCode: "ABC123",
    };
    mockFetch.mockReturnValueOnce(jsonResponse(mockData));

    const api = await loadApi();
    const result = await api.getReferralStatus();

    expect(result).toEqual(mockData);
    expect(mockFetch).toHaveBeenCalledWith(
      "/api/referral/status",
      expect.objectContaining({ credentials: "include" })
    );
  });

  it("submitReferralUid → POST /api/referral/submit-uid", async () => {
    const mockData = {
      status: "PENDING",
      exchangeUid: "12345678",
      verifiedAt: null,
      referralLink: "https://www.binance.com/referral/123",
      referralCode: "ABC123",
    };
    mockFetch.mockReturnValueOnce(jsonResponse(mockData));

    const api = await loadApi();
    const result = await api.submitReferralUid({ exchangeUid: "12345678" });

    expect(result).toEqual(mockData);
    const [url, opts] = mockFetch.mock.calls[0];
    expect(url).toBe("/api/referral/submit-uid");
    expect(opts.method).toBe("POST");
    expect(opts.credentials).toBe("include");
    expect(JSON.parse(opts.body)).toEqual({ exchangeUid: "12345678" });
  });

  it("getReferralProgram → GET /api/referral/program", async () => {
    mockFetch.mockReturnValueOnce(jsonResponse({ referralCode: "XYZ" }));

    const api = await loadApi();
    await api.getReferralProgram();

    expect(mockFetch).toHaveBeenCalledWith(
      "/api/referral/program",
      expect.objectContaining({ credentials: "include" })
    );
  });
});

// ==================== Auth Functions ====================

describe("Auth API 函式", () => {
  it("login → POST /api/auth/login（帶 credentials: include 接收 Set-Cookie）", async () => {
    mockFetch.mockReturnValueOnce(
      jsonResponse({ userId: "u1", email: "test@test.com", role: "USER", expiresIn: 1800 })
    );

    const api = await loadApi();
    const result = await api.login({ email: "test@test.com", password: "pw" });

    expect(result).toEqual({
      userId: "u1",
      email: "test@test.com",
      role: "USER",
      expiresIn: 1800,
    });

    const [url, opts] = mockFetch.mock.calls[0];
    expect(url).toBe("/api/auth/login");
    expect(opts.credentials).toBe("include");
  });

  it("apiLogout → POST /api/auth/logout", async () => {
    mockFetch.mockReturnValueOnce(jsonResponse({ message: "登出成功" }));

    const api = await loadApi();
    await api.apiLogout();

    const [url, opts] = mockFetch.mock.calls[0];
    expect(url).toBe("/api/auth/logout");
    expect(opts.method).toBe("POST");
    expect(opts.credentials).toBe("include");
  });

  it("fetchCurrentUser → GET /api/auth/me", async () => {
    mockFetch.mockReturnValueOnce(
      jsonResponse({ userId: "u1", email: "test@test.com", role: "USER" })
    );

    const api = await loadApi();
    const result = await api.fetchCurrentUser();

    expect(result).toEqual({ userId: "u1", email: "test@test.com", role: "USER" });
    expect(mockFetch).toHaveBeenCalledWith(
      "/api/auth/me",
      expect.objectContaining({ credentials: "include" })
    );
  });
});
