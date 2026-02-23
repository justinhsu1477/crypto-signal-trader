/**
 * ReferralGuard 元件測試
 *
 * 測試重點：
 * 1. isChecking → 顯示 spinner
 * 2. verified → 顯示 children
 * 3. needsReferral → 顯示 Dialog 對話框
 */
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ReferralGuard } from "../referral-guard";

// ─── Mock useReferralGuard ───
const mockUseReferralGuard = vi.fn();

vi.mock("@/lib/use-referral-guard", () => ({
  useReferralGuard: () => mockUseReferralGuard(),
}));

// ─── Mock i18n ───
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => key,
    locale: "en",
    setLocale: vi.fn(),
  }),
}));

// ─── Mock next/navigation ───
const mockPush = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mockPush }),
}));

describe("ReferralGuard", () => {
  it("isChecking=true → 顯示 spinner，不顯示 children", () => {
    mockUseReferralGuard.mockReturnValue({
      isChecking: true,
      isVerified: false,
      needsReferral: false,
    });

    render(
      <ReferralGuard>
        <div data-testid="child-content">Dashboard</div>
      </ReferralGuard>
    );

    expect(screen.queryByTestId("child-content")).not.toBeInTheDocument();
    const spinner = document.querySelector(".animate-spin");
    expect(spinner).toBeInTheDocument();
  });

  it("isChecking=false, isVerified=true → 顯示 children", () => {
    mockUseReferralGuard.mockReturnValue({
      isChecking: false,
      isVerified: true,
      needsReferral: false,
    });

    render(
      <ReferralGuard>
        <div data-testid="child-content">Dashboard</div>
      </ReferralGuard>
    );

    expect(screen.getByTestId("child-content")).toBeInTheDocument();
    const spinner = document.querySelector(".animate-spin");
    expect(spinner).not.toBeInTheDocument();
  });

  it("needsReferral=true → 顯示 Dialog 對話框 + children（背景）", () => {
    mockUseReferralGuard.mockReturnValue({
      isChecking: false,
      isVerified: false,
      needsReferral: true,
    });

    render(
      <ReferralGuard>
        <div data-testid="child-content">Dashboard</div>
      </ReferralGuard>
    );

    // children 仍然渲染（作為背景）
    expect(screen.getByTestId("child-content")).toBeInTheDocument();

    // Dialog 文案存在
    expect(screen.getByText("referral.guardTitle")).toBeInTheDocument();
    expect(screen.getByText("referral.guardDescription")).toBeInTheDocument();
    expect(screen.getByText("referral.guardAction")).toBeInTheDocument();
  });
});
