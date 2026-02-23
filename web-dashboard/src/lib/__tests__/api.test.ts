/**
 * api.ts 測試
 *
 * 測試重點：
 * 1. Refresh Token 自動重試機制（401 → refresh → retry）
 * 2. Refresh 失敗 → 清除登入狀態 + redirect
 * 3. 403 REFERRAL_NOT_VERIFIED → redirect /referral
 * 4. 正常請求 + Authorization header
 * 5. 並行 401 的單例 refresh（防止重複呼叫）
 */
import { describe, it, expect, vi, beforeEach } from "vitest";

// 需要動態 import 因為 api.ts 有 module-level state（refreshPromise）
// 每個 test 前 re-import 確保乾淨狀態
async function loadApi() {
  // 清除 module cache 讓每次 import 拿到新的 module state
  const modulePath = "@/lib/api";
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
  it("帶 Authorization header", async () => {
    localStorage.setItem("token", "my-jwt");
    mockFetch.mockReturnValueOnce(
      jsonResponse({ id: "u1", email: "test@test.com" })
    );

    const api = await loadApi();
    const result = await api.getUserProfile();

    expect(result).toEqual({ id: "u1", email: "test@test.com" });
    expect(mockFetch).toHaveBeenCalledWith(
      "/api/user/me",
      expect.objectContaining({
        headers: expect.objectContaining({
          Authorization: "Bearer my-jwt",
        }),
      })
    );
  });

  it("無 token 時不帶 Authorization", async () => {
    mockFetch.mockReturnValueOnce(jsonResponse({ id: "anon" }));

    const api = await loadApi();
    const result = await api.getUserProfile();

    expect(result).toEqual({ id: "anon" });
    const headers = mockFetch.mock.calls[0][1].headers;
    expect(headers.Authorization).toBeUndefined();
  });

  it("非 ok 回應 → 拋出 Error（含 body）", async () => {
    localStorage.setItem("token", "my-jwt");
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
    localStorage.setItem("token", "expired-jwt");
    localStorage.setItem("refreshToken", "valid-refresh");

    // 第 1 次呼叫 getUserProfile → 401
    mockFetch.mockReturnValueOnce(
      textResponse("", 401)
    );

    // 第 2 次呼叫 /api/auth/refresh → 成功
    mockFetch.mockReturnValueOnce(
      jsonResponse({
        token: "new-jwt",
        refreshToken: "new-refresh",
        userId: "u1",
        email: "test@test.com",
      })
    );

    // 第 3 次：重試 getUserProfile → 成功
    mockFetch.mockReturnValueOnce(
      jsonResponse({ id: "u1", email: "test@test.com" })
    );

    const api = await loadApi();
    const result = await api.getUserProfile();

    expect(result).toEqual({ id: "u1", email: "test@test.com" });

    // 驗證 token 已更新
    expect(localStorage.getItem("token")).toBe("new-jwt");
    expect(localStorage.getItem("refreshToken")).toBe("new-refresh");
    expect(localStorage.getItem("userId")).toBe("u1");
    expect(localStorage.getItem("email")).toBe("test@test.com");

    // 第三次呼叫用新 token
    const retryHeaders = mockFetch.mock.calls[2][1].headers;
    expect(retryHeaders.Authorization).toBe("Bearer new-jwt");

    // 不應 redirect
    expect(window.location.href).toBe("");
  });

  it("refresh 失敗 → 清除登入 + redirect /login", async () => {
    localStorage.setItem("token", "expired-jwt");
    localStorage.setItem("refreshToken", "invalid-refresh");
    localStorage.setItem("userId", "u1");
    localStorage.setItem("email", "test@test.com");

    // 第 1 次呼叫 → 401
    mockFetch.mockReturnValueOnce(textResponse("", 401));

    // 第 2 次 refresh → 401（refresh token 也過期）
    mockFetch.mockReturnValueOnce(textResponse("", 401));

    const api = await loadApi();
    await expect(api.getUserProfile()).rejects.toThrow("Unauthorized");

    // localStorage 全部清除
    expect(localStorage.getItem("token")).toBeNull();
    expect(localStorage.getItem("refreshToken")).toBeNull();
    expect(localStorage.getItem("userId")).toBeNull();
    expect(localStorage.getItem("email")).toBeNull();

    // redirect
    expect(window.location.href).toBe("/login");
  });

  it("無 refreshToken → 直接清除登入 + redirect", async () => {
    localStorage.setItem("token", "expired-jwt");
    // 不設 refreshToken

    // 呼叫 → 401
    mockFetch.mockReturnValueOnce(textResponse("", 401));

    const api = await loadApi();
    await expect(api.getUserProfile()).rejects.toThrow("Unauthorized");

    expect(window.location.href).toBe("/login");
    // 只呼叫一次 fetch（不嘗試 refresh）
    expect(mockFetch).toHaveBeenCalledTimes(1);
  });

  it("refresh 成功但重試仍 401 → 清除登入 + redirect", async () => {
    localStorage.setItem("token", "expired-jwt");
    localStorage.setItem("refreshToken", "valid-refresh");

    // 第 1 次 → 401
    mockFetch.mockReturnValueOnce(textResponse("", 401));
    // refresh 成功
    mockFetch.mockReturnValueOnce(
      jsonResponse({ token: "new-jwt", refreshToken: "new-refresh" })
    );
    // 重試仍 401
    mockFetch.mockReturnValueOnce(textResponse("", 401));

    const api = await loadApi();
    await expect(api.getUserProfile()).rejects.toThrow("Unauthorized");
    expect(window.location.href).toBe("/login");
  });

  it("並行 401 只觸發一次 refresh", async () => {
    localStorage.setItem("token", "expired-jwt");
    localStorage.setItem("refreshToken", "valid-refresh");

    // 兩個並行請求都返回 401
    mockFetch.mockReturnValueOnce(textResponse("", 401)); // getUserProfile
    mockFetch.mockReturnValueOnce(textResponse("", 401)); // getApiKeys

    // 只觸發一次 refresh
    mockFetch.mockReturnValueOnce(
      jsonResponse({ token: "new-jwt", refreshToken: "new-refresh" })
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
    localStorage.setItem("token", "valid-jwt");

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
    localStorage.setItem("token", "valid-jwt");

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
    localStorage.setItem("token", "valid-jwt");

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
    localStorage.setItem("token", "my-jwt");

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
      expect.anything()
    );
  });

  it("submitReferralUid → POST /api/referral/submit-uid", async () => {
    localStorage.setItem("token", "my-jwt");

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
    expect(JSON.parse(opts.body)).toEqual({ exchangeUid: "12345678" });
  });

  it("getReferralProgram → GET /api/referral/program", async () => {
    localStorage.setItem("token", "my-jwt");
    mockFetch.mockReturnValueOnce(jsonResponse({ referralCode: "XYZ" }));

    const api = await loadApi();
    await api.getReferralProgram();

    expect(mockFetch).toHaveBeenCalledWith(
      "/api/referral/program",
      expect.anything()
    );
  });
});
