"use client";

import { Sheet, SheetContent, SheetTrigger } from "@/components/ui/sheet";
import {
  Menu,
  LayoutDashboard,
  BarChart3,
  History,
  Link2,
  Settings,
  LogOut,
  Monitor,
  Users,
  ClipboardCheck,
  CreditCard,
  Megaphone,
  Bell,
  Zap,
  Radio,
  Send,
  TrendingUp,
  Users2,
  CandlestickChart,
  Wifi,
} from "lucide-react";
import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n/i18n-context";
import { LanguageSwitcher } from "@/components/ui/language-switcher";
import { useState } from "react";
import { LogoutDialog } from "@/components/layout/logout-dialog";

export function Header() {
  const pathname = usePathname();
  const { logout, email, role } = useAuth();
  const { t } = useT();
  const [open, setOpen] = useState(false);
  const [logoutOpen, setLogoutOpen] = useState(false);

  const navItems = [
    { href: "/", label: t("nav.overview"), icon: LayoutDashboard },
    { href: "/performance", label: t("nav.performance"), icon: BarChart3 },
    { href: "/chart", label: t("nav.chart"), icon: CandlestickChart },
    { href: "/trades", label: t("nav.trades"), icon: History },
    { href: "/referral", label: t("nav.referral"), icon: Link2 },
    { href: "/announcements", label: t("nav.announcements"), icon: Bell },
    { href: "/settings", label: t("nav.settings"), icon: Settings },
  ];

  const adminItems = [
    { href: "/admin", label: t("nav.adminOverview"), icon: Monitor },
    { href: "/admin/signal", label: t("nav.adminSignal"), icon: Zap },
    { href: "/admin/users", label: t("nav.adminUsers"), icon: Users },
    { href: "/admin/referrals", label: t("nav.adminReferrals"), icon: ClipboardCheck },
    { href: "/admin/subscriptions", label: t("nav.adminSubscriptions"), icon: CreditCard },
    { href: "/admin/analytics", label: t("nav.adminAnalytics"), icon: TrendingUp },
    { href: "/admin/insights", label: t("nav.adminInsights"), icon: Users2 },
    { href: "/admin/broadcast-logs", label: t("nav.adminBroadcastLogs"), icon: Radio },
    { href: "/admin/notifications", label: t("nav.adminNotifications"), icon: Send },
    { href: "/admin/announcements", label: t("nav.adminAnnouncements"), icon: Megaphone },
    { href: "/admin/monitor-settings", label: t("nav.adminMonitorSettings"), icon: Wifi },
    { href: "/admin/settings", label: t("nav.adminSettings"), icon: Settings },
  ];

  const isAdmin = role === "ADMIN";

  return (
    <header className="md:hidden sticky top-0 z-50 flex items-center justify-between px-4 py-3 bg-card border-b border-border">
      <div className="flex items-center gap-2">
        <Image src="/logo.jpg" alt="HookFi" width={24} height={24} className="rounded-md" />
        <span className="font-bold">HookFi</span>
      </div>

      <div className="flex items-center gap-2">
        <LanguageSwitcher />
        <Sheet open={open} onOpenChange={setOpen}>
          <SheetTrigger asChild>
            <button className="p-2 rounded-lg hover:bg-accent">
              <Menu className="h-5 w-5" />
            </button>
          </SheetTrigger>
          <SheetContent side="left" className="w-64 p-0 flex flex-col">
            <div className="flex items-center gap-2.5 px-6 py-5 border-b border-border">
              <Image src="/logo.jpg" alt="HookFi" width={28} height={28} className="rounded-lg" />
              <span className="text-lg font-bold">HookFi</span>
            </div>
            <nav className="px-3 py-4 space-y-1 overflow-y-auto flex-1 pb-20">
              {/* Admin: only show admin items */}
              {isAdmin ? (
                adminItems.map((item) => {
                  const isActive = pathname === item.href || (item.href !== "/admin" && pathname.startsWith(item.href));
                  return (
                    <Link
                      key={item.href}
                      href={item.href}
                      onClick={() => setOpen(false)}
                      className={cn(
                        "flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors",
                        isActive
                          ? "bg-purple-500/20 text-purple-400"
                          : "text-muted-foreground hover:text-foreground hover:bg-accent/50"
                      )}
                    >
                      <item.icon className="h-5 w-5" />
                      {item.label}
                    </Link>
                  );
                })
              ) : (
                /* Regular user: show user nav items */
                navItems.map((item) => {
                  const isActive = pathname === item.href;
                  return (
                    <Link
                      key={item.href}
                      href={item.href}
                      onClick={() => setOpen(false)}
                      className={cn(
                        "flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors",
                        isActive
                          ? "bg-accent text-accent-foreground"
                          : "text-muted-foreground hover:text-foreground hover:bg-accent/50"
                      )}
                    >
                      <item.icon className="h-5 w-5" />
                      {item.label}
                    </Link>
                  );
                })
              )}
            </nav>
            <div className="mt-auto border-t border-border px-3 py-4">
              <div className="px-3 mb-2 text-xs text-muted-foreground truncate">
                {email || "User"}
              </div>
              <button
                onClick={() => setLogoutOpen(true)}
                className="flex items-center gap-3 w-full px-3 py-2.5 rounded-lg text-sm text-muted-foreground hover:text-foreground hover:bg-accent/50"
              >
                <LogOut className="h-5 w-5" />
                {t("nav.logout")}
              </button>
            </div>
          </SheetContent>
        </Sheet>
      </div>

      <LogoutDialog
        open={logoutOpen}
        onOpenChange={setLogoutOpen}
        onConfirm={() => {
          logout();
          window.location.href = "/login";
        }}
      />
    </header>
  );
}
