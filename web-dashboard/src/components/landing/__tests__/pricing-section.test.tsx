import { render, screen } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { PricingSection } from "../pricing-section";

// Mock i18n
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => {
      const mockTranslations: Record<string, string> = {
        "landing.pricingTitle": "Choose Your Trading Plan",
        "landing.pricingSubtitle":
          "Start free, upgrade when you're ready. No hidden fees, cancel anytime.",
        "landing.pricingStarter": "Starter",
        "landing.pricingBasic": "Basic",
        "landing.pricingPro": "Pro",
        "landing.pricingFree": "Free",
        "landing.pricingPerMonth": "/month",
        "landing.pricingTrialDays": "7-day free trial",
        "landing.pricingGetStarted": "Get Started Free",
        "landing.pricingSubscribe": "Start Trading",
        "landing.pricingStarterF1": "1 signal source",
        "landing.pricingStarterF2": "3 concurrent positions",
        "landing.pricingStarterF3": "Basic analytics dashboard",
        "landing.pricingBasicF1": "1 signal source",
        "landing.pricingBasicF2": "5 concurrent positions",
        "landing.pricingBasicF3": "Full analytics (20+ metrics)",
        "landing.pricingProF1": "Multiple signal sources",
        "landing.pricingProF2": "10 concurrent positions",
        "landing.pricingProF3": "Full analytics (20+ metrics)",
      };
      return mockTranslations[key] || key;
    },
    locale: "en",
  }),
}));

// Mock useRouter and usePathname
vi.mock("next/navigation", async () => {
  const actual = await vi.importActual("next/navigation");
  return {
    ...actual,
    useRouter: vi.fn(() => ({
      push: vi.fn(),
    })),
    usePathname: vi.fn(() => "/login"),
  };
});

// Mock useScrollReveal
vi.mock("@/hooks/use-scroll-reveal", () => ({
  useScrollReveal: () => ({
    current: null,
  }),
}));

describe("PricingSection", () => {
  it("renders without crashing", () => {
    render(<PricingSection />);
    expect(screen.getByText("Choose Your Trading Plan")).toBeInTheDocument();
  });

  it("renders pricing title and subtitle", () => {
    render(<PricingSection />);

    expect(screen.getByText("Choose Your Trading Plan")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Start free, upgrade when you're ready. No hidden fees, cancel anytime."
      )
    ).toBeInTheDocument();
  });

  it("renders exactly 3 pricing tiers", () => {
    render(<PricingSection />);

    expect(screen.getByText("Starter")).toBeInTheDocument();
    expect(screen.getByText("Basic")).toBeInTheDocument();
    expect(screen.getByText("Pro")).toBeInTheDocument();
  });

  it("displays correct pricing for tiers", () => {
    render(<PricingSection />);

    // Starter is free
    expect(screen.getByText("Free")).toBeInTheDocument();

    // Basic is 99 USDT
    expect(screen.getByText("99 USDT")).toBeInTheDocument();

    // Pro is 199 USDT
    expect(screen.getByText("199 USDT")).toBeInTheDocument();
  });

  it("renders free trial text for starter tier", () => {
    render(<PricingSection />);

    expect(screen.getByText("7-day free trial")).toBeInTheDocument();
  });

  it("renders /month text for paid tiers", () => {
    render(<PricingSection />);

    const perMonthTexts = screen.getAllByText("/month");
    expect(perMonthTexts.length).toBeGreaterThanOrEqual(2);
  });

  it("renders features for all pricing tiers", () => {
    render(<PricingSection />);

    // Starter features
    expect(screen.getByText("1 signal source")).toBeInTheDocument();
    expect(screen.getByText("3 concurrent positions")).toBeInTheDocument();
    expect(screen.getByText("Basic analytics dashboard")).toBeInTheDocument();

    // Basic features
    expect(screen.getByText("5 concurrent positions")).toBeInTheDocument();
    expect(screen.getAllByText("Full analytics (20+ metrics)").length).toBeGreaterThanOrEqual(1);

    // Pro features
    expect(screen.getByText("Multiple signal sources")).toBeInTheDocument();
    expect(screen.getByText("10 concurrent positions")).toBeInTheDocument();
  });

  it("renders CTA buttons for all tiers", () => {
    render(<PricingSection />);

    // Starter CTA
    const startedFreeButtons = screen.getAllByText("Get Started Free");
    expect(startedFreeButtons.length).toBeGreaterThan(0);

    // Basic and Pro CTAs
    const startTradingButtons = screen.getAllByText("Start Trading");
    expect(startTradingButtons.length).toBeGreaterThanOrEqual(2);
  });

  it("renders section with correct id for scroll navigation", () => {
    const { container } = render(<PricingSection />);

    const section = container.querySelector("section");
    expect(section).toHaveAttribute("id", "pricing");
  });

  it("renders pricing cards with rounded corners", () => {
    const { container } = render(<PricingSection />);

    const cards = container.querySelectorAll('[class*="rounded-[30px]"]');
    expect(cards.length).toBeGreaterThanOrEqual(3);
  });

  it("displays check marks for features", () => {
    const { container } = render(<PricingSection />);

    // SVG Check components should be rendered
    const svgs = container.querySelectorAll("svg");
    expect(svgs.length).toBeGreaterThan(0);
  });
});
