/**
 * useReferralGuard 測試
 *
 * 測試重點：
 * 1. VERIFIED → 不 redirect、isVerified=true
 * 2. NOT_STARTED → redirect /referral
 * 3. PENDING → redirect /referral
 * 4. pathname="/referral" → 不 redirect（防迴圈）
 * 5. API error → fail-open（不 redirect）
 * 6. cache hit → 不重複呼叫 API
 * 7. clearReferralCache → 重置快取
 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";

// ─── Mock next/navigation ───
const mockReplace = vi.fn();
let mockPathname = "/";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: mockReplace }),
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
  it("VERIFIED → isVerified=true, 不 redirect", async () => {
    mockGetReferralStatus.mockResolvedValueOnce({ status: "VERIFIED" });

    const mod = await loadHook();
    const { result } = renderHook(() => mod.useReferralGuard());

    await waitFor(() => {
      expect(result.current.isChecking).toBe(false);
    });

    expect(result.current.isVerified).toBe(true);
    expect(mockReplace).not.toHaveBeenCalled();
  });

  it("NOT_STARTED → redirect /referral", async () => {
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
      expect(mockReplace).toHaveBeenCalledWith("/referral");
    });

    // isChecking 保持 true（redirect 中）
    expect(result.current.isChecking).toBe(true);
    expect(result.current.isVerified).toBe(false);
  });

  it("PENDING → redirect /referral", async () => {
    mockGetReferralStatus.mockResolvedValueOnce({
      status: "PENDING",
      exchangeUid: "12345678",
      verifiedAt: null,
      referralLink: "https://example.com",
      referralCode: "ABC",
    });

    const mod = await loadHook();
    renderHook(() => mod.useReferralGuard());

    await waitFor(() => {
      expect(mockReplace).toHaveBeenCalledWith("/referral");
    });
  });

  it("pathname=/referral → 不 redirect，isChecking=false", async () => {
    mockPathname = "/referral";

    const mod = await loadHook();
    const { result } = renderHook(() => mod.useReferralGuard());

    await waitFor(() => {
      expect(result.current.isChecking).toBe(false);
    });

    expect(mockReplace).not.toHaveBeenCalled();
    // 不呼叫 API
    expect(mockGetReferralStatus).not.toHaveBeenCalled();
  });

  it("API error → fail-open（isChecking=false, 不 redirect）", async () => {
    mockGetReferralStatus.mockRejectedValueOnce(new Error("Network Error"));

    const mod = await loadHook();
    const { result } = renderHook(() => mod.useReferralGuard());

    await waitFor(() => {
      expect(result.current.isChecking).toBe(false);
    });

    expect(mockReplace).not.toHaveBeenCalled();
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
