import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi } from "vitest";
import { LogoutDialog } from "../logout-dialog";

// Mock i18n
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => {
      const map: Record<string, string> = {
        "nav.logoutConfirmTitle": "Confirm Logout",
        "nav.logoutConfirmDesc": "Are you sure you want to logout?",
        "common.cancel": "Cancel",
        "nav.logout": "Logout",
      };
      return map[key] || key;
    },
    locale: "en",
  }),
}));

describe("LogoutDialog", () => {
  it("renders confirmation dialog when open", () => {
    render(
      <LogoutDialog open={true} onOpenChange={vi.fn()} onConfirm={vi.fn()} />,
    );
    expect(screen.getByText("Confirm Logout")).toBeInTheDocument();
    expect(screen.getByText("Are you sure you want to logout?")).toBeInTheDocument();
    expect(screen.getByText("Cancel")).toBeInTheDocument();
    expect(screen.getByText("Logout")).toBeInTheDocument();
  });

  it("does not render when closed", () => {
    render(
      <LogoutDialog open={false} onOpenChange={vi.fn()} onConfirm={vi.fn()} />,
    );
    expect(screen.queryByText("Confirm Logout")).not.toBeInTheDocument();
  });

  it("calls onConfirm when logout button is clicked", async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(
      <LogoutDialog open={true} onOpenChange={vi.fn()} onConfirm={onConfirm} />,
    );

    await user.click(screen.getByText("Logout"));
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it("calls onOpenChange(false) when cancel is clicked", async () => {
    const user = userEvent.setup();
    const onOpenChange = vi.fn();
    render(
      <LogoutDialog open={true} onOpenChange={onOpenChange} onConfirm={vi.fn()} />,
    );

    await user.click(screen.getByText("Cancel"));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});
