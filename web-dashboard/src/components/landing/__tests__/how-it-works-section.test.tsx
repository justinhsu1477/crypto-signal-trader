import { render, screen } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { HowItWorksSection } from "../how-it-works-section";

// Mock i18n
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => {
      const mockTranslations: Record<string, string> = {
        "landing.featureSecurityTitle": "Secure & Reliable",
        "landing.securityWord": "SECURITY",
        "landing.securityNonCustodialIntro":
          "Non-custodial architecture keeps your funds safe.",
        "landing.securityProtectedTitle": "Protected by Industry Standards",
        "landing.featureNonCustodialDesc":
          "Your funds stay in your Binance account. API keys are encrypted with AES-256. We never have access to withdraw your assets.",
        "landing.aboutTrustEncrypted": "AES-256 Encrypted",
        "landing.aboutTrustNonCustodial": "Non-Custodial",
        "landing.aboutTrustBinance": "Binance Integration",
        "landing.aboutTrustUptime": "24/7 Uptime",
      };
      return mockTranslations[key] || key;
    },
    locale: "en",
  }),
}));

// Mock useScrollReveal
vi.mock("@/hooks/use-scroll-reveal", () => ({
  useScrollReveal: () => ({
    current: null,
  }),
}));

describe("HowItWorksSection", () => {
  it("renders without crashing", () => {
    render(<HowItWorksSection />);
    expect(screen.getByText("Secure & Reliable")).toBeInTheDocument();
  });

  it("renders section title and large security word", () => {
    render(<HowItWorksSection />);

    expect(screen.getByText("Secure & Reliable")).toBeInTheDocument();
    expect(screen.getByText("SECURITY")).toBeInTheDocument();
  });

  it("renders security introduction text", () => {
    render(<HowItWorksSection />);

    expect(screen.getByText("Non-custodial architecture keeps your funds safe.")).toBeInTheDocument();
  });

  it("renders protected title and description", () => {
    render(<HowItWorksSection />);

    expect(screen.getByText("Protected by Industry Standards")).toBeInTheDocument();
    expect(
      screen.getByText(
        /Your funds stay in your Binance account. API keys are encrypted with AES-256/
      )
    ).toBeInTheDocument();
  });

  it("renders exactly 4 trust badges", () => {
    render(<HowItWorksSection />);

    expect(screen.getByText("AES-256 Encrypted")).toBeInTheDocument();
    expect(screen.getByText("Non-Custodial")).toBeInTheDocument();
    expect(screen.getByText("Binance Integration")).toBeInTheDocument();
    expect(screen.getByText("24/7 Uptime")).toBeInTheDocument();
  });

  it("renders trust badges with emoji icons", () => {
    const { container } = render(<HowItWorksSection />);

    // Check for emoji in badges
    const badgeTexts = container.querySelectorAll('[class*="rounded-2xl"]');
    expect(badgeTexts.length).toBeGreaterThanOrEqual(4);
  });

  it("renders section with correct id for scroll navigation", () => {
    const { container } = render(<HowItWorksSection />);

    const section = container.querySelector("section");
    expect(section).toHaveAttribute("id", "security");
  });

  it("renders trust card with proper layout", () => {
    const { container } = render(<HowItWorksSection />);

    const trustCard = container.querySelector('[class*="rounded-[30px]"]');
    expect(trustCard).toBeInTheDocument();
    expect(trustCard).toHaveClass("bg-white");
  });

  it("renders security illustration SVG", () => {
    const { container } = render(<HowItWorksSection />);

    const svg = container.querySelector("svg");
    expect(svg).toBeInTheDocument();
  });

  it("renders badges with border styling", () => {
    const { container } = render(<HowItWorksSection />);

    const badges = container.querySelectorAll('[class*="border-black"]');
    expect(badges.length).toBeGreaterThanOrEqual(4);
  });

  it("has correct background with gradient", () => {
    const { container } = render(<HowItWorksSection />);

    const section = container.querySelector("section") as HTMLElement;
    expect(section.style.background).toContain("radial-gradient");
  });
});
