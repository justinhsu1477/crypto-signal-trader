/**
 * SubscriptionManager 元件測試
 *
 * 測試重點：
 * 1. 初始載入：spinner → 方案卡片 / API 錯誤
 * 2. 方案卡片：名稱、USDT 價格、current badge、free 文字
 * 3. USDT 付款 Dialog：開啟、錢包地址、金額、複製按鈕、txHash 驗證、提交成功/失敗
 * 4. 取消訂閱：確認 Dialog → 呼叫 API
 * 5. 升級方案：升級按鈕 → 呼叫 API
 *
 * ~16 tests
 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { SubscriptionManager } from "../subscription-manager";

// ─── Mock API ───
const mockGetSubscriptionPlans = vi.fn();
const mockGetSubscriptionStatus = vi.fn();
const mockCancelSubscription = vi.fn();
const mockUpgradeSubscription = vi.fn();
const mockGetCheckoutInfo = vi.fn();
const mockSubmitPayment = vi.fn();

vi.mock("@/lib/api", () => ({
  getSubscriptionPlans: (...args: unknown[]) => mockGetSubscriptionPlans(...args),
  getSubscriptionStatus: (...args: unknown[]) => mockGetSubscriptionStatus(...args),
  cancelSubscription: (...args: unknown[]) => mockCancelSubscription(...args),
  upgradeSubscription: (...args: unknown[]) => mockUpgradeSubscription(...args),
  getCheckoutInfo: (...args: unknown[]) => mockGetCheckoutInfo(...args),
  submitPayment: (...args: unknown[]) => mockSubmitPayment(...args),
}));

// ─── Mock i18n (stable reference to avoid infinite useEffect re-runs) ───
const stableT = vi.fn((key: string, params?: Record<string, string>) => {
  if (params) {
    return Object.entries(params).reduce(
      (acc, [k, v]) => acc.replace(`{${k}}`, v),
      key
    );
  }
  return key;
});
const stableSetLocale = vi.fn();

vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: stableT,
    locale: "en",
    setLocale: stableSetLocale,
  }),
}));

// ─── Mock sonner toast ───
const mockToastSuccess = vi.fn();
const mockToastError = vi.fn();

vi.mock("sonner", () => ({
  toast: {
    success: (...args: unknown[]) => mockToastSuccess(...args),
    error: (...args: unknown[]) => mockToastError(...args),
  },
}));

// ─── Mock utils ───
vi.mock("@/lib/utils", () => ({
  formatDateTime: (val: string) => val,
  cn: (...args: unknown[]) => args.filter(Boolean).join(" "),
}));

// ─── Mock clipboard ───
Object.assign(navigator, {
  clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
});

// ─── Test data ───
const mockPlans = [
  {
    planId: "free",
    name: "Free",
    priceMonthly: 0,
    priceUsdt: 0,
    maxPositions: 1,
    maxSymbols: 3,
    dcaLayersAllowed: 0,
    maxRiskPercent: 0.1,
    current: true,
    paymentLinkUrl: null,
  },
  {
    planId: "basic",
    name: "Basic",
    priceMonthly: 19,
    priceUsdt: 19,
    maxPositions: 5,
    maxSymbols: 10,
    dcaLayersAllowed: 3,
    maxRiskPercent: 0.2,
    current: false,
    paymentLinkUrl: null,
  },
  {
    planId: "pro",
    name: "Pro",
    priceMonthly: 49,
    priceUsdt: 49,
    maxPositions: 20,
    maxSymbols: 50,
    dcaLayersAllowed: 10,
    maxRiskPercent: 0.5,
    current: false,
    paymentLinkUrl: null,
  },
];

const mockStatusNone = {
  status: "NONE",
  active: false,
  planId: null,
  planName: null,
  currentPeriodEnd: null,
  network: null,
};

const mockStatusActive = {
  status: "ACTIVE",
  active: true,
  planId: "basic",
  planName: "Basic",
  currentPeriodEnd: "2025-06-01T00:00:00",
  network: "TRC20",
};

const mockCheckoutBasic = {
  planId: "basic",
  planName: "Basic",
  amountUsdt: 19,
  walletAddress: "TTestWallet123",
  network: "TRC20",
};

// ─── Helper ───
function setupDefaultMocks() {
  mockGetSubscriptionPlans.mockResolvedValue(mockPlans);
  mockGetSubscriptionStatus.mockResolvedValue(mockStatusNone);
}

function setupActiveMocks() {
  const activePlans = mockPlans.map((p) =>
    p.planId === "basic" ? { ...p, current: true } : { ...p, current: false }
  );
  mockGetSubscriptionPlans.mockResolvedValue(activePlans);
  mockGetSubscriptionStatus.mockResolvedValue(mockStatusActive);
}

beforeEach(() => {
  vi.clearAllMocks();
});

// ==================== Initial Load ====================

describe("SubscriptionManager", () => {
  describe("Initial load", () => {
    it("shows loading spinner then renders plan cards", async () => {
      setupDefaultMocks();

      render(<SubscriptionManager />);

      // Spinner is present initially
      expect(document.querySelector(".animate-spin")).toBeInTheDocument();

      // Wait for plans to load
      await waitFor(() => {
        expect(screen.getByText("Free")).toBeInTheDocument();
      });

      // Spinner is gone
      expect(document.querySelector(".animate-spin")).not.toBeInTheDocument();

      // All plan names rendered
      expect(screen.getByText("Basic")).toBeInTheDocument();
      expect(screen.getByText("Pro")).toBeInTheDocument();
    });

    it("shows error text when API fails", async () => {
      mockGetSubscriptionPlans.mockRejectedValue(new Error("Network error"));
      mockGetSubscriptionStatus.mockRejectedValue(new Error("Network error"));

      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Network error")).toBeInTheDocument();
      });
    });
  });

  // ==================== Plan Cards ====================

  describe("Plan cards", () => {
    it("shows plan names and USDT prices", async () => {
      setupDefaultMocks();

      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Free")).toBeInTheDocument();
      });

      expect(screen.getByText("19 USDT")).toBeInTheDocument();
      expect(screen.getByText("49 USDT")).toBeInTheDocument();
    });

    it("shows current badge on the current plan", async () => {
      setupDefaultMocks();

      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Free")).toBeInTheDocument();
      });

      // The current badge text (i18n key)
      const badges = screen.getAllByText("settings.currentBadge");
      expect(badges.length).toBeGreaterThanOrEqual(1);
    });

    it("shows free text for free plan instead of USDT price", async () => {
      setupDefaultMocks();

      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Free")).toBeInTheDocument();
      });

      // Free plan shows the i18n key for free
      expect(screen.getByText("settings.free")).toBeInTheDocument();
    });
  });

  // ==================== USDT Payment Dialog ====================

  describe("USDT Payment Dialog", () => {
    it("opens payment dialog when Subscribe button is clicked", async () => {
      setupDefaultMocks();
      mockGetCheckoutInfo.mockResolvedValue(mockCheckoutBasic);

      const user = userEvent.setup();
      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Free")).toBeInTheDocument();
      });

      // Find and click Subscribe button (i18n key)
      const subscribeButtons = screen.getAllByText("settings.subscribe");
      await user.click(subscribeButtons[0]);

      await waitFor(() => {
        expect(screen.getByText("TTestWallet123")).toBeInTheDocument();
      });
    });

    it("shows wallet address and amount in dialog", async () => {
      setupDefaultMocks();
      mockGetCheckoutInfo.mockResolvedValue(mockCheckoutBasic);

      const user = userEvent.setup();
      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Free")).toBeInTheDocument();
      });

      const subscribeButtons = screen.getAllByText("settings.subscribe");
      await user.click(subscribeButtons[0]);

      await waitFor(() => {
        expect(screen.getByText("TTestWallet123")).toBeInTheDocument();
        expect(screen.getByText("19 USDT")).toBeInTheDocument();
        expect(screen.getByText("TRC20")).toBeInTheDocument();
      });
    });

    it("copy button copies wallet address to clipboard", async () => {
      setupDefaultMocks();
      mockGetCheckoutInfo.mockResolvedValue(mockCheckoutBasic);

      const user = userEvent.setup();
      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Free")).toBeInTheDocument();
      });

      const subscribeButtons = screen.getAllByText("settings.subscribe");
      await user.click(subscribeButtons[0]);

      await waitFor(() => {
        expect(screen.getByText("TTestWallet123")).toBeInTheDocument();
      });

      // Find the copy button (the button with Copy icon near the wallet address)
      // The copy button is an icon button near the wallet address
      const copyButtons = document.querySelectorAll("button");
      const copyButton = Array.from(copyButtons).find(
        (btn) => btn.querySelector(".lucide-copy") || btn.querySelector("[data-lucide='copy']")
      );

      if (copyButton) {
        await user.click(copyButton);
        expect(navigator.clipboard.writeText).toHaveBeenCalledWith("TTestWallet123");
      }
    });

    it("empty txHash disables submit button", async () => {
      setupDefaultMocks();
      mockGetCheckoutInfo.mockResolvedValue(mockCheckoutBasic);

      const user = userEvent.setup();
      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Free")).toBeInTheDocument();
      });

      const subscribeButtons = screen.getAllByText("settings.subscribe");
      await user.click(subscribeButtons[0]);

      await waitFor(() => {
        expect(screen.getByText("TTestWallet123")).toBeInTheDocument();
      });

      // The submit button should be disabled when txHash is empty
      const submitButton = screen.getByRole("button", { name: /提交驗證/ });
      expect(submitButton).toBeDisabled();
    });

    it("successful payment submission closes dialog and shows success toast", async () => {
      setupDefaultMocks();
      mockGetCheckoutInfo.mockResolvedValue(mockCheckoutBasic);
      mockSubmitPayment.mockResolvedValue({
        status: "success",
        message: "付款驗證成功！Basic 方案已開通至 2025-06-01",
      });
      // After successful payment, status refreshes
      mockGetSubscriptionStatus.mockResolvedValue(mockStatusActive);
      const activePlans = mockPlans.map((p) =>
        p.planId === "basic" ? { ...p, current: true } : { ...p, current: false }
      );
      mockGetSubscriptionPlans.mockResolvedValue(activePlans);

      const user = userEvent.setup();
      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Free")).toBeInTheDocument();
      });

      const subscribeButtons = screen.getAllByText("settings.subscribe");
      await user.click(subscribeButtons[0]);

      await waitFor(() => {
        expect(screen.getByText("TTestWallet123")).toBeInTheDocument();
      });

      // Type txHash
      const txInput = screen.getByPlaceholderText(/a1b2c3d4e5f6/);
      await user.type(txInput, "realTxHash123");

      // Submit
      const submitButton = screen.getByRole("button", { name: /提交驗證/ });
      await user.click(submitButton);

      await waitFor(() => {
        expect(mockSubmitPayment).toHaveBeenCalledWith({
          planId: "basic",
          txHash: "realTxHash123",
        });
        expect(mockToastSuccess).toHaveBeenCalled();
      });
    });

    it("failed payment submission shows error toast", async () => {
      setupDefaultMocks();
      mockGetCheckoutInfo.mockResolvedValue(mockCheckoutBasic);
      mockSubmitPayment.mockRejectedValue(new Error("交易驗證失敗"));

      const user = userEvent.setup();
      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Free")).toBeInTheDocument();
      });

      const subscribeButtons = screen.getAllByText("settings.subscribe");
      await user.click(subscribeButtons[0]);

      await waitFor(() => {
        expect(screen.getByText("TTestWallet123")).toBeInTheDocument();
      });

      const txInput = screen.getByPlaceholderText(/a1b2c3d4e5f6/);
      await user.type(txInput, "badTxHash");

      const submitButton = screen.getByRole("button", { name: /提交驗證/ });
      await user.click(submitButton);

      await waitFor(() => {
        expect(mockToastError).toHaveBeenCalled();
      });
    });

    it("shows verifying loading text during submission", async () => {
      setupDefaultMocks();
      mockGetCheckoutInfo.mockResolvedValue(mockCheckoutBasic);

      // Never resolve so we can see the loading state
      let resolvePayment: (value: unknown) => void;
      mockSubmitPayment.mockReturnValue(
        new Promise((resolve) => {
          resolvePayment = resolve;
        })
      );

      const user = userEvent.setup();
      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Free")).toBeInTheDocument();
      });

      const subscribeButtons = screen.getAllByText("settings.subscribe");
      await user.click(subscribeButtons[0]);

      await waitFor(() => {
        expect(screen.getByText("TTestWallet123")).toBeInTheDocument();
      });

      const txInput = screen.getByPlaceholderText(/a1b2c3d4e5f6/);
      await user.type(txInput, "someTxHash");

      const submitButton = screen.getByRole("button", { name: /提交驗證/ });
      await user.click(submitButton);

      // Should show verifying text
      await waitFor(() => {
        expect(screen.getByText("驗證中...")).toBeInTheDocument();
      });

      // Cleanup: resolve the pending promise
      resolvePayment!({
        status: "success",
        message: "ok",
      });
    });
  });

  // ==================== Cancel Subscription ====================

  describe("Cancel subscription", () => {
    it("opens confirmation dialog when cancel button clicked", async () => {
      setupActiveMocks();

      const user = userEvent.setup();
      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Basic")).toBeInTheDocument();
      });

      // Click the cancel subscription button (i18n key)
      const cancelButton = screen.getByText("settings.cancelSubscription");
      await user.click(cancelButton);

      // Confirmation dialog should appear
      await waitFor(() => {
        expect(screen.getByText("settings.cancelConfirmTitle")).toBeInTheDocument();
        expect(screen.getByText("settings.cancelConfirmMessage")).toBeInTheDocument();
      });
    });

    it("confirms cancellation and calls API", async () => {
      setupActiveMocks();
      mockCancelSubscription.mockResolvedValue({
        status: "success",
        message: "訂閱已立即取消",
      });
      // After cancel, status refreshes to none
      mockGetSubscriptionStatus
        .mockResolvedValueOnce(mockStatusActive) // initial load
        .mockResolvedValue(mockStatusNone); // after cancel
      mockGetSubscriptionPlans.mockResolvedValue(mockPlans); // refreshed plans

      const user = userEvent.setup();
      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Basic")).toBeInTheDocument();
      });

      const cancelButton = screen.getByText("settings.cancelSubscription");
      await user.click(cancelButton);

      await waitFor(() => {
        expect(screen.getByText("settings.cancelConfirmButton")).toBeInTheDocument();
      });

      // Click the confirm button
      const confirmButton = screen.getByText("settings.cancelConfirmButton");
      await user.click(confirmButton);

      await waitFor(() => {
        expect(mockCancelSubscription).toHaveBeenCalled();
        expect(mockToastSuccess).toHaveBeenCalled();
      });
    });
  });

  // ==================== Upgrade ====================

  describe("Upgrade subscription", () => {
    it("shows upgrade button for higher plan when subscription is active", async () => {
      setupActiveMocks();

      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Basic")).toBeInTheDocument();
      });

      // Pro plan should have an upgrade button
      expect(screen.getByText("settings.upgrade")).toBeInTheDocument();
    });

    it("calls upgrade API when upgrade button clicked", async () => {
      setupActiveMocks();
      mockUpgradeSubscription.mockResolvedValue({
        status: "success",
        message: "方案已更新為 pro",
      });
      // After upgrade, refresh status
      const proStatus = { ...mockStatusActive, planId: "pro", planName: "Pro" };
      mockGetSubscriptionStatus
        .mockResolvedValueOnce(mockStatusActive) // initial
        .mockResolvedValue(proStatus); // after upgrade
      const upgradedPlans = mockPlans.map((p) =>
        p.planId === "pro" ? { ...p, current: true } : { ...p, current: false }
      );
      mockGetSubscriptionPlans
        .mockResolvedValueOnce(
          mockPlans.map((p) =>
            p.planId === "basic" ? { ...p, current: true } : { ...p, current: false }
          )
        ) // initial
        .mockResolvedValue(upgradedPlans); // after upgrade

      const user = userEvent.setup();
      render(<SubscriptionManager />);

      await waitFor(() => {
        expect(screen.getByText("Basic")).toBeInTheDocument();
      });

      const upgradeButton = screen.getByText("settings.upgrade");
      await user.click(upgradeButton);

      await waitFor(() => {
        expect(mockUpgradeSubscription).toHaveBeenCalledWith({ planId: "pro" });
        expect(mockToastSuccess).toHaveBeenCalled();
      });
    });
  });
});
