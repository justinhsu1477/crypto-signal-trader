import { render, screen, within } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { Header } from "../header";

// Translation map — covers all nav keys used by Header
const translations: Record<string, string> = {
  "nav.overview": "Overview",
  "nav.performance": "Performance",
  "nav.chart": "Chart",
  "nav.trades": "Trades",
  "nav.referral": "Referral",
  "nav.announcements": "Announcements",
  "nav.settings": "Settings",
  "nav.logout": "Logout",
  "nav.adminOverview": "Admin Overview",
  "nav.adminSignal": "Emergency Signal",
  "nav.adminUsers": "User Management",
  "nav.adminReferrals": "Referral Management",
  "nav.adminSubscriptions": "Subscription Management",
  "nav.adminAnalytics": "Analytics",
  "nav.adminInsights": "Insights",
  "nav.adminBroadcastLogs": "Broadcast Logs",
  "nav.adminDailyReports": "Daily Signal Report",
  "nav.adminNotifications": "Notifications",
  "nav.adminAnnouncements": "Announcement Management",
  "nav.adminSignalSources": "Signal Sources",
  "nav.adminSettings": "Admin Settings",
  "nav.logoutConfirmTitle": "Confirm Logout",
  "nav.logoutConfirmDesc": "Are you sure?",
  "common.cancel": "Cancel",
};

// Mock i18n
vi.mock("@/lib/i18n/i18n-context", () => ({
  useT: () => ({
    t: (key: string) => translations[key] || key,
    locale: "en",
  }),
}));

// Mock auth — default to USER, tests can override
const mockAuth = {
  logout: vi.fn(),
  email: "user@test.com",
  role: "USER",
};
vi.mock("@/lib/auth-context", () => ({
  useAuth: () => mockAuth,
}));

// Mock Sheet components — render children directly so nav links are visible
vi.mock("@/components/ui/sheet", () => ({
  Sheet: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SheetTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  SheetContent: ({ children }: { children: React.ReactNode }) => (
    <nav data-testid="mobile-nav">{children}</nav>
  ),
}));

// Mock LogoutDialog
vi.mock("@/components/layout/logout-dialog", () => ({
  LogoutDialog: () => null,
}));

// Mock LanguageSwitcher
vi.mock("@/components/ui/language-switcher", () => ({
  LanguageSwitcher: () => <div data-testid="lang-switcher" />,
}));

describe("Header", () => {
  beforeEach(() => {
    mockAuth.role = "USER";
    mockAuth.email = "user@test.com";
  });

  describe("User Navigation", () => {
    it("renders all 7 user nav items when role is USER", () => {
      render(<Header />);
      const nav = screen.getByTestId("mobile-nav");

      expect(within(nav).getByText("Overview")).toBeInTheDocument();
      expect(within(nav).getByText("Performance")).toBeInTheDocument();
      expect(within(nav).getByText("Chart")).toBeInTheDocument();
      expect(within(nav).getByText("Trades")).toBeInTheDocument();
      expect(within(nav).getByText("Referral")).toBeInTheDocument();
      expect(within(nav).getByText("Announcements")).toBeInTheDocument();
      expect(within(nav).getByText("Settings")).toBeInTheDocument();

      // Count all nav links (a tags) in the mobile nav
      const links = within(nav).getAllByRole("link");
      expect(links).toHaveLength(7);
    });

    it("includes announcements link for users", () => {
      render(<Header />);
      const nav = screen.getByTestId("mobile-nav");
      const announcementsLink = within(nav).getByText("Announcements").closest("a");
      expect(announcementsLink).toHaveAttribute("href", "/announcements");
    });

    it("does not show admin items for regular users", () => {
      render(<Header />);
      const nav = screen.getByTestId("mobile-nav");

      expect(within(nav).queryByText("Admin Overview")).not.toBeInTheDocument();
      expect(within(nav).queryByText("Emergency Signal")).not.toBeInTheDocument();
      expect(within(nav).queryByText("User Management")).not.toBeInTheDocument();
    });
  });

  describe("Admin Navigation", () => {
    beforeEach(() => {
      mockAuth.role = "ADMIN";
      mockAuth.email = "admin@test.com";
    });

    it("renders all 13 admin nav items when role is ADMIN", () => {
      render(<Header />);
      const nav = screen.getByTestId("mobile-nav");

      expect(within(nav).getByText("Admin Overview")).toBeInTheDocument();
      expect(within(nav).getByText("Emergency Signal")).toBeInTheDocument();
      expect(within(nav).getByText("User Management")).toBeInTheDocument();
      expect(within(nav).getByText("Referral Management")).toBeInTheDocument();
      expect(within(nav).getByText("Subscription Management")).toBeInTheDocument();
      expect(within(nav).getByText("Analytics")).toBeInTheDocument();
      expect(within(nav).getByText("Insights")).toBeInTheDocument();
      expect(within(nav).getByText("Signal Sources")).toBeInTheDocument();
      expect(within(nav).getByText("Broadcast Logs")).toBeInTheDocument();
      expect(within(nav).getByText("Daily Signal Report")).toBeInTheDocument();
      expect(within(nav).getByText("Notifications")).toBeInTheDocument();
      expect(within(nav).getByText("Announcement Management")).toBeInTheDocument();
      expect(within(nav).getByText("Admin Settings")).toBeInTheDocument();

      const links = within(nav).getAllByRole("link");
      expect(links).toHaveLength(13);
    });

    it("includes signal link for admin", () => {
      render(<Header />);
      const nav = screen.getByTestId("mobile-nav");
      const signalLink = within(nav).getByText("Emergency Signal").closest("a");
      expect(signalLink).toHaveAttribute("href", "/admin/signal");
    });

    it("includes subscriptions link for admin", () => {
      render(<Header />);
      const nav = screen.getByTestId("mobile-nav");
      const link = within(nav).getByText("Subscription Management").closest("a");
      expect(link).toHaveAttribute("href", "/admin/subscriptions");
    });

    it("includes announcements management link for admin", () => {
      render(<Header />);
      const nav = screen.getByTestId("mobile-nav");
      const link = within(nav).getByText("Announcement Management").closest("a");
      expect(link).toHaveAttribute("href", "/admin/announcements");
    });

    it("includes admin settings link for admin", () => {
      render(<Header />);
      const nav = screen.getByTestId("mobile-nav");
      const link = within(nav).getByText("Admin Settings").closest("a");
      expect(link).toHaveAttribute("href", "/admin/settings");
    });

    it("does not show user nav items for admin", () => {
      render(<Header />);
      const nav = screen.getByTestId("mobile-nav");

      // Admin should not see user-level Overview (they have Admin Overview)
      expect(within(nav).queryByText("Overview")).not.toBeInTheDocument();
      expect(within(nav).queryByText("Performance")).not.toBeInTheDocument();
      expect(within(nav).queryByText("Trades")).not.toBeInTheDocument();
    });
  });

  describe("Navigation sync with Sidebar", () => {
    it("user nav items count matches sidebar (7 items)", () => {
      mockAuth.role = "USER";
      render(<Header />);
      const nav = screen.getByTestId("mobile-nav");
      const links = within(nav).getAllByRole("link");
      expect(links).toHaveLength(7);
    });

    it("admin nav items count matches sidebar (13 items)", () => {
      mockAuth.role = "ADMIN";
      render(<Header />);
      const nav = screen.getByTestId("mobile-nav");
      const links = within(nav).getAllByRole("link");
      expect(links).toHaveLength(13);
    });
  });

  describe("Header structure", () => {
    it("renders logo and app name", () => {
      render(<Header />);
      // Header has 2 HookFi texts: one in the top bar, one in the sheet
      const logos = screen.getAllByText("HookFi");
      expect(logos.length).toBeGreaterThanOrEqual(1);
      const images = screen.getAllByAltText("HookFi");
      expect(images.length).toBeGreaterThanOrEqual(1);
    });

    it("renders language switcher", () => {
      render(<Header />);
      expect(screen.getByTestId("lang-switcher")).toBeInTheDocument();
    });

    it("displays user email", () => {
      mockAuth.email = "hello@hookfi.com";
      render(<Header />);
      expect(screen.getByText("hello@hookfi.com")).toBeInTheDocument();
    });
  });
});
