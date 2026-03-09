import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi } from "vitest";
import { TradingViewChart } from "../tradingview-chart";
import type { OpenPositionSummary } from "@/types";

// Mock i18n
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => {
      const map: Record<string, string> = {
        "dashboard.liveChart": "Live Chart",
      };
      return map[key] || key;
    },
    locale: "en",
  }),
}));

function buildPosition(symbol: string): OpenPositionSummary {
  return {
    symbol,
    side: "LONG",
    entryPrice: 50000,
    stopLoss: null,
    dcaCount: 0,
    entryTime: "2024-01-01T10:00:00",
    aiConfidence: null,
    aiReasoning: null,
    markPrice: null,
    unrealizedPnl: null,
    positionValue: null,
    marginUsed: null,
    entryQuantity: null,
  };
}

describe("TradingViewChart", () => {
  it("renders card title", () => {
    render(<TradingViewChart positions={[]} />);
    expect(screen.getByText("Live Chart")).toBeInTheDocument();
  });

  it("defaults to BTCUSDT when no positions", () => {
    render(<TradingViewChart positions={[]} />);
    const container = document.querySelector(".tradingview-widget-container");
    expect(container).toBeInTheDocument();

    const script = container?.querySelector("script");
    expect(script).toBeTruthy();
    expect(script?.src).toContain("embed-widget-advanced-chart.js");
    expect(script?.textContent).toContain("BINANCE:BTCUSDT");
  });

  it("uses first position symbol as default", () => {
    render(
      <TradingViewChart positions={[buildPosition("ETHUSDT")]} />,
    );
    const container = document.querySelector(".tradingview-widget-container");
    const script = container?.querySelector("script");
    expect(script?.textContent).toContain("BINANCE:ETHUSDT");
  });

  it("does not show tabs for single position", () => {
    render(
      <TradingViewChart positions={[buildPosition("BTCUSDT")]} />,
    );
    expect(screen.queryByRole("tablist")).not.toBeInTheDocument();
  });

  it("shows tabs for multiple positions", () => {
    render(
      <TradingViewChart
        positions={[buildPosition("BTCUSDT"), buildPosition("ETHUSDT")]}
      />,
    );
    expect(screen.getByRole("tablist")).toBeInTheDocument();
    expect(screen.getByText("BTC")).toBeInTheDocument();
    expect(screen.getByText("ETH")).toBeInTheDocument();
  });

  it("deduplicates symbols", () => {
    render(
      <TradingViewChart
        positions={[
          buildPosition("BTCUSDT"),
          buildPosition("BTCUSDT"),
          buildPosition("ETHUSDT"),
        ]}
      />,
    );
    const tabs = screen.getAllByRole("tab");
    expect(tabs).toHaveLength(2);
  });

  it("switches symbol when tab is clicked", async () => {
    const user = userEvent.setup();
    render(
      <TradingViewChart
        positions={[buildPosition("BTCUSDT"), buildPosition("ETHUSDT")]}
      />,
    );

    await user.click(screen.getByText("ETH"));

    const container = document.querySelector(".tradingview-widget-container");
    const script = container?.querySelector("script");
    expect(script?.textContent).toContain("BINANCE:ETHUSDT");
  });

  it("script config includes dark theme and timezone", () => {
    render(<TradingViewChart positions={[]} />);
    const container = document.querySelector(".tradingview-widget-container");
    const script = container?.querySelector("script");
    const config = JSON.parse(script?.textContent ?? "{}");

    expect(config.theme).toBe("dark");
    expect(config.timezone).toBe("Asia/Taipei");
    expect(config.interval).toBe("60");
  });

  it("cleans up on unmount", () => {
    const { unmount } = render(<TradingViewChart positions={[]} />);
    const container = document.querySelector(".tradingview-widget-container");
    expect(container?.childNodes.length).toBeGreaterThan(0);

    unmount();

    // After unmount, the container is removed from DOM entirely
    expect(document.querySelector(".tradingview-widget-container")).toBeNull();
  });
});
