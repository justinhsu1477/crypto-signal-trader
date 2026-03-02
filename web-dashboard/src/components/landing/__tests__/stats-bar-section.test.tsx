import { render, screen } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { StatsBarSection } from "../stats-bar-section";

// Mock i18n
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => {
      const mockTranslations: Record<string, string> = {
        "landing.statsBarTrades": "Total Trades Executed",
        "landing.statsBarSpeed": "Signal Execution",
        "landing.statsBarWinRate": "Average Win Rate",
      };
      return mockTranslations[key] || key;
    },
    locale: "en",
  }),
}));

describe("StatsBarSection", () => {
  it("renders without crashing", () => {
    render(<StatsBarSection />);
    expect(screen.getByText("Total Trades Executed")).toBeInTheDocument();
  });

  it("renders all 3 stats", () => {
    render(<StatsBarSection />);

    expect(screen.getByText("24/7")).toBeInTheDocument();
    expect(screen.getByText("<1s")).toBeInTheDocument();
    expect(screen.getByText("AES-256")).toBeInTheDocument();
  });

  it("renders stat labels", () => {
    render(<StatsBarSection />);

    expect(screen.getByText("Total Trades Executed")).toBeInTheDocument();
    expect(screen.getByText("Signal Execution")).toBeInTheDocument();
    expect(screen.getByText("Average Win Rate")).toBeInTheDocument();
  });

  it("displays correct stat values", () => {
    render(<StatsBarSection />);

    expect(screen.getByText("24/7")).toBeInTheDocument();
    expect(screen.getByText("<1s")).toBeInTheDocument();
    expect(screen.getByText("AES-256")).toBeInTheDocument();
  });

  it("renders stats in flex container", () => {
    const { container } = render(<StatsBarSection />);

    const flexContainer = container.querySelector('[class*="flex"]');
    expect(flexContainer?.className).toContain("items-center");
    expect(flexContainer?.className).toContain("gap");
  });

  it("renders dividers between stats", () => {
    const { container } = render(<StatsBarSection />);

    const dividers = container.querySelectorAll('div[class*="w-px"]');
    // Should have dividers between 3 stats
    expect(dividers.length).toBeGreaterThanOrEqual(1);
  });

  it("renders stat values with bold font weight", () => {
    const { container } = render(<StatsBarSection />);

    const boldValues = container.querySelectorAll('[class*="font-bold"]');
    expect(boldValues.length).toBeGreaterThan(0);
  });

  it("renders stat labels with small text", () => {
    const { container } = render(<StatsBarSection />);

    const smallTexts = container.querySelectorAll('[class*="text-xs"]');
    expect(smallTexts.length).toBeGreaterThanOrEqual(3);
  });
});
