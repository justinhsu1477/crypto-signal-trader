import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { ErrorBoundary, SectionErrorFallback, PageErrorFallback } from "../error-boundary";

/* ── 會拋錯的測試元件 ── */
let shouldThrowGlobal = false;

function ThrowError({ shouldThrow }: { shouldThrow?: boolean }) {
  if (shouldThrow ?? shouldThrowGlobal) {
    throw new Error("Test render error");
  }
  return <div>正常內容</div>;
}

/* 壓制 React / jsdom 的 console.error — ErrorBoundary 會觸發大量 log */
beforeEach(() => {
  vi.spyOn(console, "error").mockImplementation(() => {});
  shouldThrowGlobal = false;
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
    // Uses browser locale detection — in jsdom default "en", shows English
    expect(screen.getByText("Something went wrong in this section")).toBeInTheDocument();
    expect(screen.getByText("Test render error")).toBeInTheDocument();
    expect(screen.getByText("Retry")).toBeInTheDocument();
  });

  it("按「重試」按鈕 → reset 並重新渲染", async () => {
    const user = userEvent.setup();

    shouldThrowGlobal = true;
    render(
      <ErrorBoundary>
        <ThrowError />
      </ErrorBoundary>,
    );
    expect(screen.getByText("Something went wrong in this section")).toBeInTheDocument();

    shouldThrowGlobal = false;
    await user.click(screen.getByText("Retry"));

    expect(screen.getByText("正常內容")).toBeInTheDocument();
  });

  it("自訂 fallback → 顯示自訂內容", () => {
    render(
      <ErrorBoundary fallback={<div>自訂錯誤畫面</div>}>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>,
    );
    expect(screen.getByText("自訂錯誤畫面")).toBeInTheDocument();
    expect(screen.queryByText("Something went wrong in this section")).not.toBeInTheDocument();
  });

  it("onReset callback 被呼叫", async () => {
    const user = userEvent.setup();
    const onReset = vi.fn();

    render(
      <ErrorBoundary onReset={onReset}>
        <ThrowError shouldThrow={true} />
      </ErrorBoundary>,
    );

    await user.click(screen.getByText("Retry"));
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

    expect(screen.getByText("Something went wrong in this section")).toBeInTheDocument();
    expect(screen.getByText("DB timeout")).toBeInTheDocument();
    expect(screen.getByText("Retry")).toBeInTheDocument();
  });

  it("無 onRetry 時不顯示重試按鈕", () => {
    render(<SectionErrorFallback />);
    expect(screen.getByText("Something went wrong in this section")).toBeInTheDocument();
    expect(screen.queryByText("Retry")).not.toBeInTheDocument();
  });
});

/* ================================================================== */
/*  PageErrorFallback                                                  */
/* ================================================================== */
describe("PageErrorFallback", () => {
  it("顯示全頁錯誤畫面和 refresh 按鈕", () => {
    render(<PageErrorFallback />);
    expect(screen.getByText("Something went wrong")).toBeInTheDocument();
    expect(screen.getByText("Refresh Page")).toBeInTheDocument();
  });

  it("locale detection: 使用 navigator.language 決定語言", () => {
    // jsdom default navigator.language is "en" → English labels
    render(<PageErrorFallback />);
    expect(screen.getByText("Something went wrong")).toBeInTheDocument();
    expect(screen.getByText(/unexpected error/i)).toBeInTheDocument();
  });
});
