/**
 * ReferralGuard 元件測試
 *
 * 測試重點：
 * 1. isChecking → 顯示 spinner
 * 2. verified → 顯示 children
 */
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import { ReferralGuard } from "../referral-guard";

// ─── Mock useReferralGuard ───
const mockUseReferralGuard = vi.fn();

vi.mock("@/lib/use-referral-guard", () => ({
  useReferralGuard: () => mockUseReferralGuard(),
}));

describe("ReferralGuard", () => {
  it("isChecking=true → 顯示 spinner，不顯示 children", () => {
    mockUseReferralGuard.mockReturnValue({ isChecking: true, isVerified: false });

    render(
      <ReferralGuard>
        <div data-testid="child-content">Dashboard</div>
      </ReferralGuard>
    );

    expect(screen.queryByTestId("child-content")).not.toBeInTheDocument();
    // spinner 有 animate-spin class
    const spinner = document.querySelector(".animate-spin");
    expect(spinner).toBeInTheDocument();
  });

  it("isChecking=false → 顯示 children", () => {
    mockUseReferralGuard.mockReturnValue({ isChecking: false, isVerified: true });

    render(
      <ReferralGuard>
        <div data-testid="child-content">Dashboard</div>
      </ReferralGuard>
    );

    expect(screen.getByTestId("child-content")).toBeInTheDocument();
    const spinner = document.querySelector(".animate-spin");
    expect(spinner).not.toBeInTheDocument();
  });
});
