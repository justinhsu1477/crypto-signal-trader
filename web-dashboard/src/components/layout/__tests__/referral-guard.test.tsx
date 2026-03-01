import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ReferralBanner } from "../referral-guard";

const mockUseReferralGuard = vi.fn();

vi.mock("@/lib/use-referral-guard", () => ({
  useReferralGuard: (...args: unknown[]) => mockUseReferralGuard(...args),
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    role: "USER",
  }),
}));

vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => key,
    locale: "en",
    setLocale: vi.fn(),
  }),
}));

describe("ReferralBanner", () => {
  beforeEach(() => {
    sessionStorage.clear();
    vi.clearAllMocks();
  });

  it("isChecking=true → 不顯示 banner", () => {
    mockUseReferralGuard.mockReturnValue({
      isChecking: true,
      needsReferral: false,
    });

    render(<ReferralBanner />);

    expect(screen.queryByText("referral.bannerMessage")).not.toBeInTheDocument();
  });

  it("needsReferral=false → 不顯示 banner", () => {
    mockUseReferralGuard.mockReturnValue({
      isChecking: false,
      needsReferral: false,
    });

    render(<ReferralBanner />);

    expect(screen.queryByText("referral.bannerMessage")).not.toBeInTheDocument();
  });

  it("needsReferral=true → 顯示 banner，並可 dismiss", async () => {
    mockUseReferralGuard.mockReturnValue({
      isChecking: false,
      needsReferral: true,
    });
    const user = userEvent.setup();

    render(<ReferralBanner />);

    expect(screen.getByText("referral.bannerMessage")).toBeInTheDocument();
    expect(screen.getByText("referral.bannerAction")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Dismiss" }));
    expect(screen.queryByText("referral.bannerMessage")).not.toBeInTheDocument();
  });
});
