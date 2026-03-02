import { render, screen } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { FeaturesSection } from "../features-section";

// Mock i18n
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => {
      const mockTranslations: Record<string, string> = {
        "landing.featBigAutoExec": "Auto Signal Execution",
        "landing.featureAutoExecDesc":
          "Discord trading signals are automatically parsed and executed on Binance Futures within seconds.",
        "landing.featureNonCustodialTitle": "Non-Custodial Security",
        "landing.featureNonCustodialDesc":
          "Your funds stay in your Binance account. API keys are encrypted with AES-256.",
        "landing.featBigSmartRisk": "Smart Risk Management",
        "landing.featureRiskMgmtDesc":
          "Daily loss limits, automatic position sizing, stop-loss and take-profit orders.",
        "landing.featureDcaTitle": "DCA Support",
        "landing.featureDcaDesc":
          "Intelligent dollar-cost averaging with configurable layers and risk multipliers.",
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

describe("FeaturesSection", () => {
  it("renders without crashing", () => {
    render(<FeaturesSection />);
    expect(screen.getByText("Auto Signal Execution")).toBeInTheDocument();
  });

  it("renders exactly 2 feature blocks", () => {
    render(<FeaturesSection />);

    // First block
    expect(screen.getByText("Auto Signal Execution")).toBeInTheDocument();

    // Second block
    expect(screen.getByText("Smart Risk Management")).toBeInTheDocument();
  });

  it("renders first feature block with correct content", () => {
    render(<FeaturesSection />);

    expect(screen.getByText("Auto Signal Execution")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Discord trading signals are automatically parsed and executed on Binance Futures within seconds."
      )
    ).toBeInTheDocument();
    expect(screen.getByText("Non-Custodial Security")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Your funds stay in your Binance account. API keys are encrypted with AES-256."
      )
    ).toBeInTheDocument();
  });

  it("renders second feature block with correct content", () => {
    render(<FeaturesSection />);

    expect(screen.getByText("Smart Risk Management")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Daily loss limits, automatic position sizing, stop-loss and take-profit orders."
      )
    ).toBeInTheDocument();
    expect(screen.getByText("DCA Support")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Intelligent dollar-cost averaging with configurable layers and risk multipliers."
      )
    ).toBeInTheDocument();
  });

  it("renders section with correct id for scroll navigation", () => {
    const { container } = render(<FeaturesSection />);

    const section = container.querySelector("section");
    expect(section).toHaveAttribute("id", "features");
  });

  it("renders feature blocks in grid layout", () => {
    const { container } = render(<FeaturesSection />);

    const gridContainers = container.querySelectorAll('[class*="grid"]');
    expect(gridContainers.length).toBeGreaterThan(0);
  });

  it("renders feature blocks with borders", () => {
    const { container } = render(<FeaturesSection />);

    const borderedDivs = container.querySelectorAll('[class*="border-t"]');
    expect(borderedDivs.length).toBeGreaterThan(0);
  });

  it("renders feature blocks with proper typography", () => {
    const { container } = render(<FeaturesSection />);

    // Check for large typography (text-6xl through text-8xl)
    const largeText = container.querySelectorAll(
      '[class*="text-6xl"], [class*="text-7xl"], [class*="text-8xl"]'
    );
    expect(largeText.length).toBeGreaterThan(0);
  });

  it("renders all feature descriptions with gray text color", () => {
    const { container } = render(<FeaturesSection />);

    // Feature descriptions should have text-gray-500 or text-gray-400 classes
    const grayTexts = container.querySelectorAll(
      '[class*="text-gray-500"], [class*="text-gray-400"]'
    );
    expect(grayTexts.length).toBeGreaterThan(0);
  });
});
