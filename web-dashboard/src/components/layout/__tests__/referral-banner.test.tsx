/**
 * ReferralBanner 測試
 *
 * 測試重點：
 * 1. 初始不顯示
 * 2. 收到 "referral-not-verified" CustomEvent → 顯示 banner
 * 3. 點擊「前往綁定」 → 隱藏 banner + router.push("/referral")
 * 4. 點擊 X → 隱藏 banner
 * 5. unmount 後移除 event listener
 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ReferralBanner } from "../referral-banner";

// ─── Mock i18n ───
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => key,
    locale: "en",
    setLocale: vi.fn(),
  }),
}));

// ─── Mock router ───
const mockPush = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
  usePathname: () => "/",
}));

// ─── Mock utils ───
vi.mock("@/lib/utils", () => ({
  cn: (...inputs: string[]) => inputs.filter(Boolean).join(" "),
}));

beforeEach(() => {
  mockPush.mockClear();
});

function fireReferralEvent() {
  act(() => {
    window.dispatchEvent(new CustomEvent("referral-not-verified"));
  });
}

// ==================== Tests ====================

describe("ReferralBanner", () => {
  it("初始不顯示", () => {
    const { container } = render(<ReferralBanner />);
    // banner 不存在
    expect(container.innerHTML).toBe("");
  });

  it("收到 referral-not-verified event → 顯示 banner", () => {
    render(<ReferralBanner />);

    fireReferralEvent();

    expect(screen.getByText("referral.bannerMessage")).toBeInTheDocument();
    expect(screen.getByText("referral.bannerAction")).toBeInTheDocument();
  });

  it("點擊「前往綁定」→ 隱藏 + navigate /referral", async () => {
    const user = userEvent.setup();
    render(<ReferralBanner />);

    fireReferralEvent();
    expect(screen.getByText("referral.bannerMessage")).toBeInTheDocument();

    await user.click(screen.getByText("referral.bannerAction"));

    // banner 消失
    expect(screen.queryByText("referral.bannerMessage")).not.toBeInTheDocument();
    // navigate
    expect(mockPush).toHaveBeenCalledWith("/referral");
  });

  it("點擊 X → 隱藏 banner（不 navigate）", async () => {
    const user = userEvent.setup();
    render(<ReferralBanner />);

    fireReferralEvent();
    expect(screen.getByText("referral.bannerMessage")).toBeInTheDocument();

    // X 按鈕 — 找含 X icon 的 button（不含 referral.bannerAction 文字的按鈕）
    const buttons = screen.getAllByRole("button");
    const closeBtn = buttons.find(
      (btn) => !btn.textContent?.includes("referral.bannerAction")
    );
    expect(closeBtn).toBeDefined();
    await user.click(closeBtn!);

    // banner 消失
    expect(screen.queryByText("referral.bannerMessage")).not.toBeInTheDocument();
    // 不 navigate
    expect(mockPush).not.toHaveBeenCalled();
  });

  it("unmount 後再 dispatch event 不會出錯", () => {
    const { unmount } = render(<ReferralBanner />);
    unmount();

    // 不應拋出錯誤
    expect(() => fireReferralEvent()).not.toThrow();
  });

  it("多次收到 event → 仍只顯示一個 banner", () => {
    render(<ReferralBanner />);

    fireReferralEvent();
    fireReferralEvent();
    fireReferralEvent();

    // 只有一個 banner message 元素
    expect(screen.getAllByText("referral.bannerMessage")).toHaveLength(1);
  });
});
