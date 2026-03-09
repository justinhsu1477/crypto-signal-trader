import { render, screen } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { PortfolioAllocation } from "../portfolio-allocation";
import type { DashboardOverview } from "@/types";

// Mock i18n
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => {
      const map: Record<string, string> = {
        "dashboard.portfolioAllocation": "Portfolio Allocation",
        "dashboard.marginUsage": "Margin Usage",
        "dashboard.marginUsed": "Margin Used",
        "dashboard.available": "Available",
        "dashboard.noPositions": "No open positions",
      };
      return map[key] || key;
    },
    locale: "en",
  }),
}));

// Mock Recharts — renders children as-is for testing
vi.mock("recharts", () => ({
  PieChart: ({ children }: { children: React.ReactNode }) => <div data-testid="pie-chart">{children}</div>,
  Pie: ({ children, data }: { children: React.ReactNode; data: Array<{ name: string }> }) => (
    <div data-testid="pie">{data?.map((d) => <span key={d.name}>{d.name}</span>)}{children}</div>
  ),
  Cell: () => <div data-testid="cell" />,
  Tooltip: () => <div data-testid="tooltip" />,
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  Legend: () => <div data-testid="legend" />,
}));

function buildData(overrides: Partial<{
  positions: DashboardOverview["positions"];
  totalMarginUsed: number;
  marginRatio: number;
  availableBalance: number;
}>): DashboardOverview {
  return {
    account: {
      totalBalance: 10000,
      availableBalance: overrides.availableBalance ?? 8000,
      todayPnl: 100,
      todayPnlPercent: 1.0,
      totalPnl: 500,
      totalTrades: 20,
      winRate: 65,
      activePositions: overrides.positions?.length ?? 0,
      totalMarginUsed: overrides.totalMarginUsed ?? 2000,
      marginRatio: overrides.marginRatio ?? 20,
    },
    riskBudget: {
      dailyBudget: 100,
      usedBudget: 30,
      remainingBudget: 70,
      budgetPercent: 30,
      circuitBreakerActive: false,
    },
    positions: overrides.positions ?? [],
  } as DashboardOverview;
}

describe("PortfolioAllocation", () => {
  describe("Pie Chart", () => {
    it("renders 'No open positions' when positions are empty", () => {
      render(<PortfolioAllocation data={buildData({ positions: [] })} />);
      expect(screen.getByText("No open positions")).toBeInTheDocument();
      expect(screen.queryByTestId("pie-chart")).not.toBeInTheDocument();
    });

    it("renders pie chart when positions have values", () => {
      const positions = [
        { symbol: "BTCUSDT", positionValue: 5000 },
        { symbol: "ETHUSDT", positionValue: 3000 },
      ] as DashboardOverview["positions"];

      render(<PortfolioAllocation data={buildData({ positions })} />);
      expect(screen.getByTestId("pie-chart")).toBeInTheDocument();
      expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
      expect(screen.getByText("ETHUSDT")).toBeInTheDocument();
      expect(screen.queryByText("No open positions")).not.toBeInTheDocument();
    });

    it("filters out positions with null or zero positionValue", () => {
      const positions = [
        { symbol: "BTCUSDT", positionValue: 5000 },
        { symbol: "XRPUSDT", positionValue: null },
        { symbol: "DOGEUSDT", positionValue: 0 },
      ] as DashboardOverview["positions"];

      render(<PortfolioAllocation data={buildData({ positions })} />);
      expect(screen.getByTestId("pie-chart")).toBeInTheDocument();
      expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
      expect(screen.queryByText("XRPUSDT")).not.toBeInTheDocument();
      expect(screen.queryByText("DOGEUSDT")).not.toBeInTheDocument();
    });
  });

  describe("Margin Usage", () => {
    it("displays margin used amount", () => {
      render(
        <PortfolioAllocation
          data={buildData({ totalMarginUsed: 2500, marginRatio: 25 })}
        />,
      );
      expect(screen.getByText("Margin Used")).toBeInTheDocument();
      expect(screen.getByText("Margin Usage")).toBeInTheDocument();
    });

    it("renders section titles", () => {
      render(<PortfolioAllocation data={buildData({})} />);
      expect(screen.getByText("Portfolio Allocation")).toBeInTheDocument();
      expect(screen.getByText("Margin Usage")).toBeInTheDocument();
    });

    it("displays margin ratio percentage", () => {
      render(
        <PortfolioAllocation
          data={buildData({ marginRatio: 45.5 })}
        />,
      );
      expect(screen.getByText("45.5%")).toBeInTheDocument();
    });

    it("displays available balance label", () => {
      render(
        <PortfolioAllocation
          data={buildData({ availableBalance: 7500 })}
        />,
      );
      expect(screen.getByText(/Available/)).toBeInTheDocument();
    });
  });
});
