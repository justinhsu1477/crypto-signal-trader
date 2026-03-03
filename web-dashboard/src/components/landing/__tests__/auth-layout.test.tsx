import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { AuthLayout } from "../auth-layout";

// Mock i18n
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => {
      const mockTranslations: Record<string, string> = {
        "landing.backToIntro": "Back to intro",
        "landing.heroTitle1": "Trade Crypto with",
        "landing.heroTitle2": "AI-Powered Signals",
        "landing.heroDescription":
          "Integrate signals from top communities, auto-execute trades on Binance.",
        "landing.startButton": "Start Trading",
        "landing.heroLearnMore": "Learn More",
        "landing.startHintLogin": "Already have an account? Sign in here.",
        "landing.startHintRegister": "New to HookFi? Sign up for free.",
        "landing.featuresTitle": "Features",
        "landing.featureAutoExecTitle": "Auto Execution",
        "landing.featureRiskMgmtTitle": "Risk Management",
        "landing.featureDcaTitle": "DCA Support",
        "landing.featureAnalyticsTitle": "Analytics",
        "landing.aboutBadge": "About",
        "landing.pricingBadge": "Pricing",
        "landing.featureSecurityTitle": "Security",
        "landing.footer": "Smart Crypto Trading Platform",
      };
      return mockTranslations[key] || key;
    },
    locale: "en",
  }),
}));

// Mock usePathname + useSearchParams
vi.mock("next/navigation", async () => {
  const actual = await vi.importActual("next/navigation");
  return {
    ...actual,
    usePathname: vi.fn(() => "/login"),
    useSearchParams: vi.fn(() => new URLSearchParams()),
  };
});

// Mock child components to focus on layout structure
vi.mock("../public-navbar", () => ({
  PublicNavbar: () => <div data-testid="public-navbar">Navbar</div>,
}));

vi.mock("../crypto-background", () => ({
  CryptoBackground: () => <div data-testid="crypto-background">Background</div>,
}));

vi.mock("../hero-orbit-visual", () => ({
  HeroOrbitVisual: () => <div data-testid="hero-orbit-visual">Logo</div>,
}));

vi.mock("../stats-bar-section", () => ({
  StatsBarSection: () => <div data-testid="stats-bar-section">Stats</div>,
}));

vi.mock("../pricing-section", () => ({
  PricingSection: () => <div data-testid="pricing-section">Pricing</div>,
}));

vi.mock("../features-section", () => ({
  FeaturesSection: () => <div data-testid="features-section">Features</div>,
}));

vi.mock("../how-it-works-section", () => ({
  HowItWorksSection: () => <div data-testid="how-it-works-section">Security</div>,
}));

describe("AuthLayout", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders without crashing", () => {
    render(<AuthLayout><div>Test Child</div></AuthLayout>);
    expect(screen.getByTestId("public-navbar")).toBeInTheDocument();
  });

  it("renders all major sections", () => {
    render(<AuthLayout><div>Test Child</div></AuthLayout>);

    expect(screen.getByTestId("crypto-background")).toBeInTheDocument();
    expect(screen.getByTestId("public-navbar")).toBeInTheDocument();
    expect(screen.getByTestId("hero-orbit-visual")).toBeInTheDocument();
    expect(screen.getByTestId("stats-bar-section")).toBeInTheDocument();
    expect(screen.getByTestId("pricing-section")).toBeInTheDocument();
    expect(screen.getByTestId("features-section")).toBeInTheDocument();
    expect(screen.getByTestId("how-it-works-section")).toBeInTheDocument();
  });

  it("renders hero title and description", () => {
    render(<AuthLayout><div>Test Child</div></AuthLayout>);

    expect(screen.getByText("Trade Crypto with")).toBeInTheDocument();
    expect(screen.getByText("AI-Powered Signals")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Integrate signals from top communities, auto-execute trades on Binance."
      )
    ).toBeInTheDocument();
  });

  it("renders CTA buttons", () => {
    render(<AuthLayout><div>Test Child</div></AuthLayout>);

    const buttons = screen.getAllByRole("button");
    expect(buttons.length).toBeGreaterThan(0);
    expect(screen.getByText("Start Trading")).toBeInTheDocument();
  });

  it("renders footer with logo and copyright", () => {
    render(<AuthLayout><div>Test Child</div></AuthLayout>);

    expect(screen.getByText("HookFi")).toBeInTheDocument();
    expect(screen.getByText("Smart Crypto Trading Platform")).toBeInTheDocument();
    const footer = screen.getByText(/All rights reserved/);
    expect(footer).toBeInTheDocument();
  });

  it("renders footer navigation links", () => {
    const { container } = render(<AuthLayout><div>Test Child</div></AuthLayout>);

    // Footer sections — scope to <footer> to avoid collision with mock section components
    const footer = container.querySelector("footer") as HTMLElement;
    expect(footer).toBeInTheDocument();

    // "Features" and "Pricing" also appear in mock section components,
    // so we verify they exist within the footer element specifically.
    const footerHeadings = footer.querySelectorAll("h4");
    const headingTexts = Array.from(footerHeadings).map((h) => h.textContent);
    expect(headingTexts).toContain("Features");
    expect(headingTexts).toContain("About");
    expect(headingTexts).toContain("Contact");

    // "Pricing" is a link inside the footer, not a heading
    const pricingLink = footer.querySelector('a[href="#pricing"]');
    expect(pricingLink).toBeInTheDocument();
    expect(pricingLink?.textContent).toBe("Pricing");
  });

  it("renders contact information in footer", () => {
    render(<AuthLayout><div>Test Child</div></AuthLayout>);

    expect(screen.getByText("support@hook-fi.com")).toBeInTheDocument();
    expect(screen.getByText("LINE")).toBeInTheDocument();
    expect(screen.getByText("Discord")).toBeInTheDocument();
  });

  it("renders privacy and terms links", () => {
    render(<AuthLayout><div>Test Child</div></AuthLayout>);

    const privacyLink = screen.getByText("Privacy Policy");
    const termsLink = screen.getByText("Terms of Service");
    expect(privacyLink).toBeInTheDocument();
    expect(termsLink).toBeInTheDocument();
  });

  it("renders children after clicking Start Trading", () => {
    render(<AuthLayout><div>Test Auth Content</div></AuthLayout>);

    // Initially, hero text is shown (showAuthCard is false), children are not visible
    expect(screen.queryByText("Test Auth Content")).not.toBeInTheDocument();

    // Click "Start Trading" to switch to auth card view
    fireEvent.click(screen.getByText("Start Trading"));

    // Now children should be visible
    expect(screen.getByText("Test Auth Content")).toBeInTheDocument();
  });

  it("has correct background color", () => {
    const { container } = render(<AuthLayout><div>Test</div></AuthLayout>);

    const mainDiv = container.firstChild as HTMLElement;
    expect(mainDiv).toHaveStyle("background: rgb(255, 248, 247)");
  });
});
