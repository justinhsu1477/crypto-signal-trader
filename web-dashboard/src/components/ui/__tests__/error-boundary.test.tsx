import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { ErrorBoundary, SectionErrorFallback, PageErrorFallback } from "../error-boundary";

/* ── 會拋錯的測試元件 ── */
let shouldThrowGlobal = false;

function ThrowError({ shouldThrow }: { shouldThrow?: boolean }) {
  // 支援 prop 控制或 global 控制（retry 測試需要 global）
  if (shouldThrow ?? shouldThrowGlobal) {
    throw new Error("Test render error");
  }
  return <div>正常內容</div>;
}

/* 壓制 React / jsdom 的 console.error — ErrorBoundary 會觸發大量 log */
beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
});

/* ================================================================== */
/*  ErrorBoundary                                                      */
/* ================================================================== */
describe("ErrorBoundary", () => {
  it("正常渲染 children", () => {
    render(
      <ErrorBoundary>
        <ThrowError shouldThrow={false} />
      </ErrorBoundary>,
    );
    expect(screen.getByText("正常內容")).toBeInTheDocument();
  });

  it("子元件拋錯 → 顯示預設 fallback UI", () => {
    render(
      <ErrorBoundary>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>,
    );
    expect(screen.queryByText("正常內容")).not.toBeInTheDocument();
    expect(screen.getByText("此區塊發生錯誤")).toBeInTheDocument();
    expect(screen.getByText("Test render error")).toBeInTheDocument();
    expect(screen.getByText("重試")).toBeInTheDocument();
  });

  it("按「重試」按鈕 → reset 並重新渲染", async () => {
    const user = userEvent.setup();

    // 用 global 控制拋錯行為，這樣 ErrorBoundary reset 後 re-render 時能讀到新值
    shouldThrowGlobal = true;
    render(
      <ErrorBoundary>
        <ThrowError />
      </ErrorBoundary>,
    );
    expect(screen.getByText("此區塊發生錯誤")).toBeInTheDocument();

    // 修正拋錯條件後，點重試
    shouldThrowGlobal = false;
    await user.click(screen.getByText("重試"));

    expect(screen.getByText("正常內容")).toBeInTheDocument();
  });

  it("自訂 fallback → 顯示自訂內容", () => {
    render(
      <ErrorBoundary fallback={<div>自訂錯誤畫面</div>}>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>,
    );
    expect(screen.getByText("自訂錯誤畫面")).toBeInTheDocument();
    expect(screen.queryByText("此區塊發生錯誤")).not.toBeInTheDocument();
  });

  it("onReset callback 被呼叫", async () => {
    const user = userEvent.setup();
    const onReset = vi.fn();

    render(
      <ErrorBoundary onReset={onReset}>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>,
    );

    await user.click(screen.getByText("重試"));
    expect(onReset).toHaveBeenCalledOnce();
  });
});

/* ================================================================== */
/*  SectionErrorFallback                                               */
/* ================================================================== */
describe("SectionErrorFallback", () => {
  it("顯示錯誤訊息和重試按鈕", () => {
    const onRetry = vi.fn();
    render(<SectionErrorFallback error={new Error("DB timeout")} onRetry={onRetry} />);

    expect(screen.getByText("此區塊發生錯誤")).toBeInTheDocument();
    expect(screen.getByText("DB timeout")).toBeInTheDocument();
    expect(screen.getByText("重試")).toBeInTheDocument();
  });

  it("無 onRetry 時不顯示重試按鈕", () => {
    render(<SectionErrorFallback />);
    expect(screen.getByText("此區塊發生錯誤")).toBeInTheDocument();
    expect(screen.queryByText("重試")).not.toBeInTheDocument();
  });
});

/* ================================================================== */
/*  PageErrorFallback                                                  */
/* ================================================================== */
describe("PageErrorFallback", () => {
  it("顯示全頁錯誤畫面和重新整理按鈕", () => {
    render(<PageErrorFallback />);
    expect(screen.getByText("頁面發生錯誤")).toBeInTheDocument();
    expect(screen.getByText("重新整理")).toBeInTheDocument();
  });
});
