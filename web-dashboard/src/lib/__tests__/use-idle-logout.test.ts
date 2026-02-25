/**
 * useIdleLogout 測試
 *
 * 測試重點：
 * 1. 已登入 → 啟動計時器
 * 2. 未登入 → 不啟動計時器
 * 3. 閒置超時 → 呼叫 logout 並導向 /login
 * 4. 使用者活動 → 重置計時器，不登出
 * 5. unmount → 清除計時器
 */
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { useIdleLogout } from "../use-idle-logout";

// 用 fake timer 控制時間
beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

const IDLE_TIMEOUT = 30 * 60 * 1000; // 30 分鐘

describe("useIdleLogout", () => {
  it("未登入時不啟動計時器（不呼叫 logout）", () => {
    const logout = vi.fn();
    renderHook(() => useIdleLogout(null, logout));

    vi.advanceTimersByTime(IDLE_TIMEOUT + 1000);

    expect(logout).not.toHaveBeenCalled();
  });

  it("已登入 + 閒置超時 → 呼叫 logout 並導向 /login", () => {
    const logout = vi.fn();
    // mock window.location
    const originalHref = window.location.href;
    Object.defineProperty(window, "location", {
      writable: true,
      value: { ...window.location, href: originalHref },
    });

    renderHook(() => useIdleLogout("valid-token", logout));

    // 還沒超時
    vi.advanceTimersByTime(IDLE_TIMEOUT - 1000);
    expect(logout).not.toHaveBeenCalled();

    // 超時
    vi.advanceTimersByTime(2000);
    expect(logout).toHaveBeenCalledTimes(1);
    expect(window.location.href).toBe("/login");
  });

  it("使用者活動 → 重置計時器，不登出", () => {
    const logout = vi.fn();
    renderHook(() => useIdleLogout("valid-token", logout));

    // 經過 20 分鐘
    vi.advanceTimersByTime(20 * 60 * 1000);

    // 模擬滑鼠移動 → 重置計時器
    document.dispatchEvent(new Event("mousemove"));

    // 再過 20 分鐘（從上次活動算起才 20 分鐘，沒超過 30 分鐘）
    vi.advanceTimersByTime(20 * 60 * 1000);
    expect(logout).not.toHaveBeenCalled();

    // 再過 11 分鐘（從上次活動算起 31 分鐘）
    vi.advanceTimersByTime(11 * 60 * 1000);
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it("鍵盤活動也能重置計時器", () => {
    const logout = vi.fn();
    renderHook(() => useIdleLogout("valid-token", logout));

    vi.advanceTimersByTime(25 * 60 * 1000);
    document.dispatchEvent(new Event("keydown"));

    vi.advanceTimersByTime(25 * 60 * 1000);
    expect(logout).not.toHaveBeenCalled();
  });

  it("unmount 後清除計時器", () => {
    const logout = vi.fn();
    const { unmount } = renderHook(() => useIdleLogout("valid-token", logout));

    unmount();

    vi.advanceTimersByTime(IDLE_TIMEOUT + 1000);
    expect(logout).not.toHaveBeenCalled();
  });
});
