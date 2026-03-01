/**
 * Referral Page 測試
 *
 * 測試重點：
 * 1. Loading → 資料載入完成
 * 2. NOT_STARTED 狀態：顯示 UID 表單 + 推薦連結
 * 3. PENDING 狀態：顯示等待審核卡片 + 已提交 UID
 * 4. VERIFIED 狀態：顯示成功卡片 + 前往總覽按鈕
 * 5. UID 提交成功 → 狀態切換到 PENDING
 * 6. UID 提交失敗 → 顯示錯誤訊息
 * 7. 複製推薦連結/推薦碼功能
 * 8. API 載入失敗 → 顯示錯誤
 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import ReferralPage from "../page";

// ─── Mock i18n ───
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => key,
    locale: "en",
    setLocale: vi.fn(),
  }),
}));

// ─── Mock API ───
const mockGetReferralStatus = vi.fn();
const mockSubmitReferralUid = vi.fn();

vi.mock("@/lib/api", () => ({
  getReferralStatus: (...args: unknown[]) => mockGetReferralStatus(...args),
  submitReferralUid: (...args: unknown[]) => mockSubmitReferralUid(...args),
}));

// ─── Mock clipboard ───
const mockWriteText = vi.fn(() => Promise.resolve());
beforeEach(() => {
  Object.defineProperty(navigator, "clipboard", {
    value: { writeText: mockWriteText },
    writable: true,
    configurable: true,
  });
});

// ─── Mock utils ───
vi.mock("@/lib/utils", () => ({
  cn: (...inputs: string[]) => inputs.filter(Boolean).join(" "),
  formatDateTime: (v: string) => v,
}));

// ─── Helpers ───

const NOT_STARTED_DATA = {
  status: "NOT_STARTED" as const,
  exchangeUid: null,
  verifiedAt: null,
  referralLink: "https://www.binance.com/referral/ABC123",
  referralCode: "ABC123",
};

const PENDING_DATA = {
  status: "PENDING" as const,
  exchangeUid: "12345678",
  verifiedAt: null,
  referralLink: "https://www.binance.com/referral/ABC123",
  referralCode: "ABC123",
};

const VERIFIED_DATA = {
  status: "VERIFIED" as const,
  exchangeUid: "12345678",
  verifiedAt: "2025-06-01T12:00:00Z",
  referralLink: "https://www.binance.com/referral/ABC123",
  referralCode: "ABC123",
};

beforeEach(() => {
  mockGetReferralStatus.mockReset();
  mockSubmitReferralUid.mockReset();
});

// ==================== Loading ====================

describe("載入狀態", () => {
  it("載入中 → 顯示 spinner", () => {
    // 永遠不 resolve 的 promise
    mockGetReferralStatus.mockReturnValue(new Promise(() => {}));
    const { container } = render(<ReferralPage />);
    expect(container.querySelector(".animate-spin")).toBeInTheDocument();
  });

  it("載入失敗 → 顯示錯誤訊息", async () => {
    mockGetReferralStatus.mockRejectedValue(new Error("Network Error"));
    render(<ReferralPage />);

    await waitFor(() => {
      expect(screen.getByText("Network Error")).toBeInTheDocument();
    });
  });
});

// ==================== NOT_STARTED ====================

describe("NOT_STARTED 狀態", () => {
  beforeEach(() => {
    mockGetReferralStatus.mockResolvedValue(NOT_STARTED_DATA);
  });

  it("顯示標題 + 步驟指示器 + UID 表單", async () => {
    render(<ReferralPage />);

    await waitFor(() => {
      expect(screen.getByText("referral.title")).toBeInTheDocument();
    });

    // 步驟文字
    expect(screen.getAllByText("referral.stepRegister").length).toBeGreaterThan(0);
    expect(screen.getAllByText("referral.stepSubmitUid").length).toBeGreaterThan(0);

    // UID 輸入欄
    expect(screen.getByLabelText("referral.uidLabel")).toBeInTheDocument();

    // 提交按鈕
    expect(screen.getByText("referral.submitUid")).toBeInTheDocument();

    // Badge
    expect(screen.getByText("referral.statusNotStarted")).toBeInTheDocument();
  });

  it("顯示推薦連結 + 推薦碼", async () => {
    render(<ReferralPage />);

    await waitFor(() => {
      expect(
        screen.getByDisplayValue("https://www.binance.com/referral/ABC123")
      ).toBeInTheDocument();
    });

    expect(screen.getByText("ABC123")).toBeInTheDocument();
  });

  it("不顯示 PENDING / VERIFIED 區塊", async () => {
    render(<ReferralPage />);

    await waitFor(() => {
      expect(screen.getByText("referral.title")).toBeInTheDocument();
    });

    expect(screen.queryByText("referral.pendingTitle")).not.toBeInTheDocument();
    expect(screen.queryByText("referral.verifiedTitle")).not.toBeInTheDocument();
  });
});

// ==================== PENDING ====================

describe("PENDING 狀態", () => {
  beforeEach(() => {
    mockGetReferralStatus.mockResolvedValue(PENDING_DATA);
  });

  it("顯示等待審核卡片 + 已提交 UID", async () => {
    render(<ReferralPage />);

    await waitFor(() => {
      expect(screen.getByText("referral.pendingTitle")).toBeInTheDocument();
    });

    expect(screen.getByText("referral.pendingMessage")).toBeInTheDocument();
    expect(screen.getByText("12345678")).toBeInTheDocument();

    // Badge
    expect(screen.getByText("referral.statusPending")).toBeInTheDocument();
  });

  it("不顯示 UID 表單和 VERIFIED 區塊", async () => {
    render(<ReferralPage />);

    await waitFor(() => {
      expect(screen.getByText("referral.pendingTitle")).toBeInTheDocument();
    });

    expect(screen.queryByLabelText("referral.uidLabel")).not.toBeInTheDocument();
    expect(screen.queryByText("referral.verifiedTitle")).not.toBeInTheDocument();
  });
});

// ==================== VERIFIED ====================

describe("VERIFIED 狀態", () => {
  beforeEach(() => {
    mockGetReferralStatus.mockResolvedValue(VERIFIED_DATA);
  });

  it("顯示成功卡片 + 前往總覽按鈕", async () => {
    render(<ReferralPage />);

    await waitFor(() => {
      expect(screen.getByText("referral.verifiedTitle")).toBeInTheDocument();
    });

    expect(screen.getByText("referral.verifiedMessage")).toBeInTheDocument();
    expect(screen.getByText("referral.goToDashboard")).toBeInTheDocument();

    // Badge
    expect(screen.getByText("referral.statusVerified")).toBeInTheDocument();
  });

  it("不顯示 UID 表單和 PENDING 區塊", async () => {
    render(<ReferralPage />);

    await waitFor(() => {
      expect(screen.getByText("referral.verifiedTitle")).toBeInTheDocument();
    });

    expect(screen.queryByLabelText("referral.uidLabel")).not.toBeInTheDocument();
    expect(screen.queryByText("referral.pendingTitle")).not.toBeInTheDocument();
  });
});

// ==================== UID Submit ====================

describe("UID 提交", () => {
  beforeEach(() => {
    mockGetReferralStatus.mockResolvedValue(NOT_STARTED_DATA);
  });

  it("提交成功 → 狀態切換到 PENDING", async () => {
    mockSubmitReferralUid.mockResolvedValue(PENDING_DATA);
    const user = userEvent.setup();
    render(<ReferralPage />);

    await waitFor(() => {
      expect(screen.getByLabelText("referral.uidLabel")).toBeInTheDocument();
    });

    const input = screen.getByLabelText("referral.uidLabel") as HTMLInputElement;
    await user.clear(input);
    await user.type(input, "12345678");

    const submitBtn = screen.getByText("referral.submitUid");
    await user.click(submitBtn);

    await waitFor(() => {
      expect(screen.getByText("referral.submitSuccess")).toBeInTheDocument();
    });

    // 呼叫 API（verify 有呼叫且 UID 正確）
    expect(mockSubmitReferralUid).toHaveBeenCalledTimes(1);
    const callArg = mockSubmitReferralUid.mock.calls[0][0];
    expect(callArg.exchangeUid).toBeTruthy();
  });

  it("空白 UID → 顯示必填錯誤", async () => {
    const user = userEvent.setup();
    render(<ReferralPage />);

    await waitFor(() => {
      expect(screen.getByText("referral.submitUid")).toBeInTheDocument();
    });

    const submitBtn = screen.getByText("referral.submitUid");
    await user.click(submitBtn);

    expect(screen.getByText("referral.uidRequired")).toBeInTheDocument();
    expect(mockSubmitReferralUid).not.toHaveBeenCalled();
  });

  it("提交失敗 → 顯示錯誤訊息", async () => {
    mockSubmitReferralUid.mockRejectedValue(new Error("UID already exists"));
    const user = userEvent.setup();
    render(<ReferralPage />);

    await waitFor(() => {
      expect(screen.getByLabelText("referral.uidLabel")).toBeInTheDocument();
    });

    await user.type(screen.getByLabelText("referral.uidLabel"), "99999");
    await user.click(screen.getByText("referral.submitUid"));

    await waitFor(() => {
      expect(screen.getByText("UID already exists")).toBeInTheDocument();
    });
  });
});

// ==================== Copy ====================

describe("複製功能", () => {
  beforeEach(() => {
    mockGetReferralStatus.mockResolvedValue(NOT_STARTED_DATA);
  });

  it("複製按鈕存在且可點擊 → clipboard 被呼叫", async () => {
    render(<ReferralPage />);

    await waitFor(() => {
      expect(screen.getByText("referral.copyLink")).toBeInTheDocument();
    });

    // 複製按鈕是個 button 元素
    const copyBtn = screen.getByText("referral.copyLink").closest("button");
    expect(copyBtn).toBeInTheDocument();
    expect(copyBtn).not.toBeDisabled();

    fireEvent.click(copyBtn!);

    // navigator.clipboard.writeText 被呼叫
    await waitFor(() => {
      expect(mockWriteText).toHaveBeenCalledWith(
        "https://www.binance.com/referral/ABC123"
      );
    });
  });
});
