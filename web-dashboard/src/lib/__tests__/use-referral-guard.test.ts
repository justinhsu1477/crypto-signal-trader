/**
 * useReferralGuard 測試
 *
 * 測試重點：
 * 1. VERIFIED → isVerified=true, needsReferral=false
 * 2. NOT_STARTED → needsReferral=true
 * 3. PENDING → needsReferral=true
 * 4. pathname="/referral" → 不檢查（防迴圈）
 * 5. API error → fail-open（不阻擋）
 * 6. cache hit → 不重複呼叫 API
 * 7. clearReferralCache → 重置快取
 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";

// ─── Mock next/navigation ───
let mockPathname = "/";

vi.mock("next/navigation", () => ({
  usePathname: () => mockPathname,
}));

// ─── Mock API ───
const mockGetReferralStatus = vi.fn();

vi.mock("@/lib/api", () => ({
  getReferralStatus: (...args: unknown[]) => mockGetReferralStatus(...args),
}));

// ─── Dynamic import for clean module state ───
async function loadHook() {
  vi.resetModules();
  return await import("@/lib/use-referral-guard");
}

beforeEach(() => {
  vi.clearAllMocks();
  mockPathname = "/";
});

describe("useReferralGuard", () => {
  it("VERIFIED → isVerified=true, needsReferral=false", async () => {
    mockGetReferralStatus.mockResolvedValueOnce({ status: "VERIFIED" });

    const mod = await loadHook();
    const { result } = renderHook(() => mod.useReferralGuard());

    await waitFor(() => {
      expect(result.current.isChecking).toBe(false);
    });

    expect(result.current.isVerified).toBe(true);
    expect(result.current.needsReferral).toBe(false);
  });

  it("NOT_STARTED → needsReferral=true", async () => {
    mockGetReferralStatus.mockResolvedValueOnce({
      status: "NOT_STARTED",
      exchangeUid: null,
      verifiedAt: null,
      referralLink: "https://example.com",
      referralCode: "ABC",
    });

    const mod = await loadHook();
    const { result } = renderHook(() => mod.useReferralGuard());

    await waitFor(() => {
      expect(result.current.isChecking).toBe(false);
    });

    expect(result.current.needsReferral).toBe(true);
    expect(result.current.isVerified).toBe(false);
  });

  it("PENDING → needsReferral=true", async () => {
    mockGetReferralStatus.mockResolvedValueOnce({
      status: "PENDING",
      exchangeUid: "12345678",
      verifiedAt: null,
      referralLink: "https://example.com",
      referralCode: "ABC",
    });

    const mod = await loadHook();
    const { result } = renderHook(() => mod.useReferralGuard());

    await waitFor(() => {
      expect(result.current.isChecking).toBe(false);
    });

    expect(result.current.needsReferral).toBe(true);
    expect(result.current.isVerified).toBe(false);
  });

  it("pathname=/referral → 不檢查，isChecking=false", async () => {
    mockPathname = "/referral";

    const mod = await loadHook();
    const { result } = renderHook(() => mod.useReferralGuard());

    await waitFor(() => {
      expect(result.current.isChecking).toBe(false);
    });

    expect(result.current.needsReferral).toBe(false);
    // 不呼叫 API
    expect(mockGetReferralStatus).not.toHaveBeenCalled();
  });

  it("API error → fail-open（isChecking=false, needsReferral=false）", async () => {
    mockGetReferralStatus.mockRejectedValueOnce(new Error("Network Error"));

    const mod = await loadHook();
    const { result } = renderHook(() => mod.useReferralGuard());

    await waitFor(() => {
      expect(result.current.isChecking).toBe(false);
    });

    expect(result.current.needsReferral).toBe(false);
  });

  it("cache hit VERIFIED → 不重複呼叫 API", async () => {
    // 第一次：API 回 VERIFIED
    mockGetReferralStatus.mockResolvedValueOnce({ status: "VERIFIED" });

    const mod = await loadHook();
    const { result: r1 } = renderHook(() => mod.useReferralGuard());

    await waitFor(() => {
      expect(r1.current.isChecking).toBe(false);
    });

    expect(mockGetReferralStatus).toHaveBeenCalledTimes(1);

    // 第二次 render（同 module）→ 不再呼叫 API
    const { result: r2 } = renderHook(() => mod.useReferralGuard());

    expect(r2.current.isChecking).toBe(false);
    expect(r2.current.isVerified).toBe(true);
    // API 仍然只被呼叫一次
    expect(mockGetReferralStatus).toHaveBeenCalledTimes(1);
  });

  it("clearReferralCache → 重置快取", async () => {
    // 先讓 cache 有值
    mockGetReferralStatus.mockResolvedValueOnce({ status: "VERIFIED" });

    const mod = await loadHook();
    const { result: r1 } = renderHook(() => mod.useReferralGuard());

    await waitFor(() => {
      expect(r1.current.isVerified).toBe(true);
    });

    // 清除 cache
    mod.clearReferralCache();

    // 下次 render 應重新 fetch
    mockGetReferralStatus.mockResolvedValueOnce({ status: "VERIFIED" });
    const { result: r2 } = renderHook(() => mod.useReferralGuard());

    await waitFor(() => {
      expect(r2.current.isVerified).toBe(true);
    });

    expect(mockGetReferralStatus).toHaveBeenCalledTimes(2);
  });
});
