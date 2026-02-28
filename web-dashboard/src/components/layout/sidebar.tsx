"use client";

import Link from "next/link";
import Image from "next/image";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import { useState } from "react";
import {
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
} from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { useT } from "@/lib/i18n/i18n-context";
import { LanguageSwitcher } from "@/components/ui/language-switcher";
import { LogoutDialog } from "@/components/layout/logout-dialog";

export function Sidebar() {
  const pathname = usePathname();
  const { logout, email, role } = useAuth();
  const { t } = useT();
  const [logoutOpen, setLogoutOpen] = useState(false);

  const navItems = [
    { href: "/", label: t("nav.overview"), icon: LayoutDashboard },
    { href: "/performance", label: t("nav.performance"), icon: BarChart3 },
    { href: "/trades", label: t("nav.trades"), icon: History },
    { href: "/referral", label: t("nav.referral"), icon: Link2 },
    { href: "/settings", label: t("nav.settings"), icon: Settings },
  ];

  const adminItems = [
    { href: "/admin", label: t("nav.adminOverview"), icon: Monitor },
    { href: "/admin/users", label: t("nav.adminUsers"), icon: Users },
    { href: "/admin/referrals", label: t("nav.adminReferrals"), icon: ClipboardCheck },
    { href: "/admin/subscriptions", label: t("nav.adminSubscriptions"), icon: CreditCard },
  ];

  const isAdmin = role === "ADMIN";

  return (
    <aside className="hidden md:flex md:w-64 md:flex-col md:fixed md:inset-y-0 bg-card border-r border-border">
      {/* Logo */}
      <div className="flex items-center gap-2.5 px-6 py-5 border-b border-border">
        <Image
          src="/logo.jpg"
          alt="HookFi"
          width={28}
          height={28}
          className="rounded-lg"
        />
        <span className="text-lg font-bold">HookFi</span>
      </div>

      {/* Navigation */}
      <nav className="flex-1 px-3 py-4 space-y-1 overflow-y-auto">
        {/* Admin: only show admin items */}
        {isAdmin ? (
          adminItems.map((item) => {
            const isActive = pathname === item.href || (item.href !== "/admin" && pathname.startsWith(item.href));
            return (
              <Link
                key={item.href}
                href={item.href}
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

      {/* User & Logout */}
      <div className="border-t border-border px-3 py-4">
        <div className="flex items-center justify-between px-3 mb-2">
          <span className="text-xs text-muted-foreground truncate">
            {email || "User"}
          </span>
          <LanguageSwitcher />
        </div>
        <button
          onClick={() => setLogoutOpen(true)}
          className="flex items-center gap-3 w-full px-3 py-2.5 rounded-lg text-sm text-muted-foreground hover:text-foreground hover:bg-accent/50 transition-colors"
        >
          <LogOut className="h-5 w-5" />
          {t("nav.logout")}
        </button>
      </div>

      <LogoutDialog
        open={logoutOpen}
        onOpenChange={setLogoutOpen}
        onConfirm={() => {
          logout();
          window.location.href = "/login";
        }}
      />
    </aside>
  );
}
