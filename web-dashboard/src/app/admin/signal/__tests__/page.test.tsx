import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach } from "vitest";
import AdminSignalPage from "../page";

// Translation map
const translations: Record<string, string> = {
  "adminSignal.title": "Emergency Signal",
  "adminSignal.description": "Manually broadcast trading signals",
  "adminSignal.symbol": "Symbol",
  "adminSignal.side": "Side",
  "adminSignal.entryPrice": "Entry Price",
  "adminSignal.stopLoss": "Stop Loss",
  "adminSignal.takeProfit": "Take Profit",
  "adminSignal.closeRatio": "Close Ratio",
  "adminSignal.newStopLoss": "New Stop Loss",
  "adminSignal.newTakeProfit": "New Take Profit",
  "adminSignal.isDca": "DCA",
  "adminSignal.confirm": "Broadcast",
  "adminSignal.confirmTitle": "Confirm Broadcast",
  "adminSignal.confirmMessage": "Broadcasting signal to all users:",
  "adminSignal.confirmBroadcast": "Confirm",
  "adminSignal.sending": "Sending...",
  "adminSignal.success": "Broadcast complete",
  "adminSignal.failed": "Broadcast failed",
  "adminSignal.skippedSignal": "Signal skipped",
  "adminSignal.resultUsers": "Success: {success} / Failed: {fail}",
  "adminSignal.skipped": "Skipped(no sub): {noSub} / Skipped(no key): {noKey}",
  "adminSignal.cancelDesc": "Cancel pending order for this symbol",
  "common.cancel": "Cancel",
};

vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => translations[key] || key,
    locale: "en",
  }),
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    email: "admin@test.com",
    role: "ADMIN",
  }),
}));

vi.mock("@/lib/api", () => ({
  adminBroadcastTrade: vi.fn(),
}));

vi.mock("sonner", () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
  },
}));

describe("AdminSignalPage", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe("Broadcast Button", () => {
    it("renders broadcast button in header area (not full-width)", () => {
      render(<AdminSignalPage />);
      const button = screen.getByRole("button", { name: /Broadcast/i });
      expect(button).toBeInTheDocument();

      // Button should NOT have w-full class (moved to header, compact size)
      expect(button.className).not.toContain("w-full");
    });

    it("button is enabled with valid CLOSE form (default state)", () => {
      render(<AdminSignalPage />);
      const button = screen.getByRole("button", { name: /Broadcast/i });
      // CLOSE action + symbol selected = valid
      expect(button).not.toBeDisabled();
    });
  });

  describe("Close Ratio Input", () => {
    it("renders number input instead of range slider", () => {
      render(<AdminSignalPage />);
      // Should have a number input for close ratio
      const numberInput = screen.getByDisplayValue("100");
      expect(numberInput).toBeInTheDocument();
      expect(numberInput).toHaveAttribute("type", "number");

      // Should NOT have a range input
      const rangeInputs = document.querySelectorAll('input[type="range"]');
      expect(rangeInputs).toHaveLength(0);
    });

    it("default value is 100", () => {
      render(<AdminSignalPage />);
      const input = screen.getByDisplayValue("100");
      expect(input).toBeInTheDocument();
    });

    it("shows % suffix", () => {
      render(<AdminSignalPage />);
      expect(screen.getByText("%")).toBeInTheDocument();
    });

    it("has min and max constraints", () => {
      render(<AdminSignalPage />);
      const input = screen.getByDisplayValue("100") as HTMLInputElement;
      expect(input).toHaveAttribute("min", "1");
      expect(input).toHaveAttribute("max", "100");
      expect(input).toHaveAttribute("step", "1");
    });
  });

  describe("Action Tabs", () => {
    it("renders all 4 action tabs", () => {
      render(<AdminSignalPage />);
      expect(screen.getByRole("tab", { name: "CLOSE" })).toBeInTheDocument();
      expect(screen.getByRole("tab", { name: "ENTRY" })).toBeInTheDocument();
      expect(screen.getByRole("tab", { name: "MOVE SL" })).toBeInTheDocument();
      expect(screen.getByRole("tab", { name: "CANCEL" })).toBeInTheDocument();
    });

    it("CLOSE tab is default active", () => {
      render(<AdminSignalPage />);
      const closeTab = screen.getByRole("tab", { name: "CLOSE" });
      expect(closeTab).toHaveAttribute("data-state", "active");
    });

    it("shows ENTRY fields when ENTRY tab clicked", async () => {
      const user = userEvent.setup();
      render(<AdminSignalPage />);

      await user.click(screen.getByRole("tab", { name: "ENTRY" }));

      expect(screen.getByText(/Entry Price/)).toBeInTheDocument();
      expect(screen.getByText(/Stop Loss/)).toBeInTheDocument();
      expect(screen.getByText(/Take Profit/)).toBeInTheDocument();
      expect(screen.getByText("LONG")).toBeInTheDocument();
      expect(screen.getByText("SHORT")).toBeInTheDocument();
      expect(screen.getByText("DCA")).toBeInTheDocument();
    });

    it("shows MOVE_SL fields when MOVE SL tab clicked", async () => {
      const user = userEvent.setup();
      render(<AdminSignalPage />);

      await user.click(screen.getByRole("tab", { name: "MOVE SL" }));

      expect(screen.getByText(/New Stop Loss/)).toBeInTheDocument();
      expect(screen.getByText(/New Take Profit/)).toBeInTheDocument();
    });

    it("shows cancel description when CANCEL tab clicked", async () => {
      const user = userEvent.setup();
      render(<AdminSignalPage />);

      await user.click(screen.getByRole("tab", { name: "CANCEL" }));

      expect(screen.getByText("Cancel pending order for this symbol")).toBeInTheDocument();
    });
  });

  describe("Symbol Selector", () => {
    it("renders symbol dropdown with BTCUSDT as default", () => {
      render(<AdminSignalPage />);
      const select = screen.getByDisplayValue("BTCUSDT");
      expect(select).toBeInTheDocument();
    });

    it("contains common trading pairs", () => {
      render(<AdminSignalPage />);
      const options = screen.getAllByRole("option");
      const values = options.map((o) => o.getAttribute("value"));
      expect(values).toContain("BTCUSDT");
      expect(values).toContain("ETHUSDT");
      expect(values).toContain("SOLUSDT");
    });
  });

  describe("Page Header", () => {
    it("renders title and description", () => {
      render(<AdminSignalPage />);
      expect(screen.getByText("Emergency Signal")).toBeInTheDocument();
      expect(screen.getByText("Manually broadcast trading signals")).toBeInTheDocument();
    });
  });
});
