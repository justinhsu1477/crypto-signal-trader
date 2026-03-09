import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { PositionsTable } from "../positions-table";
import type { OpenPositionSummary } from "@/types";

// Mock i18n
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string, params?: Record<string, string>) => {
      const map: Record<string, string> = {
        "dashboard.currentPositions": "Current Positions",
        "dashboard.noPositions": "No open positions",
        "dashboard.markPrice": "Mark Price",
        "dashboard.unrealizedPnl": "Unrealized P&L",
        "dashboard.positionValue": "Position Value",
        "dashboard.aiScore": "AI Score",
        "dashboard.actions": "Actions",
        "dashboard.closePosition": "Close Position",
        "dashboard.cancelOrders": "Cancel Orders",
        "dashboard.closeConfirmTitle": "Close Position?",
        "dashboard.closeConfirmDesc": `Close ${params?.symbol ?? ""} ${params?.side ?? ""} at market price?`,
        "dashboard.cancelConfirmTitle": "Cancel Orders?",
        "dashboard.cancelConfirmDesc": `Cancel all orders for ${params?.symbol ?? ""}?`,
        "dashboard.positionClosed": "Position closed",
        "dashboard.ordersCancelled": "Orders cancelled",
        "dashboard.actionFailed": "Action failed",
        "common.cancel": "Cancel",
        "common.confirm": "Confirm",
      };
      return map[key] || key;
    },
    locale: "en",
  }),
}));

// Mock API
const mockClosePosition = vi.fn();
const mockCancelOrders = vi.fn();
vi.mock("@/lib/api", () => ({
  closePosition: (...args: unknown[]) => mockClosePosition(...args),
  cancelOrders: (...args: unknown[]) => mockCancelOrders(...args),
}));

// Mock toast
const mockToastSuccess = vi.fn();
const mockToastError = vi.fn();
vi.mock("sonner", () => ({
  toast: {
    success: (...args: unknown[]) => mockToastSuccess(...args),
    error: (...args: unknown[]) => mockToastError(...args),
  },
}));

function buildPosition(overrides: Partial<OpenPositionSummary> = {}): OpenPositionSummary {
  return {
    symbol: "BTCUSDT",
    side: "LONG",
    entryPrice: 50000,
    stopLoss: 48000,
    dcaCount: 2,
    entryTime: "2024-01-01T10:00:00",
    aiConfidence: 85,
    aiReasoning: "Strong trend",
    markPrice: 51000,
    unrealizedPnl: 100,
    positionValue: 5100,
    marginUsed: 500,
    entryQuantity: 0.1,
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockClosePosition.mockResolvedValue({ status: "ok" });
  mockCancelOrders.mockResolvedValue({ status: "ok" });
});

describe("PositionsTable", () => {
  it("renders empty state when no positions", () => {
    render(<PositionsTable positions={[]} />);
    expect(screen.getByText("No open positions")).toBeInTheDocument();
  });

  it("renders position rows with correct data", () => {
    const position = buildPosition();
    render(<PositionsTable positions={[position]} />);

    expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
    expect(screen.getByText("LONG")).toBeInTheDocument();
    expect(screen.getByText("AI 85")).toBeInTheDocument();
  });

  it("renders table headers including new columns", () => {
    render(<PositionsTable positions={[buildPosition()]} />);

    expect(screen.getByText("Mark Price")).toBeInTheDocument();
    expect(screen.getByText("Unrealized P&L")).toBeInTheDocument();
    expect(screen.getByText("Position Value")).toBeInTheDocument();
    expect(screen.getByText("AI Score")).toBeInTheDocument();
    expect(screen.getByText("Actions")).toBeInTheDocument();
  });

  it("renders action buttons for each position", () => {
    render(<PositionsTable positions={[buildPosition()]} />);

    expect(screen.getByTitle("Close Position")).toBeInTheDocument();
    expect(screen.getByTitle("Cancel Orders")).toBeInTheDocument();
  });

  describe("Close Position", () => {
    it("shows confirmation dialog when close button clicked", async () => {
      const user = userEvent.setup();
      render(<PositionsTable positions={[buildPosition()]} />);

      await user.click(screen.getByTitle("Close Position"));

      expect(screen.getByText("Close Position?")).toBeInTheDocument();
      expect(screen.getByText(/Close BTCUSDT LONG at market price/)).toBeInTheDocument();
    });

    it("calls closePosition API on confirm", async () => {
      const user = userEvent.setup();
      const onRefresh = vi.fn();
      render(<PositionsTable positions={[buildPosition()]} onRefresh={onRefresh} />);

      await user.click(screen.getByTitle("Close Position"));
      await user.click(screen.getByText("Confirm"));

      await waitFor(() => {
        expect(mockClosePosition).toHaveBeenCalledWith("BTCUSDT", "LONG");
      });
      await waitFor(() => {
        expect(mockToastSuccess).toHaveBeenCalledWith("Position closed");
      });
      await waitFor(() => {
        expect(onRefresh).toHaveBeenCalledOnce();
      });
    });

    it("shows error toast on API failure", async () => {
      mockClosePosition.mockRejectedValueOnce(new Error("Network error"));
      const user = userEvent.setup();
      render(<PositionsTable positions={[buildPosition()]} />);

      await user.click(screen.getByTitle("Close Position"));
      await user.click(screen.getByText("Confirm"));

      await waitFor(() => {
        expect(mockToastError).toHaveBeenCalledWith("Action failed: Network error");
      });
    });
  });

  describe("Cancel Orders", () => {
    it("shows confirmation dialog when cancel button clicked", async () => {
      const user = userEvent.setup();
      render(<PositionsTable positions={[buildPosition()]} />);

      await user.click(screen.getByTitle("Cancel Orders"));

      expect(screen.getByText("Cancel Orders?")).toBeInTheDocument();
      expect(screen.getByText(/Cancel all orders for BTCUSDT/)).toBeInTheDocument();
    });

    it("calls cancelOrders API on confirm", async () => {
      const user = userEvent.setup();
      const onRefresh = vi.fn();
      render(<PositionsTable positions={[buildPosition()]} onRefresh={onRefresh} />);

      await user.click(screen.getByTitle("Cancel Orders"));
      await user.click(screen.getByText("Confirm"));

      await waitFor(() => {
        expect(mockCancelOrders).toHaveBeenCalledWith("BTCUSDT");
      });
      await waitFor(() => {
        expect(mockToastSuccess).toHaveBeenCalledWith("Orders cancelled");
      });
      await waitFor(() => {
        expect(onRefresh).toHaveBeenCalledOnce();
      });
    });
  });

  describe("Dialog cancel", () => {
    it("closes dialog when cancel is clicked", async () => {
      const user = userEvent.setup();
      render(<PositionsTable positions={[buildPosition()]} />);

      // Open dialog
      await user.click(screen.getByTitle("Close Position"));
      expect(screen.getByText("Close Position?")).toBeInTheDocument();

      // Click Cancel button inside dialog
      await user.click(screen.getByText("Cancel"));
      await waitFor(() => {
        expect(screen.queryByText("Close Position?")).not.toBeInTheDocument();
      });

      // API should not be called
      expect(mockClosePosition).not.toHaveBeenCalled();
    });
  });

  describe("Multiple positions", () => {
    it("renders multiple rows", () => {
      const positions = [
        buildPosition({ symbol: "BTCUSDT" }),
        buildPosition({ symbol: "ETHUSDT", side: "SHORT" }),
      ];
      render(<PositionsTable positions={positions} />);

      expect(screen.getByText("BTCUSDT")).toBeInTheDocument();
      expect(screen.getByText("ETHUSDT")).toBeInTheDocument();
      expect(screen.getByText("SHORT")).toBeInTheDocument();
    });
  });

  describe("Null values", () => {
    it("renders em-dash for null mark price", () => {
      render(
        <PositionsTable positions={[buildPosition({ markPrice: null })]} />,
      );
      // The em-dash "—" should be rendered somewhere in the row
      const cells = screen.getAllByRole("cell");
      const hasDash = cells.some((cell) => cell.textContent === "\u2014");
      expect(hasDash).toBe(true);
    });
  });
});
