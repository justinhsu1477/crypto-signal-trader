/**
 * API Error Handling 改進測試
 *
 * 測試重點：
 * 1. JSON error body → 解析 error / message / detail 欄位
 * 2. Plain text body → 使用原始文字
 * 3. REFERRAL_NOT_VERIFIED 403 → redirect /referral
 * 4. publicRequest 同樣的 error parsing
 */
import { describe, it, expect, vi, beforeEach } from "vitest";

async function loadApi() {
  vi.resetModules();
  return await import("@/lib/api");
}

const mockFetch = vi.fn();
global.fetch = mockFetch;

const originalLocation = window.location;
beforeEach(() => {
  // @ts-expect-error -- mock location.href
  delete window.location;
  window.location = { ...originalLocation, href: "", pathname: "/" } as Location;
  localStorage.clear();
  mockFetch.mockReset();
});

function textResponse(body: string, status: number) {
  return Promise.resolve({
    ok: status >= 200 && status < 300,
    status,
    json: () => {
      try { return Promise.resolve(JSON.parse(body)); } catch { return Promise.reject(new Error("not json")); }
    },
    text: () => Promise.resolve(body),
  });
}

describe("API Error Parsing", () => {
  it("JSON body with 'error' field → uses error field as message", async () => {
    mockFetch.mockReturnValueOnce(
      textResponse(JSON.stringify({ error: "Invalid credentials" }), 400)
    );

    const api = await loadApi();
    await expect(api.getUserProfile()).rejects.toThrow("Invalid credentials");
  });

  it("JSON body with 'message' field → uses message field", async () => {
    mockFetch.mockReturnValueOnce(
      textResponse(JSON.stringify({ message: "Rate limit exceeded" }), 429)
    );

    const api = await loadApi();
    await expect(api.getUserProfile()).rejects.toThrow("Rate limit exceeded");
  });

  it("JSON body with 'detail' field → uses detail field", async () => {
    mockFetch.mockReturnValueOnce(
      textResponse(JSON.stringify({ detail: "Detailed error info" }), 500)
    );

    const api = await loadApi();
    await expect(api.getUserProfile()).rejects.toThrow("Detailed error info");
  });

  it("Plain text body → uses raw text", async () => {
    mockFetch.mockReturnValueOnce(
      textResponse("Internal Server Error", 500)
    );

    const api = await loadApi();
    await expect(api.getUserProfile()).rejects.toThrow("Internal Server Error");
  });

  it("Empty body → uses HTTP status code", async () => {
    mockFetch.mockReturnValueOnce(
      textResponse("", 500)
    );

    const api = await loadApi();
    await expect(api.getUserProfile()).rejects.toThrow("HTTP 500");
  });

  it("403 REFERRAL_NOT_VERIFIED → redirect to /referral", async () => {
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
    await expect(api.getDashboardOverview()).rejects.toThrow("REFERRAL_NOT_VERIFIED");
    expect(window.location.href).toBe("/referral");
  });

  it("403 other error → no redirect", async () => {
    mockFetch.mockReturnValueOnce(
      textResponse(JSON.stringify({ error: "Forbidden" }), 403)
    );

    const api = await loadApi();
    await expect(api.getUserProfile()).rejects.toThrow("Forbidden");
    expect(window.location.href).toBe("");
  });
});

describe("publicRequest Error Parsing", () => {
  it("login failure → parses JSON error body", async () => {
    mockFetch.mockReturnValueOnce(
      textResponse(JSON.stringify({ error: "Invalid email or password" }), 401)
    );

    const api = await loadApi();
    await expect(
      api.login({ email: "test@test.com", password: "wrong" })
    ).rejects.toThrow("Invalid email or password");
  });

  it("register failure → parses JSON message body", async () => {
    mockFetch.mockReturnValueOnce(
      textResponse(JSON.stringify({ message: "Email already registered" }), 409)
    );

    const api = await loadApi();
    await expect(
      api.register({ email: "dup@test.com", password: "pw", name: "Test" })
    ).rejects.toThrow("Email already registered");
  });
});
